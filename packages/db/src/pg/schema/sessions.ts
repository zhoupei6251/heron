import { sql } from "drizzle-orm";
import { index, pgTable, text } from "drizzle-orm/pg-core";

import { auditCols, fkCol, idCol } from "./columns";
import { tenants } from "./tenants";
import { users } from "./users";

/**
 * 一次登录一行。id 同时也是 token 里的 sid，所以它由登录处生成，不带默认值。
 *
 * 撤销是「标记」不是「删行」：删掉之后审计里的 session.revoked 就指向一个不存在的 id，
 * 事后查「这条是谁踢的」只能靠猜。last_seen_at 是节流写入的——每次请求都写，
 * 页面上一刷新就是一次 UPDATE，比会话本身有用得多的写放大。
 *
 * 带 tenant_id：它是租户边界的直接证据，也省得为了列会话去 join users 才知道归属。
 */
export const sessions = pgTable(
  "sys_session",
  {
    ...auditCols(),
    id: idCol(),
    tenantId: fkCol("tenant_id", () => tenants.id).notNull(),
    userId: fkCol("user_id", () => users.id).notNull(),
    lastSeenAt: text("last_seen_at").notNull(),
    // 只用于给人看的「在哪台设备上」，不参与任何判断
    userAgent: text("user_agent"),
    revokedAt: text("revoked_at"),
  },
  (t) => [
    // 会话列表：租户等值 + 最近创建，id 兜平同一时刻
    index("ix_sys_session_tenant_recent").on(t.tenantId, sql`${t.createTime} DESC`, t.id),
    // 只看自己那几条（无 session.manage 的人走这条）
    index("ix_sys_session_tenant_user").on(t.tenantId, t.userId, sql`${t.lastSeenAt} DESC`),
  ],
);
