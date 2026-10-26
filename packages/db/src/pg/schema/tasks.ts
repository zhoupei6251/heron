import { sql } from "drizzle-orm";
import {
  boolean,
  check,
  doublePrecision,
  index,
  integer,
  jsonb,
  pgTable,
  primaryKey,
  text,
  uniqueIndex,
  uuid,
} from "drizzle-orm/pg-core";
import {
  CANDIDATE_STATUSES,
  GATES,
  STAGES,
  type CandidateStatus,
  type GateName,
  type Rejection,
  type Stage,
} from "@heron/contracts";

import { inEnum } from "./enums";
import { users } from "./users";
import { tenants } from "./tenants";

/**
 * `$type<Stage>()` / `$type<GateName>()` 把 TS 侧类型也接到 contracts 那一份枚举上：
 * 读出来就是 Stage，仓储层不必 `as Stage`；写错值在编译期红，而不是等 CHECK 在运行期拒。
 * 只改类型不改 SQL，所以迁移文件不动。
 */

/** 保留天数、抓取频率这类是租户配置，不是全局配置——所以主键是 (tenant_id, key)。 */
export const settings = pgTable(
  "settings",
  {
    tenantId: uuid("tenant_id")
      .notNull()
      .references(() => tenants.id, { onDelete: "cascade" }),
    key: text("key").notNull(),
    value: text("value").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.key] })],
);

/** 热榜抓取源。名字让给 news 的出处表用（evidence 引的是 S1..Sn），所以带 feed_ 前缀。 */
export const feedSources = pgTable(
  "feed_sources",
  {
    id: uuid("id").primaryKey(),
    tenantId: uuid("tenant_id")
      .notNull()
      .references(() => tenants.id, { onDelete: "cascade" }),
    name: text("name").notNull(),
    url: text("url").notNull(),
    frequencySec: integer("frequency_sec").notNull().default(900),
    enabled: boolean("enabled").notNull().default(true),
    lastFetchAt: text("last_fetch_at"),
    createdAt: text("created_at").notNull(),
  },
  (t) => [
    check("ck_feed_sources_frequency", sql`${t.frequencySec} >= 60`),
    uniqueIndex("u_feed_sources_tenant_url").on(t.tenantId, t.url),
  ],
);

/**
 * 台账现状视图：harness 的 ledger.jsonl 是追加式 latest-wins，这里存「折叠后的当前行」。
 * norm_key 必须与 ledger.py 的 re.sub(r"[\W_]+", "", title) 逐字符一致（不加 toLowerCase）。
 */
export const candidates = pgTable(
  "candidates",
  {
    id: uuid("id").primaryKey(),
    tenantId: uuid("tenant_id")
      .notNull()
      .references(() => tenants.id, { onDelete: "cascade" }),
    title: text("title").notNull(),
    summary: text("summary").notNull().default(""),
    normKey: text("norm_key").notNull(),
    sourceId: uuid("source_id").references(() => feedSources.id),
    heat: doublePrecision("heat").notNull().default(0),
    rankPeak: integer("rank_peak"),
    hits: integer("hits").notNull().default(1),
    status: text("status").$type<CandidateStatus>().notNull().default("candidate"),
    firstSeenAt: text("first_seen_at").notNull(),
    lastSeenAt: text("last_seen_at").notNull(),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (t) => [
    // 去重是「租户内的事实」：两个客户撞同一热点是正常业务，全局唯一会把平台做成单人工具
    uniqueIndex("u_candidates_tenant_norm").on(t.tenantId, t.normKey),
    index("ix_candidates_status").on(t.status, sql`${t.lastSeenAt} DESC`),
    check("ck_candidates_hits_positive", sql`${t.hits} > 0`),
    check("ck_candidates_rank_peak_positive", sql`${t.rankPeak} IS NULL OR ${t.rankPeak} > 0`),
    check("ck_candidates_status_known", inEnum(t.status, CANDIDATE_STATUSES)),
  ],
);

export const tasks = pgTable(
  "tasks",
  {
    id: uuid("id").primaryKey(),
    tenantId: uuid("tenant_id")
      .notNull()
      .references(() => tenants.id, { onDelete: "cascade" }),
    capability: text("capability").notNull(),
    candidateId: uuid("candidate_id").references(() => candidates.id),
    stage: text("stage").$type<Stage>().notNull(),
    // lease_owner 是 worker 进程身份（runner-<host>），不是人——所以它不归 users
    leaseOwner: text("lease_owner"),
    heartbeat: text("heartbeat"),
    attempts: integer("attempts").notNull().default(0),
    claimedBy: uuid("claimed_by").references(() => users.id),
    claimedAt: text("claimed_at"),
    errorReason: text("error_reason"),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (t) => [
    // runner 领取靠这条索引；partial 到非终态，避免把已完成的行也拖进来
    index("ix_tasks_claimable")
      .on(t.stage, t.leaseOwner, t.heartbeat)
      .where(sql`${t.stage} NOT IN ('published','failed')`),
    index("ix_tasks_candidate").on(t.candidateId),
    check("ck_tasks_stage_known", inEnum(t.stage, STAGES)),
    check("ck_tasks_attempts_nonnegative", sql`${t.attempts} >= 0`),
  ],
);

/** 子表：租户靠 tasks 的 FK 继承，不各自带 tenant_id（这条规则由 schema.test.ts 的两张清单钉住）。 */
export const taskEvents = pgTable(
  "task_events",
  {
    id: integer("id").primaryKey().generatedAlwaysAsIdentity(),
    taskId: uuid("task_id")
      .notNull()
      .references(() => tasks.id, { onDelete: "cascade" }),
    fromStage: text("from_stage").$type<Stage>().notNull(),
    toStage: text("to_stage").$type<Stage>().notNull(),
    // 闸放行也写，所以不叫 error_reason；失败详情在 tasks.error_reason 与 gate_results.rejections
    reason: text("reason"),
    createdAt: text("created_at").notNull(),
  },
  (t) => [
    index("ix_task_events_task").on(t.taskId, t.id),
    check("ck_task_events_from_stage", inEnum(t.fromStage, STAGES)),
    check("ck_task_events_to_stage", inEnum(t.toStage, STAGES)),
  ],
);

export const gateResults = pgTable(
  "gate_results",
  {
    id: uuid("id").primaryKey(),
    taskId: uuid("task_id")
      .notNull()
      .references(() => tasks.id, { onDelete: "cascade" }),
    gate: text("gate").$type<GateName>().notNull(),
    renderVersion: integer("render_version"),
    passed: boolean("passed").notNull(),
    note: text("note"),
    // 没有这一列，「为什么被拒」只剩一句拼好的文案，审计与回归都对不上账
    rejections: jsonb("rejections")
      .$type<Rejection[]>()
      .notNull()
      .default(sql`'[]'::jsonb`),
    evaluatedAt: text("evaluated_at").notNull(),
  },
  (t) => [
    index("ix_gate_results_task").on(t.taskId, t.gate, t.evaluatedAt),
    check("ck_gate_results_gate_known", inEnum(t.gate, GATES)),
    check(
      "ck_gate_results_render_version_positive",
      sql`${t.renderVersion} IS NULL OR ${t.renderVersion} > 0`,
    ),
  ],
);
