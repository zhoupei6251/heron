import { Controller, Get, type INestApplication } from "@nestjs/common";
import { JwtService } from "@nestjs/jwt";
import { Test } from "@nestjs/testing";

import {
  type Db,
  createTenant,
  createUser,
  disableUser,
  migrate,
  openDb,
  withTenant,
} from "@heron/db/pg";
import { hashPassword } from "@heron/domain";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { AppModule } from "../app.module";
import { Public } from "../common/decorators/public.decorator";
import { RequireAction } from "../common/decorators/require-action.decorator";
import { SESSION_COOKIE } from "../config/runtime";
import { PG_DB } from "../db/pg";
import { applyTransport } from "../transport";

/**
 * 真 socket 上的一往返：上面那些单元测试各自看自己的判据，这一份看**接线**。
 * 守卫顺序、@Public 真的公开、Set-Cookie 真的带那几个属性、401/403 分得对——
 * 这些都不是任何单个函数的性质，而是它们串起来之后的性质，抄一份假的控制器测不出来。
 *
 * 库用 pglite（本案所有非 T11 测试的口径）：它跑的是同一份生成迁移与同一套 RLS 策略，
 * 但它是单连接、内存态，测不出并发跨租户竞态——那条归 T11 的真 Postgres。
 * 之所以能不改 DATABASE_URL 就起来：PG_DB 这个 provider 被整个替换掉了，
 * 生产路径「没配 DATABASE_URL 就拒绝启动」由 factory 自己守。
 */

const SECRET = "e2e-secret-not-a-real-key-at-all-32chars";
const SLUG = "Acme";
const PASSWORD = "correct-horse-battery";
const REVIEWER = "reviewer@example.com";
const VIEWER = "viewer@example.com";
const DISABLER = "disabler@example.com";

/** 受保护路由的替身：T6 时业务接口还全在 SQLite 上，这里要验的是闸，不是业务。 */
@Controller("probe")
class ProbeController {
  @Get("review")
  @RequireAction("review.confirm")
  review(): { ok: boolean } {
    return { ok: true };
  }

  @Get("anonymous")
  @Public()
  anonymous(): string {
    return "公开";
  }
}

async function json(res: Response): Promise<Record<string, unknown>> {
  return (await res.json()) as Record<string, unknown>;
}

describe("认证传输（真 socket）", () => {
  let db: Db;
  let app: INestApplication;
  let base: string;
  let tenantId: string;
  /** email → 库里那行的 id：被停用那条测试要按 id 停用，而 id 只在建人的时候拿得到。 */
  const userIds = new Map<string, string>();
  const originalSecret = process.env.HERON_JWT_SECRET;

  async function login(email: string, password = PASSWORD): Promise<string | null> {
    const res = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ tenant: SLUG, identifier: email, password }),
    });
    if (!res.ok) return null;
    const raw = res.headers.getSetCookie()[0] ?? "";
    const pair = raw.split(";")[0] ?? "";
    return pair.startsWith(`${SESSION_COOKIE}=`) ? pair.slice(SESSION_COOKIE.length + 1) : null;
  }

  function get(path: string, token?: string): Promise<Response> {
    return fetch(`${base}/api${path}`, {
      headers: token ? { cookie: `${SESSION_COOKIE}=${token}` } : {},
    });
  }

  beforeAll(async () => {
    process.env.HERON_JWT_SECRET = SECRET;
    delete process.env.HERON_COOKIE_SECURE;

    db = openDb("memory");
    await migrate(db);
    tenantId = await createTenant(db, { name: "A 台", slug: SLUG });
    for (const [email, role] of [
      [REVIEWER, "reviewer"],
      [VIEWER, "viewer"],
      [DISABLER, "reviewer"],
    ] as const) {
      const id = await withTenant(db, tenantId, (t) =>
        createUser(t, {
          email,
          displayName: email,
          passwordHash: hashPassword(PASSWORD),
          role,
        }),
      );
      userIds.set(email, id);
    }

    const moduleRef = await Test.createTestingModule({
      imports: [AppModule],
      controllers: [ProbeController],
    })
      .overrideProvider(PG_DB)
      .useValue(db)
      .compile();
    app = moduleRef.createNestApplication({ logger: false });
    applyTransport(app);
    // 让 Nest 自己起监听：这既是生产代码走的那条（main.ts 也调 app.listen），
    // 也让传输层的挂载顺序在本机上被真的跑一遍，而不是我手工拼一个 server。
    await app.listen(0, "127.0.0.1");
    base = await app.getUrl();
  });

  afterAll(async () => {
    if (originalSecret === undefined) delete process.env.HERON_JWT_SECRET;
    else process.env.HERON_JWT_SECRET = originalSecret;
    // 这里不单独 db.close()：PG_DB 被换成了本案的 pglite，app.close() 走 PgConnection 的
    // 销毁钩子把它关掉——顺带证明了「容器关闭即释放连接」这条在真进程里成立。
    await app.close();
  });

  it("登录成功：会话只在 Set-Cookie 里，响应体只有身份", async () => {
    const res = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ tenant: SLUG, identifier: REVIEWER, password: PASSWORD }),
    });
    expect(res.status).toBe(201);
    const body = await json(res);
    expect(Object.keys(body).sort()).toEqual(["kind", "menus", "permissions", "roles", "user"]);
    expect(body).toMatchObject({
      kind: "tenant",
      roles: ["reviewer"],
      permissions: ["task.claim", "review.confirm", "usage.read"],
    });
    const user = body.user as Record<string, unknown>;
    // 身份仍然是安全 DTO；会话 token、口令哈希和版本都不进响应体。
    // sessionId 是「这次登录」的句柄，不是凭据：拿它换不到访问权，只用来在会话列表里认自己这一条。
    expect(Object.keys(user).sort()).toEqual([
      "displayName",
      "email",
      "id",
      "role",
      "sessionId",
      "tenantId",
      "tenantName",
      "tenantSlug",
    ]);
    expect(user).toMatchObject({
      email: REVIEWER,
      displayName: REVIEWER,
      role: "reviewer",
      tenantName: "A 台",
      tenantSlug: SLUG.toLowerCase(),
    });
    expect(typeof user.id).toBe("string");
    expect(typeof user.tenantId).toBe("string");

    const cookie = res.headers.getSetCookie()[0] ?? "";
    expect(cookie).toContain(`${SESSION_COOKIE}=`);
    expect(cookie).toContain("HttpOnly");
    expect(cookie).toContain("SameSite=Lax");
    expect(cookie).toContain("Path=/");
    // 会话 cookie：没有 Max-Age / Expires，关浏览器即登出
    expect(cookie).not.toMatch(/Max-Age|Expires/);
    // 本机 http 上不带 Secure，否则谁都存不下这个 cookie
    expect(cookie).not.toContain("Secure");
  });

  it("口令错：401 + 统一文案，且一个 cookie 都不下发", async () => {
    const res = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ tenant: SLUG, identifier: REVIEWER, password: "wrong-horse-battery" }),
    });
    expect(res.status).toBe(401);
    expect(await json(res)).toMatchObject({ message: "账号或口令不正确" });
    expect(res.headers.getSetCookie()).toEqual([]);
  });

  it("同一邮箱连续失败到阈值：此后 429，不再验口令；别的邮箱不受牵连", async () => {
    const attempt = (email: string) =>
      fetch(`${base}/api/auth/login`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ tenant: SLUG, identifier: email, password: "wrong-horse-battery" }),
      });
    for (let i = 0; i < 5; i += 1) {
      expect((await attempt("throttle@example.com")).status).toBe(401);
    }
    const blocked = await attempt("throttle@example.com");
    expect(blocked.status).toBe(429);
    expect(String((await json(blocked)).message)).toContain("15 分钟");
    expect((await attempt("not-throttled@example.com")).status).toBe(401);
  });

  it("没有会话：/auth/me 与既有的 /tasks 都是 401，只有 @Public 的进得来", async () => {
    expect((await get("/auth/me")).status).toBe(401);
    // 全局守卫挡住业务路由时压根没进 handler，所以这一步也不会去碰 SQLite
    expect((await get("/tasks")).status).toBe(401);
    expect((await get("/probe/anonymous")).status).toBe(200);
  });

  it("带会话：/auth/me 给回库里那张身份表", async () => {
    const token = await login(REVIEWER);
    expect(token).not.toBeNull();
    const res = await get("/auth/me", token as string);
    expect(res.status).toBe(200);
    expect(await json(res)).toMatchObject({
      kind: "tenant",
      user: { email: REVIEWER, role: "reviewer" },
      roles: ["reviewer"],
      permissions: ["task.claim", "review.confirm", "usage.read"],
    });
  });

  it("权限表真的在挡：viewer 403，reviewer 200", async () => {
    const viewer = await login(VIEWER);
    const reviewer = await login(REVIEWER);
    expect((await get("/probe/review", viewer as string)).status).toBe(403);
    expect((await get("/probe/review", reviewer as string)).status).toBe(200);
  });

  it("签名不对的 token 进不来：会话的正确性只由那把密钥决定", async () => {
    const forged = await new JwtService({
      secret: "some-other-secret-value-32-chars-long!!",
    }).signAsync({
      sub: "11111111-1111-4111-8111-111111111111",
      tid: "00000000-0000-4000-8000-000000000001",
      role: "owner",
      ver: 0,
      aud: "heron-tenant",
    });
    expect((await get("/auth/me", forged)).status).toBe(401);
  });

  it("登进来之后被停用：原来那张票立刻 401（守卫每次回库复查，不是只在登录时查一次）", async () => {
    const token = await login(DISABLER);
    expect((await get("/auth/me", token as string)).status).toBe(200);

    await withTenant(db, tenantId, (t) => disableUser(t, userIds.get(DISABLER) as string));

    expect((await get("/auth/me", token as string)).status).toBe(401);
    // 同一张票上的其它路由也一起关门：挡的是身份，不是那一个接口
    expect((await get("/probe/review", token as string)).status).toBe(401);
  });

  it("安全头在真响应上：CSP 只 report-only，nosniff / DENY / same-origin 都带着", async () => {
    const res = await get("/probe/anonymous");
    // 先钉住它真的进了 handler：404 的响应也带这些头，只看头会测出一个假通过
    expect(res.status).toBe(200);
    expect(res.headers.get("x-content-type-options")).toBe("nosniff");
    expect(res.headers.get("x-frame-options")).toBe("DENY");
    expect(res.headers.get("referrer-policy")).toBe("same-origin");
    expect(res.headers.get("content-security-policy-report-only")).toContain("default-src 'self'");
    // 摘掉 reportOnly 之前， enforcing 的 CSP 一条都不该发出去
    expect(res.headers.get("content-security-policy")).toBeNull();
    expect(res.headers.get("x-powered-by")).toBeNull();
  });
});
