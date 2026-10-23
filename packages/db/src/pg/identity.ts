import { randomUUID } from "node:crypto";

import type { Role } from "@heron/contracts";
import { and, eq, isNull } from "drizzle-orm";

import { nowIso } from "../time";
import type { TenantDb } from "./rls";
import { users } from "./schema/index";

export interface NewUser {
  email: string;
  displayName: string;
  /**
   * 已经哈希过的串，由调用方给：这一层只负责「存进去且不许明文出现在日志里」，
   * 方案在 domain 的 hashPassword（scrypt）。合成 actor（p0、定时抓取）传
   * `UNUSABLE_PREFIX + 说明`，verifyPassword 对这种串永远返回 false——审计列留着，登录口关死。
   */
  passwordHash: string;
  role?: Role;
}

/** 建一个租户内的人，返回 id。邮箱在这里规范化，因为 ck_users_email_lower 只认小写。 */
export async function createUser(tdb: TenantDb, input: NewUser): Promise<string> {
  const id = randomUUID();
  const at = nowIso();
  await tdb.db.insert(users).values({
    id,
    tenantId: tdb.tenantId,
    email: input.email.toLowerCase(),
    displayName: input.displayName,
    passwordHash: input.passwordHash,
    role: input.role ?? "viewer",
    createdAt: at,
    updatedAt: at,
  });
  return id;
}

/**
 * 取本租户内的一个人，取不到就抛。
 *
 * 这条查询不是「多此一举的存在性检查」：外键完整性检查不走 RLS，
 * 别的租户的 user id 光靠 FK 是插得进来的——那样「确认人」会变成另一个客户的人，
 * 审计链直接接错。所以凡接受外部传入 id 的写入，归属都要自己查一遍。
 *
 * 带 role 出来是因为台账要记的是「哪个岗位上的人确认的」：调用方拿到的是同一次查询里的角色，
 * 不是事后另查一遍——中间被人改了角色就会出现「台账说他是 reviewer，其实当场他什么都不是」。
 */
export async function requireTenantUser(
  tdb: TenantDb,
  userId: string,
): Promise<{ id: string; displayName: string; role: Role }> {
  const rows = await tdb.db
    .select({ id: users.id, displayName: users.displayName, role: users.role })
    .from(users)
    .where(and(eq(users.id, userId), eq(users.tenantId, tdb.tenantId), isNull(users.disabledAt)))
    .limit(1);
  const user = rows[0];
  if (!user) throw new Error(`操作人不存在、已停用或不属于本租户：${userId}`);
  return user;
}

/**
 * 登录与守卫要看的那几列。passwordHash 到 AuthService 为止：它只进 verifyPassword，
 * 一步都不许进响应、进日志、进 token。disabledAt 留在这一层是为了让「判空」写在服务里，
 * 仓储只负责读，不负责决定「停用了还算不算人」——那是产品判据，会变。
 */
export interface PgUser {
  id: string;
  tenantId: string;
  email: string;
  displayName: string;
  role: Role;
  passwordHash: string;
  disabledAt: string | null;
}

const USER_COLUMNS = {
  id: users.id,
  tenantId: users.tenantId,
  email: users.email,
  displayName: users.displayName,
  role: users.role,
  passwordHash: users.passwordHash,
  disabledAt: users.disabledAt,
};

/** 邮箱按规范形查：库里只有小写（ck_users_email_lower），所以查询前自己归一一次，调用方传什么都一样。 */
export async function getUserByEmail(tdb: TenantDb, email: string): Promise<PgUser | null> {
  const rows = await tdb.db
    .select(USER_COLUMNS)
    .from(users)
    .where(and(eq(users.tenantId, tdb.tenantId), eq(users.email, email.toLowerCase())))
    .limit(1);
  return rows[0] ?? null;
}

/** 按 id 取本租户的人。RLS 已经挡住了别的租户，tenant_id 条件仍是明写的那道（见 withTenant）。 */
export async function getUserById(tdb: TenantDb, userId: string): Promise<PgUser | null> {
  const rows = await tdb.db
    .select(USER_COLUMNS)
    .from(users)
    .where(and(eq(users.tenantId, tdb.tenantId), eq(users.id, userId)))
    .limit(1);
  return rows[0] ?? null;
}

/** 停用是软关闭：留行、留哈希，只把登录口关上（撤销已签发的 token 靠守卫那次回库复查）。 */
export async function disableUser(
  tdb: TenantDb,
  userId: string,
  at: string = nowIso(),
): Promise<void> {
  // 不复用 requireTenantUser：那条把「已停用」也算不存在，重复停用就会报错。
  // 这里只在乎「这个人是不是本租户的」，归属不上的 id 不许静默成功。
  const rows = await tdb.db
    .select({ id: users.id })
    .from(users)
    .where(and(eq(users.id, userId), eq(users.tenantId, tdb.tenantId)))
    .limit(1);
  if (!rows[0]) throw new Error(`停用失败：本租户没有这个人：${userId}`);
  await tdb.db
    .update(users)
    .set({ disabledAt: at, updatedAt: at })
    .where(and(eq(users.id, userId), eq(users.tenantId, tdb.tenantId)));
}
