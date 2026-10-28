import { sql } from "drizzle-orm";
import {
  check,
  doublePrecision,
  index,
  integer,
  jsonb,
  pgTable,
  text,
  uniqueIndex,
  uuid,
} from "drizzle-orm/pg-core";
import { PUBLISH_STATUSES, type PublishStatus } from "@heron/contracts";

import { inEnum } from "./enums";
import { users } from "./users";
import { tenants } from "./tenants";
import { tasks } from "./tasks";

/** 一次渲染一行；fingerprint 是人审看到的那张联络表的指纹，确认记录必须对上它。 */ export const renders =
  pgTable(
    "renders",
    {
      id: uuid("id").primaryKey(),
      // 子表：租户靠 tasks 的 FK 继承（规则见 schema/index.ts 的两张清单）
      taskId: uuid("task_id")
        .notNull()
        .references(() => tasks.id, { onDelete: "cascade" }),
      version: integer("version").notNull(),
      pack: text("pack").notNull(),
      mp4Path: text("mp4_path"),
      mp4Sha256: text("mp4_sha256"),
      mp4Bytes: integer("mp4_bytes"),
      contactSheetPath: text("contact_sheet_path"),
      fingerprint: text("fingerprint").notNull(),
      width: integer("width"),
      height: integer("height"),
      fps: doublePrecision("fps"),
      durationMs: integer("duration_ms"),
      // 合规三件套的 sidecar（aigc.json）落在成片旁边，DB 只存路径
      aigcSidecarPath: text("aigc_sidecar_path"),
      // 各阶段耗时 [{stage, seconds}]：一次渲染约 60s 墙钟，慢在哪得能查
      timings: jsonb("timings")
        .$type<{ stage: string; seconds: number }[]>()
        .notNull()
        .default(sql`'[]'::jsonb`),
      createdAt: text("created_at").notNull(),
    },
    (t) => [
      uniqueIndex("u_renders_task_version").on(t.taskId, t.version),
      // 子表没有 tenant_id：成片库按时间序取页，租户由 join tasks 过滤
      index("ix_renders_recent").on(sql`${t.createdAt} DESC`, t.id),
      check("ck_renders_version_positive", sql`${t.version} > 0`),
      check("ck_renders_bytes_nonnegative", sql`${t.mp4Bytes} IS NULL OR ${t.mp4Bytes} >= 0`),
      check("ck_renders_width_positive", sql`${t.width} IS NULL OR ${t.width} > 0`),
      check("ck_renders_height_positive", sql`${t.height} IS NULL OR ${t.height} > 0`),
      check("ck_renders_fps_positive", sql`${t.fps} IS NULL OR ${t.fps} > 0`),
      check(
        "ck_renders_duration_nonnegative",
        sql`${t.durationMs} IS NULL OR ${t.durationMs} >= 0`,
      ),
    ],
  );

/**
 * 写稿产出。一任务一行：重写就覆盖（重跑的判据是任务状态，不是版本号）。
 * content 原样存模型给的 JSON，形状由契约闸判——写稿阶段先落库再判，手改稿也绕不过去。
 */
export const scripts = pgTable(
  "scripts",
  {
    id: uuid("id").primaryKey(),
    taskId: uuid("task_id")
      .notNull()
      .references(() => tasks.id, { onDelete: "cascade" }),
    model: text("model").notNull(),
    content: jsonb("content").$type<unknown>().notNull(),
    createdAt: text("created_at").notNull(),
  },
  (t) => [uniqueIndex("u_scripts_task").on(t.taskId)],
);

export const confirmations = pgTable(
  "confirmations",
  {
    id: uuid("id").primaryKey(),
    tenantId: uuid("tenant_id")
      .notNull()
      .references(() => tenants.id, { onDelete: "cascade" }),
    taskId: uuid("task_id")
      .notNull()
      .references(() => tasks.id, { onDelete: "cascade" }),
    renderVersion: integer("render_version").notNull(),
    contactSheetFingerprint: text("contact_sheet_fingerprint").notNull(),
    // v2 的 confirmer 是自由文本（p0 往里写的是 P0_FAKE）。有了 users，「确认人」必须是外键——
    // 规格书 §5 的三样（确认人、确认时间、当时的指纹）少一样就会出现「人审 A、系统发 B」。
    confirmedBy: uuid("confirmed_by")
      .notNull()
      .references(() => users.id),
    confirmedAt: text("confirmed_at").notNull(),
  },
  (t) => [
    uniqueIndex("u_confirmations_render_fingerprint").on(
      t.taskId,
      t.renderVersion,
      t.contactSheetFingerprint,
    ),
    check("ck_confirmations_render_version_positive", sql`${t.renderVersion} > 0`),
  ],
);

export const publishes = pgTable(
  "publishes",
  {
    id: uuid("id").primaryKey(),
    tenantId: uuid("tenant_id")
      .notNull()
      .references(() => tenants.id, { onDelete: "cascade" }),
    renderId: uuid("render_id")
      .notNull()
      .references(() => renders.id, { onDelete: "cascade" }),
    platform: text("platform").notNull().default("douyin"),
    idempotencyKey: text("idempotency_key").notNull(),
    declaration: text("declaration").notNull(),
    // 登录态在 ~/.heron/state/douyin/，DB 只存路径 + sha256 指纹；发布前断言不匹配就红，不静默发
    credentialPath: text("credential_path"),
    credentialSha256: text("credential_sha256"),
    status: text("status").$type<PublishStatus>().notNull(),
    postUrl: text("post_url"),
    receipt: text("receipt"),
    // 重试前那次「其实已经发出去了」的对外核实时间：没有它，回执丢失就只能盲重试
    verifiedAt: text("verified_at"),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (t) => [
    uniqueIndex("u_publishes_tenant_idempotency").on(t.tenantId, t.idempotencyKey),
    // 发布记录列表：租户等值 + 最近创建（按 status 过滤目前没有查询，故不建 status 索引）
    index("ix_publishes_tenant_recent").on(t.tenantId, sql`${t.createdAt} DESC`, t.id),
    check("ck_publishes_status_known", inEnum(t.status, PUBLISH_STATUSES)),
    check(
      "ck_publishes_credential_pair",
      sql`(${t.credentialPath} IS NULL) = (${t.credentialSha256} IS NULL)`,
    ),
  ],
);

/**
 * AIGC 角标/声明姿态（harness 的 aigc-mode.json 落库版）：改姿态是审计动作，留全 history。
 * SQLite 那套 open_flag 生成列 + 唯一索引是「NULL 互不相等」的补丁；
 * Postgres 里 partial unique index 直接成立，补丁删掉。
 * set_by 与 confirmations.confirmed_by 同理是 uuid 外键：姿态一改就能把角标和声明绕掉，
 * 「谁改的」写成自由文本等于没写。归属校验在 insertPosture 里（外键不受 RLS 管）。
 */
export const aigcPosture = pgTable(
  "aigc_posture",
  {
    id: uuid("id").primaryKey(),
    tenantId: uuid("tenant_id")
      .notNull()
      .references(() => tenants.id, { onDelete: "cascade" }),
    renderMode: text("render_mode").notNull(),
    declaration: text("declaration").notNull(),
    note: text("note").notNull().default(""),
    setBy: uuid("set_by")
      .notNull()
      .references(() => users.id),
    sinceAt: text("since_at").notNull(),
    revertedAt: text("reverted_at"),
  },
  (t) => [
    uniqueIndex("u_aigc_posture_single_current")
      .on(t.tenantId)
      .where(sql`${t.revertedAt} IS NULL`),
    check("ck_aigc_posture_render_mode", sql`${t.renderMode} IN ('full','no-badge','draft')`),
    check("ck_aigc_posture_declaration", sql`${t.declaration} IN ('required','undeclared')`),
  ],
);
