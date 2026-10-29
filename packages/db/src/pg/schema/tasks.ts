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

import { auditCols, fkCol, idCol, softDeleteCols } from "./columns";
import { inEnum } from "./enums";
import { users } from "./users";
import { tenants } from "./tenants";

/**
 * `$type<Stage>()` / `$type<GateName>()` 把 TS 侧类型也接到 contracts 那一份枚举上：
 * 读出来就是 Stage，仓储层不必 `as Stage`；写错值在编译期红，而不是等 CHECK 在运行期拒。
 * 只改类型不改 SQL，所以迁移文件不动。
 */

/**
 * 保留天数、抓取频率这类是租户配置，不是全局配置——所以主键是 (tenant_id, key)。
 *
 * 这是一张键值表：键就是身份，没有第二层 id（平台级的 plat_setting 同理）。
 * 因此它也没有软删列——软删掉的行仍然占着这个键，再写同名的设置就会撞主键，
 * 软删在这里没有意义（设置的「删掉」就是写回一个值或退回默认）。
 */
export const settings = pgTable(
  "news_setting",
  {
    ...auditCols(),
    tenantId: fkCol("tenant_id", () => tenants.id, { onDelete: "cascade" }).notNull(),
    key: text("key").notNull(),
    value: text("value").notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.key] })],
);

/** 热榜抓取源。名字让给 news 的出处表用（evidence 引的是 S1..Sn），所以带 feed_ 前缀。 */
export const feedSources = pgTable(
  "news_feed_source",
  {
    ...auditCols(),
    ...softDeleteCols(),
    id: idCol(),
    tenantId: fkCol("tenant_id", () => tenants.id, { onDelete: "cascade" }).notNull(),
    name: text("name").notNull(),
    url: text("url").notNull(),
    frequencySec: integer("frequency_sec").notNull().default(900),
    enabled: boolean("enabled").notNull().default(true),
    /** 最后一次**成功**抓取；失败不动它，界面才不会把「试过了」当成「抓到了」。 */
    lastFetchAt: text("last_fetch_at"),
    /** 最后一次尝试（成功或失败都写）：下次重试 = 它 + frequency_sec。 */
    lastAttemptAt: text("last_attempt_at"),
    /** 最后一次失败原因；成功后清空。失败不静默靠这一列。 */
    lastError: text("last_error"),
  },
  (t) => [
    check("ck_news_feed_source_frequency", sql`${t.frequencySec} >= 60`),
    // 同一个租户内 URL 唯一，但只对活着的源唯一：删掉一个源之后要能重建同 URL 的源。
    uniqueIndex("u_news_feed_source_tenant_url")
      .on(t.tenantId, t.url)
      .where(sql`${t.delFlag} = '0'`),
    // 数据源列表：租户等值 + 最近创建
    index("ix_news_feed_source_tenant_recent").on(t.tenantId, sql`${t.createTime} DESC`, t.id),
  ],
);

/**
 * 台账现状视图：harness 的 ledger.jsonl 是追加式 latest-wins，这里存「折叠后的当前行」。
 * norm_key 必须与 ledger.py 的 re.sub(r"[\W_]+", "", title) 逐字符一致（不加 toLowerCase）。
 */
export const candidates = pgTable(
  "news_candidate",
  {
    ...auditCols(),
    ...softDeleteCols(),
    id: idCol(),
    tenantId: fkCol("tenant_id", () => tenants.id, { onDelete: "cascade" }).notNull(),
    title: text("title").notNull(),
    summary: text("summary").notNull().default(""),
    normKey: text("norm_key").notNull(),
    sourceId: fkCol("source_id", () => feedSources.id),
    heat: doublePrecision("heat").notNull().default(0),
    rankPeak: integer("rank_peak"),
    hits: integer("hits").notNull().default(1),
    status: text("status").$type<CandidateStatus>().notNull().default("candidate"),
    firstSeenAt: text("first_seen_at").notNull(),
    lastSeenAt: text("last_seen_at").notNull(),
  },
  (t) => [
    // 去重是「租户内的事实」：两个客户撞同一热点是正常业务，全局唯一会把平台做成单人工具。
    // 只对活着的候选唯一：软删掉的热点不该继续占着去重键。
    uniqueIndex("u_news_candidate_tenant_norm")
      .on(t.tenantId, t.normKey)
      .where(sql`${t.delFlag} = '0'`),
    // 候选列表 = 租户等值（RLS 给）+ 最近上榜排序；索引照查询形状建，id 兜平同一时刻
    index("ix_news_candidate_tenant_recent").on(t.tenantId, sql`${t.lastSeenAt} DESC`, t.id),
    check("ck_news_candidate_hits_positive", sql`${t.hits} > 0`),
    check("ck_news_candidate_rank_peak_positive", sql`${t.rankPeak} IS NULL OR ${t.rankPeak} > 0`),
    check("ck_news_candidate_status_known", inEnum(t.status, CANDIDATE_STATUSES)),
  ],
);

export const tasks = pgTable(
  "news_task",
  {
    ...auditCols(),
    ...softDeleteCols(),
    id: idCol(),
    tenantId: fkCol("tenant_id", () => tenants.id, { onDelete: "cascade" }).notNull(),
    capability: text("capability").notNull(),
    candidateId: fkCol("candidate_id", () => candidates.id),
    stage: text("stage").$type<Stage>().notNull(),
    // lease_owner 是 worker 进程身份（runner-<host>），不是人——所以它不归 users
    leaseOwner: text("lease_owner"),
    heartbeat: text("heartbeat"),
    attempts: integer("attempts").notNull().default(0),
    claimedBy: fkCol("claimed_by", () => users.id),
    claimedAt: text("claimed_at"),
    errorReason: text("error_reason"),
  },
  (t) => [
    // 两条领取查询都长这样：非终态 + 租约过期，取 create_time 最早的一条。
    // 索引照查询形状建（局部谓词是终态之外的都算），否则每次轮询都要把非终态任务全取出来排序
    index("ix_news_task_claimable")
      .on(t.createTime)
      .where(sql`${t.stage} NOT IN ('published','failed')`),
    // 租户台的任务列表：租户等值 + 最近创建
    index("ix_news_task_tenant_recent").on(t.tenantId, sql`${t.createTime} DESC`, t.id),
    index("ix_news_task_candidate").on(t.candidateId),
    check("ck_news_task_stage_known", inEnum(t.stage, STAGES)),
    check("ck_news_task_attempts_nonnegative", sql`${t.attempts} >= 0`),
  ],
);

/** 子表：租户靠 tasks 的 FK 继承，不各自带 tenant_id（这条规则由 schema.test.ts 的两张清单钉住）。 */
export const taskEvents = pgTable(
  "news_task_event",
  {
    ...auditCols(),
    id: idCol(),
    taskId: fkCol("task_id", () => tasks.id, { onDelete: "cascade" }).notNull(),
    fromStage: text("from_stage").$type<Stage>().notNull(),
    toStage: text("to_stage").$type<Stage>().notNull(),
    // 闸放行也写，所以不叫 error_reason；失败详情在 tasks.error_reason 与 gate_results.rejections
    reason: text("reason"),
  },
  (t) => [
    index("ix_news_task_event_task").on(t.taskId, t.id),
    check("ck_news_task_event_from_stage", inEnum(t.fromStage, STAGES)),
    check("ck_news_task_event_to_stage", inEnum(t.toStage, STAGES)),
  ],
);

export const gateResults = pgTable(
  "news_gate_result",
  {
    ...auditCols(),
    id: idCol(),
    taskId: fkCol("task_id", () => tasks.id, { onDelete: "cascade" }).notNull(),
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
    index("ix_news_gate_result_task").on(t.taskId, t.gate, t.evaluatedAt),
    check("ck_news_gate_result_gate_known", inEnum(t.gate, GATES)),
    check(
      "ck_news_gate_result_render_version_positive",
      sql`${t.renderVersion} IS NULL OR ${t.renderVersion} > 0`,
    ),
  ],
);
