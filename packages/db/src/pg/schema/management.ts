import { sql } from "drizzle-orm";
import { check, index, integer, jsonb, pgTable, text, uniqueIndex } from "drizzle-orm/pg-core";
import { auditCols, fkCol, idCol, softDeleteCols } from "./columns";
import { tenants } from "./tenants";
import { users } from "./users";

export const auditLogs = pgTable(
  "sys_audit_log",
  {
    ...auditCols(),
    id: idCol(),
    tenantId: fkCol("tenant_id", () => tenants.id, { onDelete: "restrict" }).notNull(),
    actorId: fkCol("actor_id", () => users.id, { onDelete: "restrict" }),
    action: text("action").notNull(),
    targetId: text("target_id"),
    detail: jsonb("detail")
      .$type<Record<string, unknown>>()
      .notNull()
      .default(sql`'{}'::jsonb`),
  },
  // 审计列表按「最近写入优先、id 兜平同毫秒」翻页：方向要跟 ORDER BY 一致才吃得下这半段
  (t) => [index("ix_sys_audit_log_tenant_time").on(t.tenantId, sql`${t.createTime} DESC`, t.id)],
);

/** Separate identity realm; never granted to heron_app, not a tenant child table. */
export const platformOperators = pgTable(
  "plat_operator",
  {
    ...auditCols(),
    ...softDeleteCols(),
    id: idCol(),
    username: text("username").notNull(),
    displayName: text("display_name").notNull(),
    passwordHash: text("password_hash").notNull(),
    sessionVersion: integer("session_version").notNull().default(0),
    disabledAt: text("disabled_at"),
  },
  (t) => [
    // 用户名唯一，但只对活着的运营账号唯一：停用之后要能重新用同一个用户名开人。
    uniqueIndex("u_plat_operator_username")
      .on(t.username)
      .where(sql`${t.delFlag} = '0'`),
    check("ck_plat_operator_username_normalized", sql`${t.username} = lower(btrim(${t.username}))`),
  ],
);

export const platformAuditLogs = pgTable(
  "plat_audit_log",
  {
    ...auditCols(),
    id: idCol(),
    operatorId: fkCol("operator_id", () => platformOperators.id, { onDelete: "restrict" }),
    action: text("action").notNull(),
    targetId: text("target_id"),
    detail: jsonb("detail")
      .$type<Record<string, unknown>>()
      .notNull()
      .default(sql`'{}'::jsonb`),
  },
  (t) => [index("ix_plat_audit_log_time").on(sql`${t.createTime} DESC`, t.id)],
);

/**
 * 平台级默认值，一台机器一份。**不是租户表**：heron_app 读不到，只走特权通道。
 *
 * 值一律存文本，键的类型由 contracts 的 PLATFORM_SETTING_DEFS 定义——表只负责存，
 * 类型只在一处定义，加一项不用改表。这里也**不存密钥**：模型密钥只在租户设置或环境变量里。
 *
 * 主键是设置项的键本身（不是雪花 id）：一行就是一个键，没有第二层身份可言。
 */
export const platformSettings = pgTable("plat_setting", {
  ...auditCols(),
  key: text("key").primaryKey(),
  value: text("value").notNull(),
});
