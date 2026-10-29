import { sql } from "drizzle-orm";
import { check, index, pgTable, text, uniqueIndex } from "drizzle-orm/pg-core";

import { auditCols, fkCol, idCol } from "./columns";
import { tenants } from "./tenants";

/**
 * 发布账号归租户，不归平台进程——这是「谁来发」的答案，也是计划 4 换 OAuth 时唯一的挂载点。
 * credential 与 credentialPath 二选一：OAuth 走 AES-256-GCM 密文（密钥来自 env），
 * cookie 走磁盘路径（登录态本体永远不进库，见 README 安全边界）。
 */
export const platformAccounts = pgTable(
  "plat_account",
  {
    ...auditCols(),
    id: idCol(),
    tenantId: fkCol("tenant_id", () => tenants.id).notNull(),
    platform: text("platform").notNull(),
    externalAccountId: text("external_account_id").notNull(),
    // cookie 只许自研页面自动化用；对外客户的唯一许可路径是 oauth
    authMode: text("auth_mode").notNull(),
    credential: text("credential"),
    credentialPath: text("credential_path"),
    credentialSha256: text("credential_sha256"),
    verifiedAt: text("verified_at"),
  },
  (t) => [
    uniqueIndex("u_plat_account_external").on(t.tenantId, t.platform, t.externalAccountId),
    check("ck_plat_account_auth_mode", sql`${t.authMode} IN ('oauth','cookie')`),
    // 存了路径就必须有指纹：没有指纹的登录态来源不可证，宁可不让落库
    check(
      "ck_plat_account_credential_pair",
      sql`(${t.credentialPath} IS NULL) = (${t.credentialSha256} IS NULL)`,
    ),
    index("ix_plat_account_tenant").on(t.tenantId, t.platform),
  ],
);
