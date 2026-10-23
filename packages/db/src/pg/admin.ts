import { sql } from "drizzle-orm";

import type { Db } from "./driver";

/**
 * 数据面只有两条通道，区别只有一个：RLS 生效与否。
 *
 * | 通道 | 角色 | RLS | 用途 |
 * |---|---|---|---|
 * | `withTenant` → `TenantDb` | `heron_app` | 生效，fail-closed | 一切业务读写 |
 * | `withAdmin` → `AdminDb` | `heron_owner`（BYPASSRLS） | 不生效 | 建租户、跨租户领任务、租约回收、账单汇总 |
 *
 * worker 领任务**没有租户上下文**——它是跨租户取活的调度方，领到的任务自带 tenant_id。
 * 这条线必须显式存在，否则要么调度方一行也领不到（策略判假），要么为了领得到把 RLS 关掉（隔离没了）。
 *
 * 单独成模块的原因很实际：eslint 的 `no-restricted-imports` 只能按**导入路径**拦，
 * 和 TenantDb 混在一个文件里就挡不住业务代码顺手 `import { withAdmin } from "./pg/rls"`。
 */
export const OWNER_ROLE = "heron_owner";

/** 特权通道句柄：已经降权到 heron_owner、并且已经在一个事务里。 */
export interface AdminDb {
  readonly db: Db;
}

/**
 * 特权上下文只有一个入口。`SET LOCAL ROLE` 而不是 session 级：
 * 真库是连接池，session 级降权会把下一个拿到这条连接的请求也变成特权请求。
 */
export async function withAdmin<T>(db: Db, fn: (a: AdminDb) => Promise<T>): Promise<T> {
  return db.transaction(async (tx) => {
    // 角色名写成字面量：SET ROLE 后面接不了绑定参数
    await tx.execute(sql`SET LOCAL ROLE heron_owner`);
    return fn({ db: tx as unknown as Db });
  });
}
