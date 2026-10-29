import { sql } from "drizzle-orm";
import { check, index, integer, pgTable, text, uniqueIndex } from "drizzle-orm/pg-core";
import { ROLES, type Role } from "@heron/contracts";

import { auditCols, fkCol, idCol, softDeleteCols } from "./columns";
import { inEnum } from "./enums";
import { tenants } from "./tenants";

/**
 * 人是租户内的人：owner 在本租户是最高权限，在别的租户什么都不算。
 *
 * 时间列一律 text（口径见 time.ts），取值由 DB 默认值给出——形状与 `nowIso()` 逐字符一致
 * （参考项目那边是 MySQL 的 `DEFAULT CURRENT_TIMESTAMP` + `ON UPDATE CURRENT_TIMESTAMP`，
 * Postgres 没有后者，所以 update_time 由触发器补上）。
 */
export const users = pgTable(
  "sys_user",
  {
    ...auditCols(),
    ...softDeleteCols(),
    id: idCol(),
    tenantId: fkCol("tenant_id", () => tenants.id).notNull(),
    email: text("email").notNull(),
    displayName: text("display_name").notNull(),
    // 合成 actor（p0、未来的定时抓取）带 "!" 前缀，永远不可登录——见计划 T5 与 domain/password.ts
    passwordHash: text("password_hash").notNull(),
    sessionVersion: integer("session_version").notNull().default(0),
    role: text("role").$type<Role>().notNull().default("viewer"),
    disabledAt: text("disabled_at"),
  },
  (t) => [
    // 同一邮箱在不同租户是两个人；全局唯一会把多租户平台做成单人工具。
    // 只对活着的成员唯一：软删之后要能重新用同一个邮箱入职。
    uniqueIndex("u_sys_user_tenant_email")
      .on(t.tenantId, t.email)
      .where(sql`${t.delFlag} = '0'`),
    // 成员列表：租户等值 + 最近创建，id 兜平同一时刻
    index("ix_sys_user_tenant_recent").on(t.tenantId, sql`${t.createTime} DESC`, t.id),
    // 规范形只留一种：登录查询不许再猜大小写
    check("ck_sys_user_email_lower", sql`${t.email} = lower(${t.email})`),
    check("ck_sys_user_role_known", inEnum(t.role, ROLES)),
  ],
);
