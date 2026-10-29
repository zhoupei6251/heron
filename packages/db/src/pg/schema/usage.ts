import { sql } from "drizzle-orm";
import { check, index, jsonb, numeric, pgTable, text } from "drizzle-orm/pg-core";
import { USAGE_KINDS, USAGE_KIND_UNITS, USAGE_UNITS } from "@heron/contracts";

import { auditCols, fkCol, idCol } from "./columns";
import { inEnum, lit } from "./enums";
import { tasks } from "./tasks";
import { tenants } from "./tenants";

/**
 * 用量台账先于价格存在：账本形状现在就能定，价格与套餐是计划 5 的事。
 * 每个 kind 只允许配一个 unit——配错是记账事故，靠 code review 拦不住，所以写成 CHECK。
 * 三条 CHECK 都从 contracts 的 USAGE_KINDS / USAGE_UNITS / USAGE_KIND_UNITS 生成：
 * 配对表只有一份，zod 的 refine 与库的约束迟早不会各飘一半。
 *
 * recorded_at 是「这笔用量发生在什么时候」（业务时刻），与审计四件套的 create_time
 * （这行什么时候落库）是两件事，两个都留。
 */
export const usageRecords = pgTable(
  "usage_record",
  {
    ...auditCols(),
    id: idCol(),
    tenantId: fkCol("tenant_id", () => tenants.id, { onDelete: "cascade" }).notNull(),
    // 任务被删时账不许跟着蒸发：置空 task_id，行留着
    taskId: fkCol("task_id", () => tasks.id, { onDelete: "set null" }),
    kind: text("kind").notNull(),
    quantity: numeric("quantity").notNull(),
    unit: text("unit").notNull(),
    detail: jsonb("detail")
      .notNull()
      .default(sql`'{}'::jsonb`),
    recordedAt: text("recorded_at").notNull(),
  },
  (t) => [
    index("ix_usage_record_tenant_time").on(t.tenantId, t.recordedAt),
    check("ck_usage_record_kind_known", inEnum(t.kind, USAGE_KINDS)),
    check("ck_usage_record_unit_known", inEnum(t.unit, USAGE_UNITS)),
    check(
      "ck_usage_record_kind_unit_pair",
      sql`CASE ${t.kind} ${sql.join(
        USAGE_KINDS.map(
          (kind) => sql`WHEN ${lit(kind)} THEN ${t.unit} = ${lit(USAGE_KIND_UNITS[kind])}`,
        ),
        sql` `,
      )} END`,
    ),
  ],
);
