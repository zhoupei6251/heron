import { sql } from "drizzle-orm";
import { check, index, integer, pgTable, text, uniqueIndex, uuid } from "drizzle-orm/pg-core";
import { ROLES, type Role } from "@heron/contracts";

import { inEnum } from "./enums";
import { tenants } from "./tenants";

/**
 * 人是租户内的人：owner 在本租户是最高权限，在别的租户什么都不算。
 * 时间列一律 text（口径见 tenants.ts），DB 不给默认值——漏写就炸，不兜一个形状不同的 now()。
 */
export const users = pgTable(
  "users",
  {
    id: uuid("id").primaryKey(),
    tenantId: uuid("tenant_id")
      .notNull()
      .references(() => tenants.id, { onDelete: "cascade" }),
    email: text("email").notNull(),
    displayName: text("display_name").notNull(),
    // 合成 actor（p0、未来的定时抓取）带 "!" 前缀，永远不可登录——见计划 T5 与 domain/password.ts
    passwordHash: text("password_hash").notNull(),
    sessionVersion: integer("session_version").notNull().default(0),
    role: text("role").$type<Role>().notNull().default("viewer"),
    disabledAt: text("disabled_at"),
    createdAt: text("created_at").notNull(),
    updatedAt: text("updated_at").notNull(),
  },
  (t) => [
    // 同一邮箱在不同租户是两个人；全局唯一会把多租户平台做成单人工具
    uniqueIndex("u_users_tenant_email").on(t.tenantId, t.email),
    // 成员列表：租户等值 + 最近创建，id 兜平同一时刻
    index("ix_users_tenant_recent").on(t.tenantId, sql`${t.createdAt} DESC`, t.id),
    // 规范形只留一种：登录查询不许再猜大小写
    check("ck_users_email_lower", sql`${t.email} = lower(${t.email})`),
    check("ck_users_role_known", inEnum(t.role, ROLES)),
  ],
);
