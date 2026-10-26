import { Controller, Get, type INestApplication } from "@nestjs/common";
import { JwtService } from "@nestjs/jwt";
import { Test } from "@nestjs/testing";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  type Db,
  openDb,
  migrate,
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
import { randomUUID } from "node:crypto";
import { hashPassword } from "@heron/domain";
import { AppModule } from "./app.module";
import { PG_DB } from "./db/pg";
import { applyTransport } from "./transport";

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
  const originalSecret = process.env.HERON_JWT_SECRET;

  const request = (path: string, cookie = "", method = "GET", body?: unknown, headers = {}) =>
    fetch(`${base}/api${path}`, {
      method,
      headers: { cookie, "content-type": "application/json", ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  const data = async (res: Response) => (await res.json()) as Record<string, unknown>;
  async function login(email: string, tenant = "alpha", password = PASSWORD) {
    const res = await request("/auth/login", "", "POST", { tenant, email, password });
    expect(res.status).toBe(201);
    return res.headers.getSetCookie()[0]!.split(";")[0]!;
  }
  async function platformLogin() {
    const res = await request("/platform/auth/login", "", "POST", {
      email: "platform@example.com",
      password: PASSWORD,
    });
    expect(res.status).toBe(201);
    const body = await data(res);
    expect(Object.keys(body).sort()).toEqual(["displayName", "email", "id"]);
    expect(res.headers.getSetCookie()[0]).toContain("Path=/api/platform");
    expect(res.headers.getSetCookie()[0]).toContain("HttpOnly");
    return res.headers.getSetCookie()[0]!.split(";")[0]!;
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
        passwordHash: hashPassword(PASSWORD),
      });
      foreignFeed = (await createFeedSource(t, foreignUser, feed)).id;
      await createTask(t, { capability: "demo", candidateId: null });
    });
    await createPlatformOperator(db, {
      email: "platform@example.com",
      displayName: "平台",
      passwordHash: hashPassword(PASSWORD),
    });
    const module = await Test.createTestingModule({
      imports: [AppModule],
      controllers: [ErrorProbeController],
    })
      .overrideProvider(PG_DB)
      .useValue(db)
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
    const audit = JSON.stringify(await data(await request("/manage/audit", admin)));
    expect(audit).toContain("retention.updated");
    expect(audit).not.toMatch(/scrypt|passwordHash|explicit-console/);
    expect((await request("/manage/usage", admin)).status).toBe(200);
  });

  it("isolates IDs, rejects malformed input/duplicates and preserves the last owner", async () => {
    expect(
      (await request(`/manage/members/${foreignUser}`, owner, "PATCH", { disabled: true })).status,
    ).toBe(404);
    expect((await request(`/manage/feeds/${foreignFeed}`, admin, "PUT", feed)).status).toBe(404);
    for (const id of ["not-uuid", "1"]) {
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
      "createdAt",
      "disabledAt",
      "displayName",
      "email",
      "id",
      "role",
    ]);
    expect((await request("/manage/members", owner, "POST", body)).status).toBe(409);
  });

  it("updates profile, rejects bad password, then revokes all old sessions on password change/logout", async () => {
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
          email: "new@example.com",
          password: PASSWORD,
        })
      ).status,
    ).toBe(401);
    const fresh = await login("new@example.com", "alpha", "a-new-console-password");
    expect(await data(await request("/auth/logout", fresh, "POST"))).toEqual({ ok: true });
    expect((await request("/auth/me", fresh)).status).toBe(401);
  });

  it("compares full Origins, supports secure TLS termination and never trusts forwarded headers", async () => {
    const body = { tenant: "alpha", email: "owner@example.com", password: PASSWORD };
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

  it("requires independent platform authentication, strict audiences/version, even with cookie swaps", async () => {
    for (const path of ["auth/me", "tenants", "usage", "audit", "health"]) {
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
    expect((await request("/platform/auth/logout", owner, "POST")).status).toBe(401);
    platform = await platformLogin();
    expect(
      (
        await request(
          "/platform/auth/login",
          "",
          "POST",
          { email: "platform@example.com", password: PASSWORD },
          { origin: "https://attacker.example" },
        )
      ).status,
    ).toBe(403);
    expect((await request("/platform/auth/me", platform)).status).toBe(200);
    expect((await request("/auth/me", platform)).status).toBe(401);
    expect((await request("/manage/tasks", platform)).status).toBe(401);
    expect(
      (await request("/auth/me", platform.replace("heron_platform_session=", "heron_session=")))
        .status,
    ).toBe(401);
    expect(
      (
        await request(
          "/platform/auth/me",
          owner.replace("heron_session=", "heron_platform_session="),
        )
      ).status,
    ).toBe(401);
    const jwt = new JwtService({ secret: SECRET });
    for (const payload of [
      { sub: ownerId, tid: tenantId, role: "owner", aud: "heron-tenant" },
      { sub: ownerId, tid: tenantId, role: "owner", ver: 0 },
      { sub: ownerId, tid: tenantId, role: "owner", ver: 0, aud: "heron-platform" },
    ]) {
      expect(
        (await request("/auth/me", `heron_session=${await jwt.signAsync(payload)}`)).status,
      ).toBe(401);
    }
    const invalid = await request("/platform/auth/login", "", "POST", {
      email: "platform@example.com",
      password: "wrong",
    });
    const absent = await request("/platform/auth/login", "", "POST", {
      email: "missing@example.com",
      password: PASSWORD,
    });
    expect(invalid.status).toBe(401);
    expect(await data(invalid)).toEqual(await data(absent));
    expect(
      (
        await request("/platform/auth/login", "", "POST", {
          email: "owner@example.com",
          password: PASSWORD,
        })
      ).status,
    ).toBe(401);
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
    const logout = await request("/platform/auth/logout", platform, "POST");
    expect(await data(logout)).toEqual({ ok: true });
    expect(logout.headers.getSetCookie()[0]).toContain("Path=/api/platform");
    expect((await request("/platform/tenants", platform)).status).toBe(401);
    expect((await request("/auth/me", owner)).status).toBe(200);
  });

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
          id: randomUUID(),
          tenantId,
          candidateId,
          capability: "demo",
          stage: i < 10 ? "review" : i < 20 ? "failed" : "published",
          createdAt: at,
          updatedAt: at,
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
      const cookie = `heron_platform_session=${await jwt.signAsync(bad)}`;
      expect((await request("/platform/auth/me", cookie)).status).toBe(401);
    }
    // This fixture has exactly one operator; update the fixture table, never a live database.
    await db.update(platformOperators).set({ disabledAt: new Date().toISOString() });
    for (const path of ["auth/me", "tenants", "usage", "audit", "health"])
      expect((await request(`/platform/${path}`, opCookie)).status).toBe(401);
    const disabledLogin = await request("/platform/auth/login", "", "POST", {
      email: "platform@example.com",
      password: PASSWORD,
    });
    expect(disabledLogin.status).toBe(401);
    expect((await data(disabledLogin)).message).toBe("邮箱或口令不正确");
  });
});
