import { snowflakeId } from "../id";
import { and, eq, sql } from "drizzle-orm";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import * as repo from "./index";
import type { Db, TenantDb } from "./index";

const HASH = "sensitive-password-hash-never-audit";

describe("登录会话（真实 PGlite）", () => {
  let db: Db;
  let tenantId: string;
  let owner: string;
  let viewer: string;
  const inTenant = <T>(fn: (tdb: TenantDb) => Promise<T>) => repo.withTenant(db, tenantId, fn);

  beforeEach(async () => {
    db = repo.openDb("memory");
    await repo.migrate(db);
    tenantId = await repo.createTenant(db, { name: "Alpha", slug: "alpha" });
    owner = await inTenant((t) =>
      repo.createUser(t, {
        email: "owner@a.com",
        displayName: "Owner",
        passwordHash: HASH,
        role: "owner",
      }),
    );
    viewer = await inTenant((t) =>
      repo.createUser(t, {
        email: "viewer@a.com",
        displayName: "Viewer",
        passwordHash: HASH,
        role: "viewer",
      }),
    );
  });
  afterEach(async () => {
    await db.close();
  });

  /** 一条会话 + 它自己的 id，和登录处生成的形状一样。 */
  async function signIn(userId: string, userAgent: string | null = null): Promise<string> {
    const id = snowflakeId();
    await inTenant((t) => repo.createSession(t, { id, userId, userAgent }));
    return id;
  }

  async function sessionAudits() {
    return inTenant((t) =>
      t.db
        .select({ action: repo.auditLogs.action, targetId: repo.auditLogs.targetId })
        .from(repo.auditLogs)
        .where(eq(repo.auditLogs.action, "session.revoked"))
        // 顺序按 id 走：id 是单调的雪花，写入顺序就是 id 顺序。
        // 不写 ORDER BY 的话这断言靠的是堆顺序——改一次列类型就可能换一批行序。
        .orderBy(repo.auditLogs.id),
    );
  }

  it("登录写一行，findLiveSession 只认本人且未撤销的那条", async () => {
    const id = await signIn(owner, "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0");
    expect(await inTenant((t) => repo.findLiveSession(t, { id, userId: owner }))).toMatchObject({
      id,
    });
    // 拿别人的 userId 换这条 sid 必须是空：sid 与人对不上就等于没登录
    expect(await inTenant((t) => repo.findLiveSession(t, { id, userId: viewer }))).toBeNull();
    expect(
      await inTenant((t) => repo.findLiveSession(t, { id: snowflakeId(), userId: owner })),
    ).toBeNull();
    // 撤销之后同一次读立刻空：单条强退靠的就是这次读，不是票的有效期
    await inTenant((t) => repo.revokeSession(t, owner, id));
    expect(await inTenant((t) => repo.findLiveSession(t, { id, userId: owner }))).toBeNull();
  });

  it("last_seen_at 一分钟才写一次，写的是同一条", async () => {
    const id = await signIn(owner);
    const live = (await inTenant((t) => repo.findLiveSession(t, { id, userId: owner })))!;
    const sameMoment = await inTenant((t) =>
      repo.keepSessionAlive(t, live, Date.parse(live.lastSeenAt)),
    );
    expect(sameMoment).toBe(false);
    const later = Date.parse(live.lastSeenAt) + 61_000;
    expect(await inTenant((t) => repo.keepSessionAlive(t, live, later))).toBe(true);
    expect(
      (await inTenant((t) => repo.findLiveSession(t, { id, userId: owner })))?.lastSeenAt,
    ).toBe(new Date(later).toISOString());
  });

  it("成员只看得到自己的；有 session.manage 的人看得到全空间，current 由服务端标", async () => {
    const mine = await signIn(viewer);
    const owners = await signIn(owner);
    const self = await inTenant((t) =>
      repo.listSessions(t, { id: viewer, canManage: false }, {}, mine),
    );
    expect(self.total).toBe(1);
    expect(self.items[0]).toMatchObject({
      id: mine,
      userName: "Viewer",
      self: true,
      current: true,
    });

    const all = await inTenant((t) =>
      repo.listSessions(t, { id: owner, canManage: true }, {}, owners),
    );
    expect(all.total).toBe(2);
    expect(all.items.map((row) => row.id).sort()).toEqual([mine, owners].sort());
    expect(all.items.find((row) => row.id === owners)).toMatchObject({ current: true, self: true });
    expect(all.items.find((row) => row.id === mine)).toMatchObject({ current: false, self: false });
    // 搜索按成员姓名/邮箱走，搜别人的名字不会给成员多看见一条
    expect(
      (
        await inTenant((t) =>
          repo.listSessions(t, { id: viewer, canManage: false }, { q: "owner" }),
        )
      ).total,
    ).toBe(0);
    expect(
      (await inTenant((t) => repo.listSessions(t, { id: owner, canManage: true }, { q: "owner" })))
        .total,
    ).toBe(1);
  });

  it("自己那条谁都能退；别人的要 session.manage；重复退不写第二条审计", async () => {
    const mine = await signIn(viewer);
    const owners = await signIn(owner);
    expect(await inTenant((t) => repo.revokeSession(t, viewer, mine))).toMatchObject({
      self: true,
    });
    await expect(inTenant((t) => repo.revokeSession(t, viewer, owners))).rejects.toMatchObject({
      code: "forbidden",
    });
    expect(await inTenant((t) => repo.revokeSession(t, owner, owners))).toMatchObject({
      self: true,
    });
    // owner 退掉 viewer 的另一条会话：这一次是「强退别人」
    const again = await signIn(viewer);
    expect(await inTenant((t) => repo.revokeSession(t, owner, again))).toMatchObject({
      self: false,
      userId: viewer,
    });
    // 已经退过的再点一次：不报错，也不在审计里再长一行
    expect(await inTenant((t) => repo.revokeSession(t, owner, again))).toMatchObject({
      userId: viewer,
    });
    const audits = await sessionAudits();
    expect(audits.map((row) => row.targetId)).toEqual([mine, owners, again]);
    await expect(
      inTenant((t) => repo.revokeSession(t, owner, snowflakeId())),
    ).rejects.toMatchObject({ code: "not_found" });
  });

  it("整户撤销（改密码 / 一键踢全部 / 停用成员）把会话行一起标掉", async () => {
    const first = await signIn(viewer);
    const second = await signIn(viewer);
    await inTenant((t) => repo.revokeUserSessions(t, viewer));
    const page = await inTenant((t) => repo.listSessions(t, { id: owner, canManage: true }));
    expect(page.items.map((row) => row.id)).toEqual([]);
    // 行还在（审计里的 id 要对得上），只是不在「在线」里了
    const rows = await inTenant((t) =>
      t.db
        .select({ id: repo.sessions.id })
        .from(repo.sessions)
        .where(and(eq(repo.sessions.userId, viewer), sql`${repo.sessions.revokedAt} is not null`)),
    );
    expect(rows.map((row) => row.id).sort()).toEqual([first, second].sort());
  });

  it("会话表受 RLS 约束：别的租户读不到也写不进", async () => {
    const id = await signIn(owner);
    const other = await repo.createTenant(db, { name: "Beta", slug: "beta" });
    const seen = await repo.withTenant(db, other, (t) =>
      t.db.select({ id: repo.sessions.id }).from(repo.sessions),
    );
    expect(seen).toEqual([]);
    expect(
      await repo.withTenant(db, other, (t) => repo.findLiveSession(t, { id, userId: owner })),
    ).toBeNull();
    // 也踢不动：别的租户里这条 sid 不存在，撤销只能退成 not_found
    await expect(
      repo.withTenant(db, other, (t) => repo.revokeSession(t, owner, id)),
    ).rejects.toMatchObject({ code: "not_found" });
  });
});
