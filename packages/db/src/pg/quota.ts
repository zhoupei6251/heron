import { count, eq, gte } from "drizzle-orm";
import { PLAN_LIMITS, type PlanId, type PlanLimits } from "@heron/contracts";

import { publishes, renders, tenants, users } from "./schema/index";
import type { TenantDb } from "./rls";

/**
 * 当月账本怎么数：
 * - 座位数的是**成员行**，含被停用的那些。停用不是删人，把座位让出来等于「停用=退订」，
 *   而重新启用会突然撞上限——账要在加人那一刻说得清。
 * - 渲染数的是 `renders` 行：每一版都是一次真实渲染（重渲算新的一版），而不是用量台账里的秒数。
 * - 发布数的是 `publishes` 行：同一版本重试共用一个幂等键、只有一行，所以重试不吃额度。
 *
 * 三张表都没有按「自然月」建索引也能跑：单租户一个月的行数是几十到几百的量级，
 * 真正要索引的是跨月的账号级查询（`ix_usage_records_tenant_time`），别为这条判据再加一个索引。
 */

export interface MonthlyUsage {
  seats: number;
  renders: number;
  publishes: number;
}

/**
 * 自然月从 UTC 月初算起，与 usage_records.recorded_at 的写法（ISO UTC）同一把尺子。
 * 按本地时区切月会在部署换时区时凭空多出/少掉一天的额度，而那时没人会想到是时区。
 */
export function monthStartIso(now: Date = new Date()): string {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)).toISOString();
}

/** 当前租户的档位。读不到行说明租户上下文与库里对不上，按最低档处理（fail closed）。 */
export async function tenantPlan(tdb: TenantDb): Promise<PlanId> {
  const [row] = await tdb.db
    .select({ plan: tenants.plan })
    .from(tenants)
    .where(eq(tenants.id, tdb.tenantId));
  return row?.plan ?? "free";
}

/**
 * 当月用量。renders / publishes 没有 tenant_id（子表，租户由 tasks 的 FK 继承），
 * RLS 已经顺着父表把它们收口到本租户，这里再 join tasks 只会让谓词变长、结果一样。
 */
export async function monthlyUsage(tdb: TenantDb, now: Date = new Date()): Promise<MonthlyUsage> {
  const since = monthStartIso(now);
  const [seatRow] = await tdb.db
    .select({ total: count() })
    .from(users)
    .where(eq(users.tenantId, tdb.tenantId));
  const [renderRow] = await tdb.db
    .select({ total: count() })
    .from(renders)
    .where(gte(renders.createTime, since));
  const [publishRow] = await tdb.db
    .select({ total: count() })
    .from(publishes)
    .where(gte(publishes.createTime, since));
  return {
    seats: seatRow?.total ?? 0,
    renders: renderRow?.total ?? 0,
    publishes: publishRow?.total ?? 0,
  };
}

export interface QuotaStatus {
  plan: PlanId;
  limits: PlanLimits;
  usage: MonthlyUsage;
}

/**
 * 界面要的一整块：档位、上限、当月用量一起给。
 * 只给用量不给上限，界面就得自己再写一份 PLAN_LIMITS——那正是「界面允许、worker 拒绝」的起点。
 */
export async function quotaStatus(tdb: TenantDb, now: Date = new Date()): Promise<QuotaStatus> {
  const plan = await tenantPlan(tdb);
  return { plan, limits: PLAN_LIMITS[plan], usage: await monthlyUsage(tdb, now) };
}
