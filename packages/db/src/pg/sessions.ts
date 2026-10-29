import { and, asc, count, desc, eq, ilike, isNull, or } from "drizzle-orm";
import { PageQuerySchema, type Page, type PageQuery, type Session } from "@heron/contracts";

import { snowflakeId } from "../id";
import { nowIso } from "../time";
import { ManagementError, readPage, searchPattern } from "./console-query";
import { requireActiveActor } from "./management";
import type { TenantDb } from "./rls";
import { auditLogs, sessions, users } from "./schema/index";

/**
 * last_seen_at 的写入节流。每来一个请求就 UPDATE 一次，一个开着轮询的控制台
 * 就能把这张表写成全场最热的那张；一分钟一次的精度已经够回答「这条还活着吗」。
 */
const TOUCH_INTERVAL_MS = 60_000;

const sessionColumns = {
  id: sessions.id,
  userId: sessions.userId,
  userName: users.displayName,
  userEmail: users.email,
  userRole: users.role,
  createTime: sessions.createTime,
  lastSeenAt: sessions.lastSeenAt,
  userAgent: sessions.userAgent,
};

/** 登录处调用：id 由调用方生成，它同时就是 token 里的 sid。 */
export async function createSession(
  tdb: TenantDb,
  input: { id: string; userId: string; userAgent?: string | null },
): Promise<void> {
  const at = nowIso();
  await tdb.db.insert(sessions).values({
    id: input.id,
    tenantId: tdb.tenantId,
    userId: input.userId,
    createTime: at,
    lastSeenAt: at,
    userAgent: input.userAgent?.slice(0, 400) ?? null,
  });
}

/**
 * 每次请求都走这里：撤销立刻生效靠的是这次读，不是 token 里的 ver。
 * 返回 null 的三种情况（行不存在 / 已撤销 / 不属于这个人）一律当作没登录——
 * 分开回报只会告诉攻击者「这个 sid 存在」。
 */
export async function findLiveSession(
  tdb: TenantDb,
  input: { id: string; userId: string },
): Promise<{ id: string; lastSeenAt: string } | null> {
  const [row] = await tdb.db
    .select({ id: sessions.id, lastSeenAt: sessions.lastSeenAt })
    .from(sessions)
    .where(
      and(
        eq(sessions.tenantId, tdb.tenantId),
        eq(sessions.id, input.id),
        eq(sessions.userId, input.userId),
        isNull(sessions.revokedAt),
      ),
    )
    .limit(1);
  return row ?? null;
}

/** 节流之后的 keep-alive。返回值 = 这次到底写没写，测试用它挡住「每次都写」的回退。 */
export async function keepSessionAlive(
  tdb: TenantDb,
  session: { id: string; lastSeenAt: string },
  now = Date.now(),
): Promise<boolean> {
  if (now - Date.parse(session.lastSeenAt) < TOUCH_INTERVAL_MS) return false;
  await tdb.db
    .update(sessions)
    .set({ lastSeenAt: nowIso(new Date(now)), updateBy: null, updateTime: nowIso() })
    .where(and(eq(sessions.tenantId, tdb.tenantId), eq(sessions.id, session.id)));
  return true;
}

/**
 * 会话列表。没有 session.manage 的人只看得到自己的那几条——不靠前端藏，
 * 查询本身就把别人的行where掉。
 */
export async function listSessions(
  tdb: TenantDb,
  viewer: { id: string; canManage: boolean },
  input: Partial<PageQuery> = {},
  currentSessionId?: string,
): Promise<Page<Session>> {
  const q = PageQuerySchema.parse(input);
  const pattern = searchPattern(q.q);
  const scope = viewer.canManage ? undefined : eq(sessions.userId, viewer.id);
  const where = and(
    eq(sessions.tenantId, tdb.tenantId),
    isNull(sessions.revokedAt),
    scope,
    or(
      ilike(users.email, pattern),
      ilike(users.displayName, pattern),
      ilike(sessions.userAgent, pattern),
    ),
  );
  const page = await readPage(
    q,
    tdb.db
      .select(sessionColumns)
      .from(sessions)
      .innerJoin(users, eq(users.id, sessions.userId))
      .where(where)
      .orderBy(desc(sessions.lastSeenAt), asc(sessions.id))
      .limit(q.pageSize)
      .offset((q.page - 1) * q.pageSize),
    tdb.db
      .select({ total: count() })
      .from(sessions)
      .innerJoin(users, eq(users.id, sessions.userId))
      .where(where),
  );
  return {
    ...page,
    items: page.items.map((item) => ({
      ...item,
      current: item.id === currentSessionId,
      self: item.userId === viewer.id,
    })),
  };
}

export interface RevokedSession {
  id: string;
  userId: string;
  /** 退的是自己那条：界面上这句等于「退出登录」，不是「把别人踢下去」。 */
  self: boolean;
}

/**
 * 单条强退。自己那条谁都能退（那是退出登录）；别人的要 session.manage。
 * 已经退过的再退一次不写第二条审计——重复点击不该在审计里长出一串噪声。
 */
export async function revokeSession(
  tdb: TenantDb,
  actorId: string,
  sessionId: string,
): Promise<RevokedSession> {
  const [target] = await tdb.db
    .select({ id: sessions.id, userId: sessions.userId, revokedAt: sessions.revokedAt })
    .from(sessions)
    .where(and(eq(sessions.tenantId, tdb.tenantId), eq(sessions.id, sessionId)))
    .limit(1);
  if (!target) throw new ManagementError("not_found", "Session not found in this workspace");
  const self = target.userId === actorId;
  await requireActiveActor(tdb, actorId, self ? undefined : "session.manage");
  if (target.revokedAt !== null) return { id: target.id, userId: target.userId, self };
  await tdb.db
    .update(sessions)
    .set({ revokedAt: nowIso(), updateBy: actorId, updateTime: nowIso() })
    .where(and(eq(sessions.tenantId, tdb.tenantId), eq(sessions.id, target.id)));
  await tdb.db.insert(auditLogs).values({
    id: snowflakeId(),
    tenantId: tdb.tenantId,
    actorId,
    action: "session.revoked",
    targetId: target.id,
    detail: { userId: target.userId, self },
    createTime: nowIso(),
  });
  return { id: target.id, userId: target.userId, self };
}
