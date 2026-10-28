import { sql } from "drizzle-orm";
import {
  boolean,
  check,
  index,
  integer,
  jsonb,
  pgTable,
  text,
  uniqueIndex,
  uuid,
} from "drizzle-orm/pg-core";
import {
  PROTO_FAILURE_KINDS,
  PROTO_STAGES,
  PROTO_TARGETS,
  PROTO_THEMES,
  type ProtoFailureKind,
  type ProtoStage,
  type ProtoTarget,
  type ProtoTheme,
} from "@heron/contracts";

import { inEnum } from "./enums";
import { tenants } from "./tenants";
import { users } from "./users";

/**
 * 原型工坊的两张表。与新闻线不共用任务表：产物、闸与失败分类都不一样，
 * 塞进 tasks 只会让两条线的判据互相污染（见需求文档 §1）。
 */

export const protoProjects = pgTable(
  "proto_projects",
  {
    id: uuid("id").primaryKey(),
    tenantId: uuid("tenant_id")
      .notNull()
      .references(() => tenants.id, { onDelete: "cascade" }),
    title: text("title").notNull(),
    /** 需求原文。上限由接口层判（PROTO_REQUIREMENT_MAX），这里不做长度检查，换限不必迁移。 */
    requirement: text("requirement").notNull(),
    target: text("target").$type<ProtoTarget>().notNull(),
    theme: text("theme").$type<ProtoTheme>().notNull(),
    stage: text("stage").$type<ProtoStage>().notNull(),
    /** 失败原因只有一条：本次生成停下来的那一步，界面展开全文。 */
    errorReason: text("error_reason"),
    // 与 tasks 同一套租约：worker 身份，不是人；心跳过期才可被接管
    leaseOwner: text("lease_owner"),
    heartbeat: text("heartbeat"),
    attempts: integer("attempts").notNull().default(0),
    createdBy: uuid("created_by").references(() => users.id),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (t) => [
    // 领取查询只认这两个阶段：draft 是待生成，approved 是等人确认后待交付（worker 只做落库）
    index("ix_proto_projects_claimable")
      .on(t.createdAt)
      .where(sql`${t.stage} IN ('draft','approved')`),
    index("ix_proto_projects_tenant_recent").on(t.tenantId, sql`${t.createdAt} DESC`, t.id),
    check("ck_proto_projects_stage_known", inEnum(t.stage, PROTO_STAGES)),
    check("ck_proto_projects_target_known", inEnum(t.target, PROTO_TARGETS)),
    check("ck_proto_projects_theme_known", inEnum(t.theme, PROTO_THEMES)),
    check("ck_proto_projects_attempts_nonnegative", sql`${t.attempts} >= 0`),
  ],
);

export const protoRevisions = pgTable(
  "proto_revisions",
  {
    id: uuid("id").primaryKey(),
    /** 子表：租户靠父表 FK 继承（见 schema/index.ts 与 rls.ts 的父表映射）。 */
    projectId: uuid("project_id")
      .notNull()
      .references(() => protoProjects.id, { onDelete: "cascade" }),
    version: integer("version").notNull(),
    /** 模型原稿。先落库再编译：check 的诊断要对得上模型当时写的那份。 */
    rp1Source: text("rp1_source"),
    engineVersion: text("engine_version"),
    /** audit-report.json 原文。判定结论走 domain 的纯函数，这里只留证据。 */
    audit: jsonb("audit").$type<unknown>(),
    model: text("model"),
    tokensIn: integer("tokens_in"),
    tokensOut: integer("tokens_out"),
    failureKind: text("failure_kind").$type<ProtoFailureKind>(),
    failureReason: text("failure_reason"),
    /** 产物的字节指纹；路径由 project/version 推出，落库只存不允许被请求体指定的部分。 */
    rpSha256: text("rp_sha256"),
    rpBytes: integer("rp_bytes"),
    previewSha256: text("preview_sha256"),
    // 人的闸：确认人、确认时间、当时预览图的指纹；原生闸的勾选也记在版本上
    confirmedBy: uuid("confirmed_by").references(() => users.id),
    confirmedAt: text("confirmed_at"),
    axureVerified: boolean("axure_verified").notNull().default(false),
    axureVerifiedBy: uuid("axure_verified_by").references(() => users.id),
    axureVerifiedAt: text("axure_verified_at"),
    deliveredAt: text("delivered_at"),
    createdAt: text("created_at").notNull(),
  },
  (t) => [
    uniqueIndex("u_proto_revisions_project_version").on(t.projectId, t.version),
    index("ix_proto_revisions_project").on(t.projectId, t.version),
    check("ck_proto_revisions_version_positive", sql`${t.version} > 0`),
    check(
      "ck_proto_revisions_tokens_nonnegative",
      sql`(${t.tokensIn} IS NULL OR ${t.tokensIn} >= 0) AND (${t.tokensOut} IS NULL OR ${t.tokensOut} >= 0)`,
    ),
    check(
      "ck_proto_revisions_rp_bytes_nonnegative",
      sql`${t.rpBytes} IS NULL OR ${t.rpBytes} >= 0`,
    ),
    check("ck_proto_revisions_failure_kind_known", inEnum(t.failureKind, PROTO_FAILURE_KINDS)),
    // 勾了「已在 Axure 打开过」就必须说得出是谁、何时勾的；没勾时两列都必须为空
    check(
      "ck_proto_revisions_axure_attribution",
      sql`(${t.axureVerified} = false AND ${t.axureVerifiedBy} IS NULL AND ${t.axureVerifiedAt} IS NULL)
          OR (${t.axureVerified} = true AND ${t.axureVerifiedBy} IS NOT NULL AND ${t.axureVerifiedAt} IS NOT NULL)`,
    ),
  ],
);
