import { sql } from "drizzle-orm";
import { check, index, pgTable, text, uuid } from "drizzle-orm/pg-core";
import { PLAN_IDS, type PlanId } from "@heron/contracts";

import { inEnum } from "./enums";

/** 租户是这一切的外键目标；第一期只有 DEFAULT_TENANT_ID 一行，但边界从第一天就立着。 */
export const tenants = pgTable(
  "tenants",
  {
    id: uuid("id").primaryKey(),
    name: text("name").notNull(),
    slug: text("slug").notNull().unique(),
    // 档位只决定额度，不决定能不能用。默认 free：新租户默认不该白拿最高额度。
    plan: text("plan").$type<PlanId>().notNull().default("free"),
    // 停租是软关闭：数据不许物理删，「当时这个租户还能不能发」必须可回溯
    suspendedAt: text("suspended_at"),
    createdAt: text("created_at").notNull(),
  },
  (t) => [
    // 登录时人报的是工作空间名（slug），库里只留小写这一种形状，查询才不许猜大小写。
    // 与 ck_users_email_lower 同一条规矩：规范形由 DB 兜，不靠每个写入方自觉。
    check("ck_tenants_slug_lower", sql`${t.slug} = lower(${t.slug})`),
    // 与 PLAN_IDS 同源：写进一个不认识的档位，额度判据就会读到 undefined 而静默放行
    check("ck_tenants_plan_known", inEnum(t.plan, PLAN_IDS)),
    // 平台租户列表：没有租户维度可等值，只按最近创建
    index("ix_tenants_recent").on(sql`${t.createdAt} DESC`, t.id),
  ],
);

export const DEFAULT_TENANT_ID = "00000000-0000-4000-8000-000000000001";
