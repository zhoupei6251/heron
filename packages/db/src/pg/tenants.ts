import { randomUUID } from "node:crypto";

import { and, asc, eq, isNull } from "drizzle-orm";

import { nowIso } from "../time";
import { withAdmin } from "./admin";
import type { Db } from "./driver";
import type { TenantDb } from "./rls";
import { tenants } from "./schema/index";

/**
 * 租户的读写全走特权通道（heron_owner / BYPASSRLS），这不是偷懒，是时序决定的：
 * 说这句话的时候还没有任何可信的租户坐标——「我是 acme 家的人」是申请人自己报的，
 * 拿它去设 GUC 等于让请求自己选租户。所以这里只做**定位**（slug → id）与**生命周期**（建、停），
 * 定位出来的 id 由调用方签进 token，之后每条业务查询才配得上 withTenant。
 *
 * 与 admin.ts 那条「仓储函数第一参数是 TenantDb」的约定不同，这里三个函数都收裸 Db：
 * 它们本来就不在租户上下文里。别照着它们的样子往业务仓储里塞裸连接。
 */

/** 租户的存在与状态，不含任何业务数据。name/slug 是给界面显示「在哪个工作空间」的。 */
export interface TenantRef {
  id: string;
  name: string;
  slug: string;
  suspendedAt: string | null;
}

/** 建租户。slug 在这里规范化，因为 ck_tenants_slug_lower 只认小写。 */
export async function createTenant(
  db: Db,
  input: { id?: string; name: string; slug: string },
): Promise<string> {
  const id = input.id ?? randomUUID();
  const slug = input.slug.trim().toLowerCase();
  if (!slug) throw new Error(`建租户失败：slug 是空的：${input.name}`);
  await withAdmin(db, (a) =>
    a.db.insert(tenants).values({
      id,
      name: input.name,
      slug,
      suspendedAt: null,
      createdAt: nowIso(),
    }),
  );
  return id;
}

/**
 * 跨租户定时任务（采集到期、成片保留清理）要逐租户进入上下文，先得有这张名单。
 * 停租的也在内：数据还在磁盘上，保留策略不会因为被停用就失效。
 */
export async function listTenantIds(db: Db): Promise<string[]> {
  const rows = await withAdmin(db, (a) =>
    a.db.select({ id: tenants.id }).from(tenants).orderBy(tenants.createdAt),
  );
  return rows.map((row) => row.id);
}

/** 登录时人只报得出工作空间名，所以它是登录链上第一个查询——大小写与空格不许让人再猜一次。 */
export async function findTenantBySlug(db: Db, slug: string): Promise<TenantRef | null> {
  const rows = await withAdmin(db, (a) =>
    a.db
      .select({
        id: tenants.id,
        name: tenants.name,
        slug: tenants.slug,
        suspendedAt: tenants.suspendedAt,
      })
      .from(tenants)
      .where(eq(tenants.slug, slug.trim().toLowerCase()))
      .limit(1),
  );
  return rows[0] ?? null;
}

/**
 * 停租/复租。软关闭：只动 suspended_at，数据一行都不删——
 * 「当时这个租户还能不能发」是审计问题，删了就答不上来。
 */
export async function setTenantSuspended(
  db: Db,
  tenantId: string,
  suspendedAt: string | null,
): Promise<void> {
  await withAdmin(db, async (a) => {
    const rows = await a.db
      .select({ id: tenants.id })
      .from(tenants)
      .where(eq(tenants.id, tenantId))
      .limit(1);
    if (!rows[0]) throw new Error(`停租失败：没有这个租户：${tenantId}`);
    await a.db.update(tenants).set({ suspendedAt }).where(eq(tenants.id, tenantId));
  });
}

/**
 * 这个租户此刻还在、且没停吗？在租户上下文里问（tenants 的策略按 id 隔离，见 rls.ts），
 * 所以它顺带也回答了「这行租户还在不在」。一条查询同时带回名字与 slug：
 * 守卫要把这张租户表填进 AuthUser 给界面用，分两次查只会多出一个不一致的窗口。
 *
 * 存在的意义是补登录之外的那一半：JWT 短 TTL 也撤销不掉已发出去的会话，
 * 停租要立刻生效，只能靠守卫每次请求回库问这一句——「停租的租户整个关在门外」
 * 若只管登录，等于把已经登进来的人留在门里。
 */
export async function getActiveTenant(
  tdb: TenantDb,
  options: { forUpdate?: boolean } = {},
): Promise<{
  id: string;
  name: string;
  slug: string;
} | null> {
  const query = tdb.db
    .select({ id: tenants.id, name: tenants.name, slug: tenants.slug })
    .from(tenants)
    .where(and(eq(tenants.id, tdb.tenantId), isNull(tenants.suspendedAt)))
    .limit(1);
  // Login uses the same workspace lock as identity changes and suspension, before reading users.
  const rows = await (options.forUpdate ? query.for("update") : query);
  return rows[0] ?? null;
}

/**
 * 全部在用的工作空间，给右上角那个切换器用。
 *
 * 不复用 listPlatformTenants：那条分页查询带成员数与任务数两个聚合，切换器一个都不读，
 * 而空间列表要一次拿全（分页的切换器会让人以为世界上只有 20 个空间）。
 */
export function listActiveTenants(db: Db): Promise<TenantRef[]> {
  return withAdmin(db, (a) =>
    a.db
      .select({
        id: tenants.id,
        name: tenants.name,
        slug: tenants.slug,
        suspendedAt: tenants.suspendedAt,
      })
      .from(tenants)
      .where(isNull(tenants.suspendedAt))
      .orderBy(asc(tenants.name), asc(tenants.id)),
  );
}
