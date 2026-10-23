import { sql } from "drizzle-orm";
import { check, pgTable, text, uuid } from "drizzle-orm/pg-core";

/** 租户是这一切的外键目标；第一期只有 DEFAULT_TENANT_ID 一行，但边界从第一天就立着。 */
export const tenants = pgTable(
  "tenants",
  {
    id: uuid("id").primaryKey(),
    name: text("name").notNull(),
    slug: text("slug").notNull().unique(),
    // 停租是软关闭：数据不许物理删，「当时这个租户还能不能发」必须可回溯
    suspendedAt: text("suspended_at"),
    createdAt: text("created_at").notNull(),
  },
  (t) => [
    // 登录时人报的是工作空间名（slug），库里只留小写这一种形状，查询才不许猜大小写。
    // 与 ck_users_email_lower 同一条规矩：规范形由 DB 兜，不靠每个写入方自觉。
    check("ck_tenants_slug_lower", sql`${t.slug} = lower(${t.slug})`),
  ],
);

export const DEFAULT_TENANT_ID = "00000000-0000-4000-8000-000000000001";
