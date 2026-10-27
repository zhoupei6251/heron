import { randomUUID } from "node:crypto";

import {
  DEFAULT_TENANT_ID,
  type Db,
  createUser,
  createTenant,
  disableUser,
  migrate,
  openDb,
  setTenantSuspended,
  withTenant,
  findTenantBySlug,
  createPlatformOperator,
  changeTenantStatus,
  getUserById,
  changePassword,
  updateMember,
} from "@heron/db/pg";
import { hashPassword } from "@heron/domain";
import { JwtService } from "@nestjs/jwt";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AuthService } from "./auth.service";

// Keep real database operations; intercept only the two transaction boundaries under test.
vi.mock("@heron/db/pg", async (importOriginal) => {
  const actual = await importOriginal<{
    findTenantBySlug: typeof findTenantBySlug;
    withTenant: typeof withTenant;
  }>();
  return {
    ...actual,
    findTenantBySlug: vi.fn(actual.findTenantBySlug),
    withTenant: vi.fn(actual.withTenant),
  };
});

/**
 * 只测判据，不发 HTTP：登录的对错、载荷的形状、租户边界，全在 service 一层。
 * 传输（cookie 属性、守卫）在 auth.controller.test.ts 与 jwt.strategy 的构造参数里，
 * 真 socket 上的一次往返要等 Postgres 起来（计划 T11），不在这里假称验过。
 *
 * 「用户不存在」与「口令错」返回同一个 null 是刻意的：泄露「这个邮箱有没有账号」就是送给
 * 撞库脚本的免费情报。界面文案统一写「邮箱或口令不正确」，这条不许在 review 时被当成「错误信息不清楚」改掉。
 */

const AT = "2026-10-22T00:00:00.000Z";
const TENANT_B = "00000000-0000-4000-8000-000000000002";
const SLUG_A = "Acme";
const SLUG_B = "beta";
const EMAIL = "zhoupei@example.com";
const PASSWORD = "correct-horse-battery";
// 32 字符：连测试密钥也得过 jwtSecret() 那条长度线，否则「最小长度」只是个注释
const TEST_SECRET = "unit-test-secret-not-a-real-key!";

describe("AuthService.login", () => {
  let db: Db;
  let service: AuthService;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    // slug 故意给成大写：规范化是仓储与 CHECK 的事，调用方不许为了大小写先猜一次
    await createTenant(db, { id: DEFAULT_TENANT_ID, name: "A 台", slug: SLUG_A });
    await createTenant(db, { id: TENANT_B, name: "B 台", slug: SLUG_B });
    // JwtService 直接 new：脱离 Nest 容器测判据，密钥只活在这条测试里
    service = new AuthService(
      db,
      new JwtService({ secret: TEST_SECRET, signOptions: { expiresIn: "12h" } }),
    );
  });

  afterEach(async () => {
    vi.restoreAllMocks();
    await db.close();
  });

  /** 在指定租户里建一个人，返回其 id。登录查的就是这条行。 */
  async function seedUser(
    tenantId: string,
    input: { email?: string; password?: string; disabledAt?: string | null } = {},
  ): Promise<string> {
    return withTenant(db, tenantId, (t) =>
      createUser(t, {
        email: input.email ?? EMAIL,
        displayName: "周佩",
        passwordHash: hashPassword(input.password ?? PASSWORD),
        role: "reviewer",
      }),
    );
  }

  it("口令错与用户不存在给出同一个答案", async () => {
    await seedUser(DEFAULT_TENANT_ID);
    expect(await service.login(SLUG_A, EMAIL, PASSWORD)).toBeTruthy();
    expect(await service.login(SLUG_A, EMAIL, "wrong-horse-battery")).toBeNull();
    expect(await service.login(SLUG_A, "nobody@example.com", PASSWORD)).toBeNull();
  });

  it("同一个邮箱在别的租户是另一个人：拿 A 的口令登不进 B", async () => {
    await seedUser(DEFAULT_TENANT_ID);
    expect(await service.login(SLUG_B, EMAIL, PASSWORD)).toBeNull();
    // B 里真的有同名邮箱的人时，也要用 B 自己的口令——两条租户的口令互不相干
    await seedUser(TENANT_B, { password: "another-tenant-secret" });
    expect(await service.login(SLUG_B, EMAIL, PASSWORD)).toBeNull();
    expect(await service.login(SLUG_B, EMAIL, "another-tenant-secret")).toBeTruthy();
  });

  it("停用的账号即使口令对也不给 token", async () => {
    const userId = await seedUser(DEFAULT_TENANT_ID);
    // 停用走仓储：T7 才有成员管理接口，这里只需要「disabled_at 非空」这个前置状态
    await withTenant(db, DEFAULT_TENANT_ID, (t) => disableUser(t, userId));
    expect(await service.login(SLUG_A, EMAIL, PASSWORD)).toBeNull();
  });

  it("停租的租户整个关在门外（suspended_at 是软关闭，数据不许跟着删）", async () => {
    await seedUser(DEFAULT_TENANT_ID);
    await setTenantSuspended(db, DEFAULT_TENANT_ID, AT);
    expect(await service.login(SLUG_A, EMAIL, PASSWORD)).toBeNull();
  });

  it("rejects a stale active tenant preread and cannot revive its login after resume", async () => {
    await seedUser(DEFAULT_TENANT_ID);
    const operator = await createPlatformOperator(db, {
      username: "platform",
      displayName: "Platform",
      passwordHash: "unusable:test",
    });
    const before = await service.login(SLUG_A, EMAIL, PASSWORD);
    const find = vi.mocked(findTenantBySlug).getMockImplementation()!;
    vi.mocked(findTenantBySlug).mockImplementationOnce(async (...args) => {
      const stale = await find(...args);
      await changeTenantStatus(db, operator.id, DEFAULT_TENANT_ID, true);
      return stale;
    });
    const during = await service.login(SLUG_A, EMAIL, PASSWORD);
    await changeTenantStatus(db, operator.id, DEFAULT_TENANT_ID, false);
    const jwt = new JwtService({ secret: TEST_SECRET });
    expect(await service.userFor(jwt.decode((during ?? before)!.token))).toBeNull();
    expect(during).toBeNull();
    expect(await service.login(SLUG_A, EMAIL, PASSWORD)).not.toBeNull();
  });

  it.each(["password", "disable", "role"] as const)(
    "serializes login signing with concurrent %s invalidation",
    async (mutation) => {
      const userId = await seedUser(DEFAULT_TENANT_ID);
      const ownerId = await withTenant(db, DEFAULT_TENANT_ID, (t) =>
        createUser(t, {
          email: "owner@example.com",
          displayName: "Owner",
          role: "owner",
          passwordHash: "unusable:test",
        }),
      );
      const jwt = new JwtService({ secret: TEST_SECRET });
      service = new AuthService(db, jwt);
      let signalEntered!: () => void;
      let releaseSigning!: () => void;
      const entered = new Promise<void>((resolve) => {
        signalEntered = resolve;
      });
      const release = new Promise<void>((resolve) => {
        releaseSigning = resolve;
      });
      const order: string[] = [];
      const transact = vi.mocked(withTenant).getMockImplementation()!;
      vi.mocked(withTenant).mockImplementationOnce(async (...args) => {
        const result = await transact(...args);
        order.push("login-committed");
        return result;
      });
      const sign = jwt.signAsync.bind(jwt);
      vi.spyOn(jwt, "signAsync").mockImplementationOnce(async (payload, options) => {
        signalEntered();
        await release;
        order.push("signed");
        return sign(payload, options);
      });
      const login = service.login(SLUG_A, EMAIL, PASSWORD);
      await entered;
      const invalidate = withTenant(db, DEFAULT_TENANT_ID, async (t) => {
        if (mutation === "password") {
          const user = await getUserById(t, userId);
          await changePassword(t, userId, user!.passwordHash, hashPassword("replacement-password"));
        } else {
          await updateMember(
            t,
            ownerId,
            userId,
            mutation === "disable" ? { disabled: true } : { role: "viewer" },
          );
        }
        order.push("invalidated");
      });
      releaseSigning();
      const signed = await login;
      await invalidate;
      if (mutation === "disable")
        await withTenant(db, DEFAULT_TENANT_ID, (t) =>
          updateMember(t, ownerId, userId, { disabled: false }),
        );
      expect(order).toEqual(["signed", "login-committed", "invalidated"]);
      expect(await service.userFor(jwt.decode(signed!.token))).toBeNull();
    },
  );

  it("未知的工作空间没有账号可言", async () => {
    await seedUser(DEFAULT_TENANT_ID);
    expect(await service.login("gamma", EMAIL, PASSWORD)).toBeNull();
  });

  it("工作空间名大小写不敏感：客户端不许为了大小写再猜一次", async () => {
    await seedUser(DEFAULT_TENANT_ID);
    expect(await service.login("ACME", EMAIL, PASSWORD)).toBeTruthy();
    expect(await service.login(" acme ", EMAIL, PASSWORD)).toBeTruthy();
  });

  it("载荷带必需版本与租户 audience，但不带邮箱或凭据", async () => {
    const userId = await seedUser(DEFAULT_TENANT_ID);
    const signed = await service.login(SLUG_A, EMAIL, PASSWORD);
    expect(signed).not.toBeNull();
    const payload = new JwtService({ secret: TEST_SECRET }).decode<Record<string, unknown>>(
      signed?.token as string,
    );
    expect(Object.keys(payload).sort()).toEqual(["aud", "exp", "iat", "role", "sub", "tid", "ver"]);
    expect(payload).toMatchObject({
      sub: userId,
      tid: DEFAULT_TENANT_ID,
      role: "reviewer",
      ver: 0,
      aud: "heron-tenant",
    });
    // 邮箱与口令哈希不进 token：它们是会在库里改的字段，改一次就得重签一遍是白付的债
    expect(JSON.stringify(payload)).not.toContain("example.com");
    expect(JSON.stringify(payload)).not.toContain("scrypt");
    // 顺带还给界面的是这张表，不是库里那一行——password_hash 一步都不许往外走
    expect(signed?.user).toEqual({
      id: userId,
      tenantId: DEFAULT_TENANT_ID,
      tenantName: "A 台",
      tenantSlug: "acme",
      email: EMAIL,
      displayName: "周佩",
      role: "reviewer",
    });
  });

  describe("userFor（守卫每次请求的库内复查）", () => {
    it("token 说你有身份，库说你还有没有", async () => {
      const userId = await seedUser(DEFAULT_TENANT_ID);
      const payload = { sub: userId, tid: DEFAULT_TENANT_ID, role: "reviewer" as const, ver: 0 };
      expect(await service.userFor(payload)).toEqual({
        id: userId,
        tenantId: DEFAULT_TENANT_ID,
        tenantName: "A 台",
        tenantSlug: "acme",
        email: EMAIL,
        displayName: "周佩",
        role: "reviewer",
      });

      await withTenant(db, DEFAULT_TENANT_ID, (t) => disableUser(t, userId));
      expect(await service.userFor(payload)).toBeNull();
    });

    it("别的租户的 user id 配不上这个 tid：载荷里的租户不是可信来源", async () => {
      const userId = await seedUser(DEFAULT_TENANT_ID);
      expect(
        await service.userFor({ sub: userId, tid: TENANT_B, role: "reviewer", ver: 0 }),
      ).toBeNull();
    });

    it("库里已经没有这个人也返回 null（token 不会随着删号而失效，靠这次复查兜住）", async () => {
      expect(
        await service.userFor({
          sub: randomUUID(),
          tid: DEFAULT_TENANT_ID,
          role: "viewer",
          ver: 0,
        }),
      ).toBeNull();
    });

    it("登进来之后才停租：已签发的会话立刻失效，不然「关在门外」只管进门", async () => {
      const userId = await seedUser(DEFAULT_TENANT_ID);
      const payload = { sub: userId, tid: DEFAULT_TENANT_ID, role: "reviewer" as const, ver: 0 };
      expect(await service.userFor(payload)).not.toBeNull();

      await setTenantSuspended(db, DEFAULT_TENANT_ID, AT);
      expect(await service.userFor(payload)).toBeNull();
    });
  });
});
