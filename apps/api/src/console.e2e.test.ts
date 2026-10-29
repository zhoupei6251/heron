import { snowflakeId } from "@heron/db";
import { Controller, Get, type INestApplication } from "@nestjs/common";
import { JwtService } from "@nestjs/jwt";
import { Test } from "@nestjs/testing";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { existsSync } from "node:fs";
import type * as Fs from "node:fs";
import * as protoTools from "../../../packages/db/src/proto-tools";
import {
  type Db,
  openDb,
  migrate,
  changeTenantPlan,
  createTenant,
  withTenant,
  createUser,
  createFeedSource,
  createTask,
  createCandidate,
  createPlatformOperator,
  platformOperators,
  tasks,
} from "@heron/db/pg";
import { ConsoleSessionSchema, DEFAULT_CONSOLE_MENU_CONFIG } from "@heron/contracts";
import { hashPassword } from "@heron/domain";
import { AppModule } from "./app.module";
import { PG_DB } from "./db/pg";
import { REDIS, QUEUE } from "./redis/redis.module";
import { applyTransport } from "./transport";

// Block the import-time .env loader: this suite must never read deployment credentials.
const envFileSpy = vi.hoisted(() => vi.spyOn(process, "loadEnvFile").mockImplementation(() => {}));
vi.mock("node:fs", async (importOriginal) => {
  const fs = await importOriginal<typeof Fs>();
  return { ...fs, existsSync: vi.fn(fs.existsSync) };
});

const PASSWORD = "explicit-console-test-password";
const SECRET = "console-test-signing-key-at-least-32-characters";
const feed = {
  name: "演示订阅",
  url: "https://example.com/feed",
  frequencySec: 900,
  enabled: true,
};

@Controller("test-only-error")
class ErrorProbeController {
  @Get()
  fail(): never {
    throw new Error("SQL INSERT passwordHash=secret-value internal/path");
  }
}

describe("dual console HTTP (isolated PGlite + real socket)", () => {
  let db: Db;
  let app: INestApplication;
  let base: string;
  let tenantId: string;
  let ownerId: string;
  let foreignUser: string;
  let foreignFeed: string;
  let owner: string;
  let admin: string;
  let viewer: string;
  let platform: string;
  let platformId: string;
  const originalSecret = process.env.HERON_JWT_SECRET;

  const request = (path: string, cookie = "", method = "GET", body?: unknown, headers = {}) =>
    fetch(`${base}/api${path}`, {
      method,
      headers: { cookie, "content-type": "application/json", ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  const data = async (res: Response) => (await res.json()) as Record<string, unknown>;
  async function login(identifier: string, tenant = "alpha", password = PASSWORD) {
    const res = await request("/auth/login", "", "POST", { tenant, identifier, password });
    expect(res.status).toBe(201);
    return res.headers.getSetCookie()[0]!.split(";")[0]!;
  }
  function expectLegacyCleared(res: Response) {
    const legacy = res.headers
      .getSetCookie()
      .find((value) => value.startsWith("heron_platform_session="));
    expect(legacy).toContain("heron_platform_session=;");
    expect(legacy).toContain("Path=/api/platform;");
    expect(legacy).toContain("Expires=Thu, 01 Jan 1970");
  }
  async function platformLogin() {
    const res = await request("/auth/login", "", "POST", {
      identifier: "platform",
      password: PASSWORD,
    });
    expect(res.status).toBe(201);
    const body = ConsoleSessionSchema.parse(await data(res));
    expect(Object.keys(body).sort()).toEqual([
      "groups",
      "kind",
      "menus",
      "permissions",
      "roles",
      "user",
    ]);
    expect(body.kind).toBe("platform");
    expect(body.groups.map((group) => group.key)).toEqual([
      "platform-operations",
      "platform-config",
      "platform-monitor",
    ]);
    expect(Object.keys(body.user).sort()).toEqual(["displayName", "id", "username"]);
    expect(body.roles).toEqual(["platform"]);
    expect(body.permissions).toEqual([
      "platform.tenants.manage",
      "platform.usage.read",
      "platform.audit.read",
      "platform.health.read",
    ]);
    expect(body.menus.map((menu) => menu.id)).toEqual([
      "platform-overview",
      "tenants",
      "platform-settings",
      "platform-menus",
      "platform-usage",
      "platform-audit",
      "health",
    ]);
    const cookie = res.headers.getSetCookie()[0]!;
    expect(cookie).toMatch(/^heron_session=/);
    expect(cookie).toContain("Path=/;");
    expect(cookie).toContain("HttpOnly");
    expect(cookie).toContain("SameSite=Lax");
    expect(cookie).not.toMatch(/Expires|Max-Age/);
    expectLegacyCleared(res);
    const pair = cookie.split(";")[0]!;
    expect(await data(await request("/auth/me", pair))).toEqual(body);
    return pair;
  }

  beforeAll(async () => {
    process.env.HERON_JWT_SECRET = SECRET;
    db = openDb("memory");
    await migrate(db);
    tenantId = await createTenant(db, { name: "Alpha", slug: "alpha" });
    for (const role of ["owner", "admin", "viewer"] as const) {
      const id = await withTenant(db, tenantId, (t) =>
        createUser(t, {
          email: `${role}@example.com`,
          displayName: role,
          role,
          passwordHash: hashPassword(PASSWORD),
        }),
      );
      if (role === "owner") ownerId = id;
    }
    const other = await createTenant(db, { name: "Beta", slug: "beta" });
    await withTenant(db, other, async (t) => {
      foreignUser = await createUser(t, {
        email: "owner@example.com",
        displayName: "Other",
        role: "owner",
        passwordHash: hashPassword("beta-owner-password"),
      });
      for (const role of ["reviewer", "operator"] as const) {
        await createUser(t, {
          email: `${role}@example.com`,
          displayName: role,
          role,
          passwordHash: hashPassword(PASSWORD),
        });
      }
      foreignFeed = (await createFeedSource(t, foreignUser, feed)).id;
      await createTask(t, { capability: "demo", candidateId: null });
    });
    platformId = (
      await createPlatformOperator(db, {
        username: "platform",
        displayName: "平台",
        passwordHash: hashPassword(PASSWORD),
      })
    ).id;
    // Alpha 开局就是 3 个成员，正好用满免费档的席位，后面每个「加人成功」的用例都会先撞额度。
    // 席位闸自己有专门的一条用例（Beta 留在免费档），所以这里升到标准档，让各条用例只测自己那件事。
    await changeTenantPlan(db, platformId, tenantId, "standard");
    await createPlatformOperator(db, {
      username: "owner",
      displayName: "Separate platform identity",
      passwordHash: hashPassword("platform-owner-password"),
    });
    const module = await Test.createTestingModule({
      imports: [AppModule],
      controllers: [ErrorProbeController],
    })
      .overrideProvider(PG_DB)
      .useValue(db)
      .overrideProvider(REDIS)
      .useValue(null)
      .overrideProvider(QUEUE)
      .useValue(null)
      .compile();
    app = module.createNestApplication({ logger: false });
    applyTransport(app);
    await app.listen(0, "127.0.0.1");
    base = await app.getUrl();
    owner = await login("owner@example.com");
    admin = await login("admin@example.com");
    viewer = await login("viewer@example.com");
  });
  afterAll(async () => {
    await app?.close();
    envFileSpy.mockRestore();
    if (originalSecret === undefined) delete process.env.HERON_JWT_SECRET;
    else process.env.HERON_JWT_SECRET = originalSecret;
  });

  it("serves whole-tenant overview and all paginated content, never foreign rows", async () => {
    const res = await request("/manage/overview", viewer);
    expect(res.status).toBe(200);
    expect(await data(res)).toEqual({
      candidates: 0,
      tasks: 0,
      review: 0,
      published: 0,
      failed: 0,
      members: 3,
      recentTasks: [],
    });
    for (const path of ["candidates", "tasks", "assets", "publishes"]) {
      expect(await data(await request(`/manage/${path}?page=2&pageSize=1`, viewer))).toEqual({
        items: [],
        total: 0,
        page: 2,
        pageSize: 1,
      });
    }
    expect((await request("/manage/tasks?page=0", viewer)).status).toBe(400);
    expect((await data(await request("/manage/workspace", viewer))).id).toBe(tenantId);
  });

  it("owner alone manages safe members; admin configures workspace/feeds/retention with audit", async () => {
    for (const cookie of [admin, viewer]) {
      expect((await request("/manage/members", cookie)).status).toBe(403);
      expect(
        (
          await request("/manage/members", cookie, "POST", {
            email: "new@example.com",
            displayName: "New",
            password: PASSWORD,
            role: "viewer",
          })
        ).status,
      ).toBe(403);
    }
    expect((await request("/manage/feeds", viewer)).status).toBe(403);
    expect((await request("/manage/audit", viewer)).status).toBe(403);
    expect(
      (
        await request("/settings/model", viewer, "PUT", {
          baseUrl: "https://api.qnaigc.com/v1",
          model: "qiniu/conatus-free",
        })
      ).status,
    ).toBe(403);
    expect((await request("/manage/usage", viewer)).status).toBe(200);
    expect((await request("/manage/workspace", viewer, "PATCH", { name: "No" })).status).toBe(403);
    expect((await request("/manage/workspace", admin, "PATCH", { name: "Renamed" })).status).toBe(
      200,
    );
    const created = await request("/manage/feeds", admin, "POST", feed);
    expect(created.status).toBe(201);
    const row = await data(created);
    expect(
      (await request(`/manage/feeds/${String(row.id)}`, admin, "PUT", { ...feed, enabled: false }))
        .status,
    ).toBe(200);
    expect((await request("/settings/retention", admin, "PUT", { days: 45 })).status).toBe(200);
    const saved = await request("/settings/model", admin, "PUT", {
      baseUrl: "https://api.qnaigc.com/v1/",
      model: "qiniu/conatus-free",
      apiKey: "sk-e2e-secret",
    });
    expect(saved.status).toBe(200);
    const settingsState = await data(await request("/settings", admin));
    expect(settingsState.model).toEqual({
      baseUrl: "https://api.qnaigc.com/v1",
      model: "qiniu/conatus-free",
      hasApiKey: true,
    });
    // 密钥入库但不出接口：回显只有「配没配」
    expect(JSON.stringify(settingsState)).not.toContain("sk-e2e-secret");
    const audit = JSON.stringify(await data(await request("/manage/audit", admin)));
    expect(audit).toContain("retention.updated");
    expect(audit).toContain("model.updated");
    expect(audit).not.toContain("sk-e2e-secret");
    expect(audit).not.toMatch(/scrypt|passwordHash|explicit-console/);
    expect((await request("/manage/usage", admin)).status).toBe(200);
  });

  it("isolates IDs, rejects malformed input/duplicates and preserves the last owner", async () => {
    expect(
      (await request(`/manage/members/${foreignUser}`, owner, "PATCH", { disabled: true })).status,
    ).toBe(404);
    expect((await request(`/manage/feeds/${foreignFeed}`, admin, "PUT", feed)).status).toBe(404);
    // 非法 id：不是数字串，或者 0（雪花 id 从 1 起）。
    // 注意 "1" 现在是**合法**的 id 形状——它正是保留的哨兵租户号。
    for (const id of ["not-an-id", "0"]) {
      expect(
        (await request(`/manage/members/${id}`, owner, "PATCH", { disabled: true })).status,
      ).toBe(400);
      expect((await request(`/manage/feeds/${id}`, admin, "PUT", feed)).status).toBe(400);
      expect(
        (await request(`/reviews/${id}/confirm`, owner, "POST", { contactSheetFingerprint: "fp" }))
          .status,
      ).toBe(400);
    }
    expect(
      (await request(`/manage/members/${ownerId}`, owner, "PATCH", { role: "viewer" })).status,
    ).toBe(403);
    const body = {
      email: "new@example.com",
      displayName: "New",
      password: PASSWORD,
      role: "viewer",
    };
    const created = await request("/manage/members", owner, "POST", body);
    expect(created.status).toBe(201);
    expect(Object.keys(await data(created)).sort()).toEqual([
      "createTime",
      "disabledAt",
      "displayName",
      "email",
      "id",
      "role",
    ]);
    expect((await request("/manage/members", owner, "POST", body)).status).toBe(409);
  });

  it("席位满了就拒绝：免费档 3 席加第 4 个成员被额度拦下，原因说清哪一格", async () => {
    const betaOwner = await login("owner@example.com", "beta", "beta-owner-password");
    const res = await request("/manage/members", betaOwner, "POST", {
      email: "fourth@example.com",
      displayName: "Fourth",
      password: PASSWORD,
      role: "viewer",
    });
    expect(res.status).toBe(409);
    const text = JSON.stringify(await data(res));
    expect(text).toContain("成员数已达该档上限（3/3");
    expect(text).toContain("平台台");
  });

  it("额度可见但改不了：租户台读到档位与上限，改档只有平台台能做并留审计", async () => {
    const quota = await data(await request("/manage/quota", viewer));
    expect(quota).toMatchObject({
      plan: "standard",
      limits: { seats: 20, rendersPerMonth: 1_000, publishesPerMonth: 300 },
    });
    const usage = (await data(await request("/manage/quota", viewer))).usage as Record<
      string,
      unknown
    >;
    expect(Object.keys(usage).sort()).toEqual(["publishes", "renders", "seats"]);
    const opCookie = await platformLogin();
    expect(
      (await request(`/platform/tenants/${tenantId}/plan`, viewer, "PATCH", { plan: "free" }))
        .status,
    ).toBe(401);
    const changed = await request(`/platform/tenants/${tenantId}/plan`, opCookie, "PATCH", {
      plan: "enterprise",
    });
    expect(changed.status).toBe(200);
    expect(await data(changed)).toMatchObject({ id: tenantId, plan: "enterprise" });
    expect(await data(await request("/manage/quota", viewer))).toMatchObject({
      plan: "enterprise",
      limits: { seats: 100 },
    });
    // 不认识的档位在契约层就拒掉，不会写进库再被 CHECK 拦成 500
    expect(
      (
        await request(`/platform/tenants/${tenantId}/plan`, opCookie, "PATCH", {
          plan: "platinum",
        })
      ).status,
    ).toBe(400);
    expect(
      (
        await request(`/platform/tenants/${snowflakeId()}/plan`, opCookie, "PATCH", {
          plan: "free",
        })
      ).status,
    ).toBe(404);
    // 改回标准档：后面按标准档的额度走
    expect(
      (
        await request(`/platform/tenants/${tenantId}/plan`, opCookie, "PATCH", {
          plan: "standard",
        })
      ).status,
    ).toBe(200);
    const audit = await data(await request("/platform/audit?q=tenant.plan_changed", opCookie));
    expect(audit.total as number).toBeGreaterThanOrEqual(3);
    expect((audit.items as Record<string, unknown>[])[0]).toMatchObject({
      action: "tenant.plan_changed",
      targetId: tenantId,
      detail: { from: "enterprise", to: "standard" },
    });
  });

  it("keeps platform defaults behind the platform audience and validates each key", async () => {
    const opCookie = await platformLogin();
    const defaults = (body: Record<string, unknown>) => {
      const items = body.items as { key: string; value: string; isDefault: boolean }[];
      return Object.fromEntries(items.map((item) => [item.key, item]));
    };
    const empty = defaults(await data(await request("/platform/settings", opCookie)));
    expect(Object.keys(empty)).toHaveLength(4);
    expect(Object.values(empty).every((item) => item.isDefault)).toBe(true);
    // 租户 cookie 进不了平台通道：这是两个受众，不是两级权限
    expect((await request("/platform/settings", owner)).status).toBe(401);
    expect((await request("/platform/settings", owner, "PUT", { values: {} })).status).toBe(401);
    // 坏值在契约层就拒，并点名是哪一项——「保存失败」四个字帮不上填表的人
    const bad = await request("/platform/settings", opCookie, "PUT", {
      values: { default_retention_days: "0" },
    });
    expect(bad.status).toBe(400);
    expect(JSON.stringify(await data(bad))).toContain("默认成片保留天数");

    const putRes = await request("/platform/settings", opCookie, "PUT", {
      values: { default_model_name: "qiniu/conatus-free", default_retention_days: "14" },
    });
    expect(putRes.status).toBe(200);
    const saved = defaults(await data(putRes));
    expect(Object.keys(saved).sort()).toEqual([
      "default_model_base_url",
      "default_model_name",
      "default_plan",
      "default_retention_days",
    ]);
    expect(saved.default_model_name).toMatchObject({
      value: "qiniu/conatus-free",
      isDefault: false,
      updateBy: platformId,
    });
    expect(saved.default_retention_days).toMatchObject({ value: "14", isDefault: false });
    expect(saved.default_plan).toMatchObject({ isDefault: true });
    const audit = await data(await request("/platform/audit?q=platform_setting.updated", opCookie));
    expect(audit.total).toBeGreaterThanOrEqual(2);
    // 审计里要能看出「改成了什么」：detail.to 是那一格的新值，不是一句「已更新」
    const rows = audit.items as Array<{ action: string; detail: Record<string, unknown> }>;
    expect(rows[0]?.action).toBe("platform_setting.updated");
    expect(typeof rows[0]?.detail.to).toBe("string");

    // 空串恢复默认：别把默认值留在库里影响后面的用例
    const restored = defaults(
      await data(
        await request("/platform/settings", opCookie, "PUT", {
          values: { default_model_name: "", default_retention_days: "" },
        }),
      ),
    );
    expect(restored.default_model_name?.isDefault).toBe(true);
    expect(restored.default_retention_days?.isDefault).toBe(true);
  });

  it("lets the platform reshape tenant menus and reflects it on the next session read", async () => {
    const opCookie = await platformLogin();
    const initial = await data(await request("/platform/menus", opCookie));
    expect(Object.keys(initial).sort()).toEqual(["config", "isDefault"]);
    expect(initial.isDefault).toBe(true);
    expect(initial.config).toEqual(DEFAULT_CONSOLE_MENU_CONFIG);
    // 菜单配置是平台受众的写：租户 cookie 连读都不行
    for (const cookie of [owner, viewer]) {
      expect((await request("/platform/menus", cookie)).status).toBe(401);
      expect(
        (await request("/platform/menus", cookie, "PUT", DEFAULT_CONSOLE_MENU_CONFIG)).status,
      ).toBe(401);
    }

    const custom = structuredClone(DEFAULT_CONSOLE_MENU_CONFIG);
    custom.groups[0]!.label = "新闻线";
    custom.groups[0]!.items[0]!.label = "总览";
    // 候选管理收窄到 reviewer：viewer 的菜单里就该没有它
    custom.groups[0]!.items[1]!.roles = ["reviewer"];
    const saved = await request("/platform/menus", opCookie, "PUT", custom);
    expect(saved.status).toBe(200);
    expect(await data(saved)).toMatchObject({ isDefault: false, config: custom });

    const viewerSession = ConsoleSessionSchema.parse(await data(await request("/auth/me", viewer)));
    expect(viewerSession.groups[0]).toMatchObject({ key: "news", label: "新闻线" });
    const viewerMenus = viewerSession.menus.map((menu) => menu.id);
    expect(viewerMenus).not.toContain("candidates");
    expect(viewerSession.menus.find((menu) => menu.id === "overview")?.label).toBe("总览");
    // 隐藏的个人页仍在菜单数据里，路由与头像入口都靠它
    expect(viewerSession.menus.find((menu) => menu.id === "profile")?.hidden).toBe(true);

    const tooWide = structuredClone(DEFAULT_CONSOLE_MENU_CONFIG);
    tooWide.groups[2]!.items[0]!.roles = ["viewer"];
    const rejected = await request("/platform/menus", opCookie, "PUT", tooWide);
    expect(rejected.status).toBe(400);
    expect(JSON.stringify(await data(rejected))).toContain("可见角色超出");

    const audit = await data(await request("/platform/audit?q=console_menu.updated", opCookie));
    expect(audit.total).toBeGreaterThanOrEqual(1);

    // 收尾：后面的用例按默认菜单断言
    expect(
      (await request("/platform/menus", opCookie, "PUT", DEFAULT_CONSOLE_MENU_CONFIG)).status,
    ).toBe(200);
    const restored = ConsoleSessionSchema.parse(await data(await request("/auth/me", viewer)));
    expect(restored.menus.map((menu) => menu.id)).toContain("candidates");
  });

  it("updates profile, rejects bad password, then revokes every session on password change but only its own on logout", async () => {
    const old = await login("new@example.com");
    const other = await login("new@example.com");
    const profile = await data(
      await request("/auth/profile", old, "PATCH", { displayName: "Changed" }),
    );
    expect(profile).toMatchObject({
      displayName: "Changed",
      tenantId,
      tenantName: "Renamed",
      tenantSlug: "alpha",
    });
    expect(
      (
        await request("/auth/password", old, "POST", {
          currentPassword: "wrong",
          newPassword: "a-new-console-password",
        })
      ).status,
    ).toBe(400);
    expect((await request("/auth/me", old)).status).toBe(200);
    const changed = await request("/auth/password", old, "POST", {
      currentPassword: PASSWORD,
      newPassword: "a-new-console-password",
    });
    expect(changed.status).toBe(201);
    expect(await data(changed)).toEqual({ ok: true });
    expect(changed.headers.getSetCookie()[0]).toContain("Expires=");
    for (const cookie of [old, other]) expect((await request("/auth/me", cookie)).status).toBe(401);
    expect(
      (
        await request("/auth/login", "", "POST", {
          tenant: "alpha",
          identifier: "new@example.com",
          password: PASSWORD,
        })
      ).status,
    ).toBe(401);
    const fresh = await login("new@example.com", "alpha", "a-new-console-password");
    const sibling = await login("new@example.com", "alpha", "a-new-console-password");
    const logout = await request("/auth/logout", fresh, "POST");
    expect(await data(logout)).toEqual({ ok: true });
    expect(logout.headers.getSetCookie()[0]).toContain("heron_session=;");
    expect(logout.headers.getSetCookie()[0]).toContain("Path=/;");
    expectLegacyCleared(logout);
    expect((await request("/auth/me", fresh)).status).toBe(401);
    // 退出登录只关自己这一条：另一台设备上的会话照常在线。
    // 以前是一把拉高 session_version，等于「退出登录顺手把手机上的我也踢了」，多设备登录就成了互相顶号。
    expect((await request("/auth/me", sibling)).status).toBe(200);
  });

  it("在线会话：成员只看得见自己的，强退别人的要 session.manage", async () => {
    type Row = { id: string; userId: string; userEmail: string; current: boolean; self: boolean };
    const memberA = await login("viewer@example.com");
    const memberB = await login("viewer@example.com");
    const ownerC = await login("owner@example.com");

    const mine = await request("/manage/sessions?pageSize=50", memberA);
    expect(mine.status).toBe(200);
    const own = (await data(mine)).items as Row[];
    // 没有 session.manage 的人这一份列表就是「我的登录」，不是「空间的登录」
    expect(own.every((row) => row.userEmail === "viewer@example.com")).toBe(true);
    expect(own.filter((row) => row.current)).toHaveLength(1);
    expect(own.every((row) => row.self)).toBe(true);
    const thisOne = own.find((row) => row.current)!;
    const other = own.find((row) => !row.current)!;

    // 自己那条随时能退；退掉之后只有它失效，手上这张照常在线
    const self = await request(`/manage/sessions/${other.id}`, memberA, "DELETE");
    expect(self.status).toBe(200);
    expect(await data(self)).toMatchObject({ id: other.id, self: true });
    expect((await request("/auth/me", memberB)).status).toBe(401);
    expect((await request("/auth/me", memberA)).status).toBe(200);

    // 别人的会话：拒，且理由说清是被权限挡住的（不是「会话不存在」）
    const ownerSessions = await data(await request("/manage/sessions?q=owner@example.com", ownerC));
    const ownerRow = (ownerSessions.items as Row[]).find((row) => !row.current)!;
    const denied = await request(`/manage/sessions/${ownerRow.id}`, memberA, "DELETE");
    expect(denied.status).toBe(403);
    expect((await request("/auth/me", ownerC)).status).toBe(200);

    // 有 session.manage 的人强退别人：被退的那张票下一个请求就失效
    const forced = await request(`/manage/sessions/${thisOne.id}`, ownerC, "DELETE");
    expect(forced.status).toBe(200);
    expect(await data(forced)).toMatchObject({
      id: thisOne.id,
      userId: thisOne.userId,
      self: false,
    });
    expect((await request("/auth/me", memberA)).status).toBe(401);

    // 强退要留痕：审计里能按 session.revoked 查到，目标就是那条会话
    const audit = await data(await request("/manage/audit?q=session.revoked", ownerC));
    const rows = audit.items as Array<{ action: string; targetId: string | null }>;
    expect(rows.map((row) => row.targetId)).toEqual(expect.arrayContaining([other.id, thisOne.id]));
    expect(rows.every((row) => row.action === "session.revoked")).toBe(true);
  });

  it("compares full Origins, supports secure TLS termination and never trusts forwarded headers", async () => {
    const body = { tenant: "alpha", identifier: "owner@example.com", password: PASSWORD };
    const secure = process.env.HERON_COOKIE_SECURE;
    try {
      process.env.HERON_COOKIE_SECURE = "0";
      expect((await request("/auth/login", "", "POST", body, { origin: base })).status).toBe(201);
      expect(
        (
          await request("/auth/login", "", "POST", body, {
            origin: base.replace("http:", "https:"),
            "x-forwarded-proto": "https",
          })
        ).status,
      ).toBe(403);
      for (const origin of [
        "https://attacker.example",
        "null",
        "not-a-url",
        "file:///tmp",
        `${base}/path`,
        `${base}, https://attacker.example`,
      ]) {
        expect((await request("/auth/login", "", "POST", body, { origin })).status).toBe(403);
      }
      process.env.HERON_COOKIE_SECURE = "1";
      expect(
        (
          await request("/auth/login", "", "POST", body, {
            origin: base,
            "x-forwarded-proto": "http",
          })
        ).status,
      ).toBe(403);
      const tls = await request("/auth/login", "", "POST", body, {
        origin: base.replace("http:", "https:"),
      });
      expect(tls.status).toBe(201);
      expect(tls.headers.getSetCookie()[0]).toContain("Secure");
      expect(
        (
          await request("/auth/login", "", "POST", body, {
            origin: "https://attacker.example",
            "x-forwarded-host": "attacker.example",
          })
        ).status,
      ).toBe(403);
      expect((await request("/auth/login", "", "POST", body)).status).toBe(201);
    } finally {
      if (secure === undefined) delete process.env.HERON_COOKIE_SECURE;
      else process.env.HERON_COOKIE_SECURE = secure;
    }
  });

  it.each([
    [
      "owner",
      "alpha",
      [
        "task.claim",
        "review.confirm",
        "settings.write",
        "aigc.set",
        "user.manage",
        "usage.read",
        "session.manage",
      ],
      ["members", "feeds", "settings", "audit"],
    ],
    [
      "admin",
      "alpha",
      [
        "task.claim",
        "review.confirm",
        "settings.write",
        "aigc.set",
        "usage.read",
        "session.manage",
      ],
      ["feeds", "settings", "audit"],
    ],
    ["reviewer", "beta", ["task.claim", "review.confirm", "usage.read"], []],
    ["operator", "beta", ["task.claim", "usage.read"], []],
    ["viewer", "alpha", ["usage.read"], []],
  ] as const)(
    "returns only %s access and safe menu metadata from login and me",
    async (role, tenant, permissions, privilegedMenus) => {
      const res = await request("/auth/login", "heron_platform_session=legacy", "POST", {
        tenant: ` ${tenant.toUpperCase()} `,
        identifier: ` ${role.toUpperCase()}@EXAMPLE.COM `,
        password: PASSWORD,
      });
      expect(res.status).toBe(201);
      const session = ConsoleSessionSchema.parse(await data(res));
      expect(session).toMatchObject({
        kind: "tenant",
        user: { email: `${role}@example.com`, role, tenantSlug: tenant },
      });
      expect(session.roles).toEqual([role]);
      expect(session.permissions).toEqual(permissions);
      expect(session.menus.map((menu) => menu.id).sort()).toEqual(
        [
          "overview",
          "candidates",
          "production",
          "review",
          "publish",
          "library",
          "proto",
          "roles",
          "usage",
          "sessions",
          "profile",
          ...privilegedMenus,
        ].sort(),
      );
      // 默认配置下每个页面都归在某个模块里：分组键必须出现在 groups 名单中
      expect(session.groups.map((group) => group.key)).toEqual([
        "news",
        "proto",
        "system",
        "monitor",
      ]);
      for (const menu of session.menus) {
        expect(Object.keys(menu).sort()).toEqual(["group", "hidden", "id", "label", "path"]);
        expect(session.groups.map((group) => group.key)).toContain(menu.group);
        expect(menu.path).toBe(`/${menu.id}`);
      }
      expectLegacyCleared(res);
      const cookie = res.headers.getSetCookie()[0]!.split(";")[0]!;
      expect(await data(await request("/auth/me", cookie))).toEqual(session);
      expect(JSON.stringify(session)).not.toMatch(/scrypt|passwordHash|sessionVersion|token/);
    },
  );

  it("never merges same-name actors or falls back across workspaces and realms", async () => {
    const identities = [];
    for (const [tenant, identifier, password, kind, id] of [
      ["alpha", "owner@example.com", PASSWORD, "tenant", ownerId],
      ["beta", "owner@example.com", "beta-owner-password", "tenant", foreignUser],
      ["", "owner", "platform-owner-password", "platform", undefined],
    ] as const) {
      const res = await request("/auth/login", "", "POST", { tenant, identifier, password });
      expect(res.status).toBe(201);
      const session = ConsoleSessionSchema.parse(await data(res));
      expect(session.kind).toBe(kind);
      if (id) expect(session.user.id).toBe(id);
      identities.push(session.user.id);
    }
    expect(new Set(identities).size).toBe(3);
    for (const [tenant, identifier, password] of [
      ["alpha", "owner@example.com", "platform-owner-password"],
      ["beta", "owner@example.com", PASSWORD],
      ["missing", "owner@example.com", PASSWORD],
      ["missing", "platform@example.com", PASSWORD],
      ["alpha", "platform@example.com", PASSWORD],
      ["", "owner", PASSWORD],
    ]) {
      const res = await request("/auth/login", "", "POST", { tenant, identifier, password });
      expect(res.status).toBe(401);
      expect((await data(res)).message).toBe("账号或口令不正确");
      expect(res.headers.getSetCookie()).toEqual([]);
    }
    // 平台运营用户名走平台分支（形状里没有 @），三种空值写法都算「没带空间码」
    for (const tenant of ["", "   ", undefined]) {
      expect(
        (
          await request("/auth/login", "", "POST", {
            tenant,
            identifier: " PLATFORM ",
            password: PASSWORD,
          })
        ).status,
      ).toBe(201);
    }
    // 不带空间码的邮箱：恰好一个空间的口令对得上，就登进那一家（界面上选空间那一步已经没了）
    const alphaByName = await request("/auth/login", "", "POST", {
      identifier: "owner@example.com",
      password: PASSWORD,
    });
    expect(alphaByName.status).toBe(201);
    expect(ConsoleSessionSchema.parse(await data(alphaByName)).user).toMatchObject({
      id: ownerId,
      tenantSlug: "alpha",
    });
    const betaByName = await request("/auth/login", "", "POST", {
      identifier: "owner@example.com",
      password: "beta-owner-password",
    });
    expect(betaByName.status).toBe(201);
    expect(ConsoleSessionSchema.parse(await data(betaByName)).user).toMatchObject({
      id: foreignUser,
      tenantSlug: "beta",
    });
    // 邮箱形状的字符串只是不再被边界拒掉：它在租户分支里查无此人，答案仍旧是 401
    for (const tenant of ["", "   ", undefined]) {
      expect(
        (
          await request("/auth/login", "", "POST", {
            tenant,
            identifier: "platform@example.com",
            password: PASSWORD,
          })
        ).status,
      ).toBe(401);
    }
  });

  it("validates the unified login boundary for both realms before authentication", async () => {
    for (const tenant of ["alpha", ""]) {
      for (const password of ["short", "x".repeat(129)]) {
        expect(
          (
            await request("/auth/login", "", "POST", {
              tenant,
              identifier: "owner@example.com",
              password,
            })
          ).status,
        ).toBe(400);
      }
    }
    for (const [tenant, identifier] of [
      ["alpha", "not-email"],
      ["", "ad/min"],
      ["", "管理员"],
    ]) {
      expect(
        (await request("/auth/login", "", "POST", { tenant, identifier, password: PASSWORD }))
          .status,
      ).toBe(400);
    }
    for (const tenant of [null, "bad/workspace", "a".repeat(64)]) {
      expect(
        (
          await request("/auth/login", "", "POST", {
            tenant,
            identifier: "owner@example.com",
            password: PASSWORD,
          })
        ).status,
      ).toBe(400);
    }
  });

  it("lets a platform operator view one workspace read-only and come back", async () => {
    const operator = await platformLogin();
    // 这一列不是对象而是数组，data() 那个 Record 形状套不住它
    const spaces = (await (await request("/auth/workspaces", operator)).json()) as {
      id: string;
      slug: string;
    }[];
    // 不按名字断言顺序：前面的用例把 Alpha 改名了，这里只问「两家都在且只有两家」
    expect(spaces.map((space) => space.slug).sort()).toEqual(["alpha", "beta"]);
    // 空间成员问同一句只拿到自家那一个：切换器不属于他
    expect(await data(await request("/auth/workspaces", owner))).toHaveLength(1);
    const betaId = spaces.find((space) => space.slug === "beta")!.id;

    const view = await request("/auth/workspace-view", operator, "POST", { tenantId: betaId });
    expect(view.status).toBe(201);
    const session = ConsoleSessionSchema.parse(await data(view));
    expect(session.kind).toBe("tenant");
    expect(session.user).toMatchObject({
      scope: "platform-view",
      role: "owner",
      tenantSlug: "beta",
      email: "platform",
    });
    const cookie = view.headers.getSetCookie()[0]!.split(";")[0]!;

    // 读得到这一家。GET /manage/feeds 的门槛是 settings.write——按动词判才放得行
    const feeds = await request("/manage/feeds", cookie);
    expect(feeds.status).toBe(200);
    expect(JSON.stringify(await data(feeds))).toContain(foreignFeed);

    const readOnly = "以平台身份查看工作空间是只读的：这一步请由该空间的成员执行。";
    // 有动作的、没标 @Roles 的、连读起来像读的写接口都点一遍：只读闸是按 HTTP 方法判的，不看有没有声明动作
    const writeRoutes: [string, string][] = [
      ["/manage/feeds", "POST"],
      [`/manage/feeds/${foreignFeed}/fetch`, "POST"],
      [`/manage/members/${foreignUser}`, "PATCH"],
      ["/auth/profile", "PATCH"],
      ["/auth/password", "POST"],
    ];
    for (const [path, method] of writeRoutes) {
      const res = await request(path, cookie, method, {});
      expect(res.status, `${method} ${path}`).toBe(403);
      expect((await data(res)).message).toBe(readOnly);
    }
    // 成员借查看票的路也进不来
    expect(
      (await request("/auth/workspace-view", owner, "POST", { tenantId: betaId })).status,
    ).toBe(403);

    const back = await request("/auth/workspace-view/end", cookie, "POST");
    expect(back.status).toBe(201);
    expect(ConsoleSessionSchema.parse(await data(back)).kind).toBe("platform");
    const backCookie = back.headers.getSetCookie()[0]!.split(";")[0]!;
    expect((await request("/platform/tenants", backCookie)).status).toBe(200);
    // 查看票当场换掉了：拿着平台票去敲租户接口就是 401，没有第三种身份
    expect((await request("/manage/feeds", backCookie)).status).toBe(401);

    const audit = await data(await request("/platform/audit", backCookie));
    expect(JSON.stringify(audit)).toContain("workspace.view_started");
    expect(JSON.stringify(audit)).toContain("workspace.view_ended");
  });

  it("keeps realm guards separate after unified login, including tenant mutations and human review", async () => {
    for (const path of ["tenants", "usage", "audit", "health"]) {
      expect((await request(`/platform/${path}`)).status).toBe(401);
      expect((await request(`/platform/${path}`, owner)).status).toBe(401);
    }
    const tenantBody = {
      name: "Forbidden",
      slug: "forbidden",
      ownerEmail: "bad@example.com",
      ownerDisplayName: "Bad",
      ownerPassword: PASSWORD,
    };
    expect((await request("/platform/tenants", owner, "POST", tenantBody)).status).toBe(401);
    expect(
      (await request(`/platform/tenants/${tenantId}/status`, owner, "PATCH", { suspended: true }))
        .status,
    ).toBe(401);
    platform = await platformLogin();
    expect(
      (
        await request(
          "/auth/login",
          "",
          "POST",
          { identifier: "platform", password: PASSWORD },
          { origin: "https://attacker.example" },
        )
      ).status,
    ).toBe(403);
    expect((await request("/auth/me", platform)).status).toBe(200);
    for (const path of ["/manage/tasks", "/tasks", "/reviews/pending", "/settings"]) {
      expect((await request(path, platform)).status).toBe(401);
    }
    expect((await request("/auth/profile", platform, "PATCH", { displayName: "No" })).status).toBe(
      401,
    );
    expect(
      (
        await request("/auth/password", platform, "POST", {
          currentPassword: PASSWORD,
          newPassword: "replacement-password",
        })
      ).status,
    ).toBe(401);
    expect(
      (
        await request("/manage/members", platform, "POST", {
          email: "forbidden@example.com",
          displayName: "No",
          password: PASSWORD,
          role: "owner",
        })
      ).status,
    ).toBe(401);
    expect(
      (
        await request(`/reviews/${snowflakeId()}/confirm`, platform, "POST", {
          contactSheetFingerprint: "fp",
        })
      ).status,
    ).toBe(401);
    const invalid = await request("/auth/login", "", "POST", {
      identifier: "platform",
      password: "wrong-platform-password",
    });
    const absent = await request("/auth/login", "", "POST", {
      identifier: "missing",
      password: PASSWORD,
    });
    expect(invalid.status).toBe(401);
    expect(absent.status).toBe(401);
    expect(await data(invalid)).toEqual(await data(absent));
  });

  it("removes the independent platform auth endpoints rather than aliasing them", async () => {
    for (const cookie of ["", owner, platform]) {
      for (const [path, method] of [
        ["login", "POST"],
        ["me", "GET"],
        ["logout", "POST"],
      ] as const) {
        expect(
          (
            await request(
              `/platform/auth/${path}`,
              cookie,
              method,
              method === "POST"
                ? {
                    identifier: "platform",
                    password: PASSWORD,
                  }
                : undefined,
            )
          ).status,
        ).toBe(404);
      }
    }
    expect((await request("/auth/me", platform)).status).toBe(200);
  });

  it("ignores the legacy cookie, even when it names a different actor beside the unified cookie", async () => {
    const legacyPlatform = platform.replace("heron_session=", "heron_platform_session=");
    const legacyTenant = owner.replace("heron_session=", "heron_platform_session=");
    for (const cookie of [legacyPlatform, legacyTenant]) {
      for (const path of ["/auth/me", "/platform/tenants", "/manage/tasks"]) {
        expect((await request(path, cookie)).status).toBe(401);
      }
      expect((await request("/auth/logout", cookie, "POST")).status).toBe(401);
    }
    const tenantCookie = `${legacyPlatform}; ${owner}`;
    expect(await data(await request("/auth/me", tenantCookie))).toMatchObject({
      kind: "tenant",
      user: { id: ownerId },
    });
    expect((await request("/platform/tenants", tenantCookie)).status).toBe(401);
    const platformCookie = `${legacyTenant}; ${platform}`;
    expect(await data(await request("/auth/me", platformCookie))).toMatchObject({
      kind: "platform",
    });
    expect((await request("/manage/tasks", platformCookie)).status).toBe(401);
  });

  it("rejects forged, expired, wrong-algorithm, ambiguous-audience and stale sessions before exposing access", async () => {
    const jwt = new JwtService({ secret: SECRET });
    for (const [actor, audience, route] of [
      [{ sub: ownerId, tid: tenantId, role: "owner", ver: 0 }, "heron-tenant", "/manage/tasks"],
      [{ sub: platformId, ver: 0 }, "heron-platform", "/platform/tenants"],
    ] as const) {
      const payload = { ...actor, aud: audience };
      const unversioned = Object.fromEntries(
        Object.entries(payload).filter(([key]) => key !== "ver"),
      );
      const badTokens = [
        await new JwtService({
          secret: "different-test-signing-key-at-least-32-characters",
        }).signAsync(payload),
        await jwt.signAsync(payload, { algorithm: "HS384" }),
        await jwt.signAsync({ ...payload, exp: 1 }),
        await jwt.signAsync({ ...actor, aud: "other" }),
        await jwt.signAsync({ ...actor, aud: ["heron-tenant", "heron-platform"] }),
        await jwt.signAsync(actor),
        await jwt.signAsync(unversioned),
        await jwt.signAsync({ ...payload, ver: 100 }),
        "not-a-jwt",
      ];
      for (const token of badTokens) {
        const cookie = `heron_session=${token}`;
        expect((await request("/auth/me", cookie)).status).toBe(401);
        expect((await request("/auth/logout", cookie, "POST")).status).toBe(401);
        expect((await request(route, cookie)).status).toBe(401);
      }
    }
    const swapped = await jwt.signAsync({
      sub: ownerId,
      tid: tenantId,
      role: "owner",
      ver: 0,
      aud: "heron-platform",
    });
    expect((await request("/auth/me", `heron_session=${swapped}`)).status).toBe(401);
  });

  it("atomically provisions tenant+owner, suspends immediately, and records only safe platform audit", async () => {
    const body = {
      name: "New tenant",
      slug: "new-tenant",
      ownerEmail: "initial@example.com",
      ownerDisplayName: "Initial",
      ownerPassword: PASSWORD,
    };
    const res = await request("/platform/tenants", platform, "POST", body);
    expect(res.status).toBe(201);
    const tenant = await data(res);
    expect((await request("/platform/tenants", platform, "POST", body)).status).toBe(409);
    expect(
      (
        await request("/platform/tenants", platform, "POST", {
          ...body,
          slug: "invalid-owner",
          ownerPassword: "short",
        })
      ).status,
    ).toBe(400);
    const token = await login("initial@example.com", "new-tenant");
    expect((await data(await request("/manage/members", token))).total).toBe(1);
    expect((await data(await request("/platform/tenants?pageSize=1", platform))).total).toBe(3);
    const statusPath = `/platform/tenants/${String(tenant.id)}/status`;
    expect((await request(statusPath, platform, "PATCH", { suspended: true })).status).toBe(200);
    expect((await request("/auth/me", token)).status).toBe(401);
    expect((await request(statusPath, platform, "PATCH", { suspended: false })).status).toBe(200);
    expect((await request("/auth/me", token)).status).toBe(401);
    expect(
      (await request("/platform/tenants/bad/status", platform, "PATCH", { suspended: true }))
        .status,
    ).toBe(400);
    const audit = JSON.stringify(await data(await request("/platform/audit", platform)));
    expect(audit).toContain("tenant.provisioned");
    expect(audit).not.toMatch(/ownerPassword|passwordHash|scrypt|explicit-console/);
    expect(await (await request("/platform/usage", platform)).json()).toEqual([]);
    const health = await request("/platform/health", platform);
    expect([200, 503]).toContain(health.status);
    expect((await data(health)).publish).toMatchObject({ open: false });
    const secondSession = await platformLogin();
    const logout = await request("/auth/logout", platform, "POST");
    expect(await data(logout)).toEqual({ ok: true });
    expect(logout.headers.getSetCookie()[0]).toContain("heron_session=;");
    expect(logout.headers.getSetCookie()[0]).toContain("Path=/;");
    expectLegacyCleared(logout);
    for (const cookie of [platform, secondSession]) {
      expect((await request("/auth/me", cookie)).status).toBe(401);
      expect((await request("/platform/tenants", cookie)).status).toBe(401);
    }
    expect((await request("/auth/me", owner)).status).toBe(200);
  });

  it.each([
    [true, true, true],
    [true, false, true],
    [false, true, true],
    [false, false, true],
    [true, true, false],
    [true, false, false],
    [false, true, false],
    [false, false, false],
  ])(
    "platform health isolates proto diagnostics (engine=%s, template=%s, web=%s)",
    async (engineOk, templateOk, webOk) => {
      const operator = await platformLogin();
      const proto = {
        engine: {
          ok: engineOk,
          detail: engineOk ? "zee-rp p9-test 已就位" : "引擎缺失：/private/zee-rp",
          version: engineOk ? "p9-test" : null,
        },
        template: {
          ok: templateOk,
          detail: templateOk ? "RP9 已就位：/private/blank.rp" : "模板缺失：/private/blank.rp",
        },
      };
      const engine = vi.spyOn(protoTools, "probeEngine").mockReturnValue(proto.engine);
      const template = vi.spyOn(protoTools, "probeTemplate").mockReturnValue(proto.template);
      vi.mocked(existsSync).mockReturnValue(webOk);
      try {
        const response = await request("/platform/health", operator);
        expect(response.status).toBe(webOk ? 200 : 503);
        expect(await data(response)).toMatchObject({
          ok: webOk,
          db: { ok: true },
          webDist: { ok: webOk },
          publish: { open: false },
          proto,
        });
        const publicResponse = await request("/health");
        expect(publicResponse.status).toBe(webOk ? 200 : 503);
        const publicReport = await data(publicResponse);
        expect(publicReport.ok).toBe(webOk);
        expect(publicReport).not.toHaveProperty("proto");
        expect(JSON.stringify(publicReport)).not.toContain("/private/");
        for (const cookie of ["", owner]) {
          const denied = await request("/platform/health", cookie);
          expect(denied.status).toBe(401);
          expect(await data(denied)).not.toHaveProperty("proto");
        }
      } finally {
        engine.mockRestore();
        template.mockRestore();
        vi.mocked(existsSync).mockReset();
      }
    },
  );

  it("never returns internal exceptions or credential/SQL fragments", async () => {
    const res = await request("/test-only-error", owner);
    expect(res.status).toBe(500);
    expect(await data(res)).toEqual({ statusCode: 500, message: "内部操作失败，请稍后重试" });
  });

  it("counts the whole tenant rather than the visible page and keeps recent tasks bounded", async () => {
    await withTenant(db, tenantId, async (t) => {
      const at = new Date().toISOString();
      for (let i = 0; i < 25; i++) {
        const candidateId = await createCandidate(t, {
          title: `演示 ${i}`,
          summary: "fixture",
          heat: i,
        });
        await t.db.insert(tasks).values({
          id: snowflakeId(),
          tenantId,
          candidateId,
          capability: "demo",
          stage: i < 10 ? "review" : i < 20 ? "failed" : "published",
          createTime: at,
          updateTime: at,
        });
      }
    });
    const result = await data(await request("/manage/overview", viewer));
    expect(result).toMatchObject({
      candidates: 25,
      tasks: 25,
      review: 10,
      failed: 10,
      published: 5,
    });
    expect(result.recentTasks).toHaveLength(5);
    const page = await data(await request("/manage/tasks?page=2&pageSize=3", viewer));
    expect(page).toMatchObject({ total: 25, page: 2, pageSize: 3 });
    expect(page.items).toHaveLength(3);
    expect((await data(await request("/manage/candidates?q=演示%2024", viewer))).total).toBe(1);
  });

  it("revokes role/disable sessions permanently and rechecks disabled platform operators", async () => {
    const body = {
      email: "revoke@example.com",
      displayName: "Revoke",
      password: PASSWORD,
      role: "viewer",
    };
    const member = await data(await request("/manage/members", owner, "POST", body));
    const memberId = String(member.id);
    const before = await login(body.email);
    expect(
      (await request(`/manage/members/${memberId}`, owner, "PATCH", { role: "admin" })).status,
    ).toBe(200);
    expect((await request("/auth/me", before)).status).toBe(401);
    const changed = await login(body.email);
    expect(
      (await request(`/manage/members/${memberId}`, owner, "PATCH", { disabled: true })).status,
    ).toBe(200);
    expect((await request("/auth/me", changed)).status).toBe(401);
    expect(
      (await request(`/manage/members/${memberId}`, owner, "PATCH", { disabled: false })).status,
    ).toBe(200);
    expect((await request("/auth/me", changed)).status).toBe(401);

    const opCookie = await platformLogin();
    const token = opCookie.slice(opCookie.indexOf("=") + 1);
    const jwt = new JwtService({ secret: SECRET });
    const payload = jwt.decode<{ sub: string; ver: number }>(token);
    for (const bad of [
      { sub: payload.sub, aud: "heron-platform" },
      { sub: payload.sub, ver: payload.ver, aud: "heron-tenant" },
      { sub: payload.sub, ver: payload.ver + 1, aud: "heron-platform" },
    ]) {
      const cookie = `heron_session=${await jwt.signAsync(bad)}`;
      expect((await request("/auth/me", cookie)).status).toBe(401);
      expect((await request("/platform/tenants", cookie)).status).toBe(401);
    }
    // Only the isolated fixture operators are disabled, never a live database.
    await db.update(platformOperators).set({ disabledAt: new Date().toISOString() });
    expect((await request("/auth/me", opCookie)).status).toBe(401);
    for (const path of ["tenants", "usage", "audit", "health"])
      expect((await request(`/platform/${path}`, opCookie)).status).toBe(401);
    const disabledLogin = await request("/auth/login", "", "POST", {
      identifier: "platform",
      password: PASSWORD,
    });
    expect(disabledLogin.status).toBe(401);
    expect((await data(disabledLogin)).message).toBe("账号或口令不正确");
  });
});
