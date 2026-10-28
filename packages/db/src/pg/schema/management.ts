import { sql } from "drizzle-orm";
import {
  check,
  index,
  integer,
  jsonb,
  pgTable,
  text,
  uniqueIndex,
  uuid,
} from "drizzle-orm/pg-core";
import { tenants } from "./tenants";
import { users } from "./users";

export const auditLogs = pgTable(
  "audit_logs",
  {
    id: uuid("id").primaryKey(),
    tenantId: uuid("tenant_id")
      .notNull()
      .references(() => tenants.id, { onDelete: "restrict" }),
    actorId: uuid("actor_id").references(() => users.id, { onDelete: "restrict" }),
    action: text("action").notNull(),
    targetId: text("target_id"),
    detail: jsonb("detail")
      .$type<Record<string, unknown>>()
      .notNull()
      .default(sql`'{}'::jsonb`),
    createdAt: text("created_at").notNull(),
  },
  // 审计列表按「最近写入优先、id 兜平同毫秒」翻页：方向要跟 ORDER BY 一致才吃得下这半段
  (t) => [index("ix_audit_logs_tenant_time").on(t.tenantId, sql`${t.createdAt} DESC`, t.id)],
);

/** Separate identity realm; never granted to heron_app, not a tenant child table. */
export const platformOperators = pgTable(
  "platform_operators",
  {
    id: uuid("id").primaryKey(),
    username: text("username").notNull(),
    displayName: text("display_name").notNull(),
    passwordHash: text("password_hash").notNull(),
    sessionVersion: integer("session_version").notNull().default(0),
    disabledAt: text("disabled_at"),
    createdAt: text("created_at").notNull(),
  },
  (t) => [
    uniqueIndex("u_platform_operators_username").on(t.username),
    check(
      "ck_platform_operators_username_normalized",
      sql`${t.username} = lower(btrim(${t.username}))`,
    ),
  ],
);

export const platformAuditLogs = pgTable(
  "platform_audit_logs",
  {
    id: uuid("id").primaryKey(),
    operatorId: uuid("operator_id").references(() => platformOperators.id, {
      onDelete: "restrict",
    }),
    action: text("action").notNull(),
    targetId: text("target_id"),
    detail: jsonb("detail")
      .$type<Record<string, unknown>>()
      .notNull()
      .default(sql`'{}'::jsonb`),
    createdAt: text("created_at").notNull(),
  },
  (t) => [index("ix_platform_audit_logs_time").on(sql`${t.createdAt} DESC`, t.id)],
);
