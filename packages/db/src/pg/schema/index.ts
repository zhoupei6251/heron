export * from "./tenants";
export * from "./users";
export * from "./identity";
export * from "./tasks";
export * from "./artifacts";
export * from "./usage";
export * from "./management";

/**
 * 「哪些表带 tenant_id」是这个架构的承重墙，所以它必须是可测的字符串清单：
 * schema.test.ts 拿这四张清单去对 information_schema 里的真实表，
 * 要求并集正好覆盖——漏一张就是漏一道墙，多一张就是搬走了墙还留着说明。
 */
export const TABLES_WITH_TENANT = [
  "users",
  "platform_accounts",
  "settings",
  "feed_sources",
  "candidates",
  "tasks",
  "confirmations",
  "publishes",
  "aigc_posture",
  "usage_records",
  "audit_logs",
] as const;

/** 子表：租户由父表 FK 继承，自己不带 tenant_id 是有意的（带了反而会出现自相矛盾的归属）。 */
export const TABLES_WITHOUT_TENANT = ["task_events", "gate_results", "renders"] as const;

/** 租户自己就是租户边界，不参与「带 tenant_id」这件事。 */
export const TABLES_TENANT_ROOT = ["tenants"] as const;

/** Platform identity/audit are privileged only, never tenant children. */
export const TABLES_PLATFORM = ["platform_operators", "platform_audit_logs"] as const;
