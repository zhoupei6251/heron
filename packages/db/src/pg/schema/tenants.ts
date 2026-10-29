import { sql } from "drizzle-orm";
import { check, index, pgTable, text, uniqueIndex } from "drizzle-orm/pg-core";
import { PLAN_IDS, type PlanId } from "@heron/contracts";

import { auditCols, idCol, softDeleteCols } from "./columns";
import { inEnum } from "./enums";

/** 租户是这一切的外键目标；第一期只有 DEFAULT_TENANT_ID 一行，但边界从第一天就立着。 */
export const tenants = pgTable(
  "sys_tenant",
  {
    ...auditCols(),
    ...softDeleteCols(),
    id: idCol(),
    name: text("name").notNull(),
    slug: text("slug").notNull(),
    // 档位只决定额度，不决定能不能用。默认 free：新租户默认不该白拿最高额度。
    plan: text("plan").$type<PlanId>().notNull().default("free"),
    // 停租是软关闭：数据不许物理删，「当时这个租户还能不能发」必须可回溯
    suspendedAt: text("suspended_at"),
  },
  (t) => [
    // 登录时人报的是工作空间名（slug），库里只留小写这一种形状，查询才不许猜大小写。
    // 与 ck_sys_user_email_lower 同一条规矩：规范形由 DB 兜，不靠每个写入方自觉。
    check("ck_sys_tenant_slug_lower", sql`${t.slug} = lower(${t.slug})`),
    // 与 PLAN_IDS 同源：写进一个不认识的档位，额度判据就会读到 undefined 而静默放行
    check("ck_sys_tenant_plan_known", inEnum(t.plan, PLAN_IDS)),
    // 平台租户列表：没有租户维度可等值，只按最近创建
    index("ix_sys_tenant_recent").on(sql`${t.createTime} DESC`, t.id),
    // slug 唯一，但只对活着的租户唯一：软删掉的租户不该占着这个名字，
    // 否则「删掉 A 再建一个叫 A 的」会撞唯一索引，而报错完全指不到软删上。
    uniqueIndex("u_sys_tenant_slug")
      .on(t.slug)
      .where(sql`${t.delFlag} = '0'`),
  ],
);

/**
 * 保留的哨兵租户 id：全库只有这一行是「固定的」，它不是雪花 id。
 *
 * 为什么留一个不是雪花的值：种子里要有一个确定能引用到的租户（第一个 owner 的家）。
 * 雪花 id 每台机器、每次生成都不同，写死在代码里就等于给了一个必然对不上的坐标。
 * 所以它是一个保留号——与任何雪花 id 都不会撞（雪花 id 至少 8 位、远大于 1）。
 */
export const DEFAULT_TENANT_ID = "1";
