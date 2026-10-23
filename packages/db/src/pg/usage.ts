import { sql } from "drizzle-orm";

import { nowIso } from "../time";
import type { AdminDb } from "./admin";
import type { TenantDb } from "./rls";
import { usageRecords } from "./schema/index";

/**
 * 用量台账。kind/unit 的合法组合由 ck_usage_records_kind_unit_pair 钉住（见 schema/usage.ts），
 * 这里不重列一遍 union：写错组合会在运行期红得明明白白，而两份列表迟早会漂移。
 *
 * quantity 用字符串而不是 number：numeric 列是钱与用量的底账，
 * 浮点在这里的每一次四舍五入都是一笔对不平的账（实测 pglite 与 postgres-js 都回字符串）。
 */
export interface NewUsage {
  kind: string;
  quantity: string;
  unit: string;
  /** 挂在哪个任务上；置空表示与任务无关的开销（比如定时抓取）。 */
  taskId?: string | null;
  detail?: unknown;
}

/** 记账只有一条路：写在租户上下文里，时间戳当场落。事后补不回来，所以也不给「可选时间」。 */
export async function recordUsage(tdb: TenantDb, input: NewUsage): Promise<void> {
  await tdb.db.insert(usageRecords).values({
    tenantId: tdb.tenantId,
    taskId: input.taskId ?? null,
    kind: input.kind,
    quantity: input.quantity,
    unit: input.unit,
    detail: input.detail ?? {},
    recordedAt: nowIso(),
  });
}

export interface UsageTotal {
  tenantId: string;
  kind: string;
  /** numeric 聚合结果，字符串形状，交给计费侧去定点。 */
  quantity: string;
  rows: number;
}

/**
 * 账单汇总走特权通道：出账方看的是全部租户，不属于任何一个租户。
 * 这也是 admin 通道唯一合理的读用途——业务读一律还是 TenantDb。
 */
export async function sumUsage(admin: AdminDb): Promise<UsageTotal[]> {
  const rows = await admin.db
    .select({
      tenantId: usageRecords.tenantId,
      kind: usageRecords.kind,
      quantity: sql<string>`sum(${usageRecords.quantity})::text`,
      rows: sql<number>`count(*)::int`,
    })
    .from(usageRecords)
    .groupBy(usageRecords.tenantId, usageRecords.kind);
  return rows;
}
