export * from "./artifacts";
export * from "./columns";
export * from "./identity";
export * from "./management";
export * from "./proto";
export * from "./sessions";
export * from "./tasks";
export * from "./tenants";
export * from "./usage";
export * from "./users";

/**
 * 「哪些表带 tenant_id」是这个架构的承重墙，所以它必须是可测的字符串清单：
 * schema.test.ts 拿这四张清单去对 information_schema 里的真实表，
 * 要求并集正好覆盖——漏一张就是漏一道墙，多一张就是搬走了墙还留着说明。
 *
 * 表名一律 `<域>_<实体单数>`（sys_ / news_ / plat_ / proto_ / usage_），
 * 域前缀是这套命名规范的骨架：看表名就知道它属于哪一块、归谁的 RLS 管。
 */
export const TABLES_WITH_TENANT = [
  "sys_user",
  "sys_session",
  "plat_account",
  "news_setting",
  "news_feed_source",
  "news_candidate",
  "news_task",
  "news_confirmation",
  "news_publish",
  "news_posture",
  "usage_record",
  "sys_audit_log",
  "proto_project",
] as const;

/** 子表：租户由父表 FK 继承，自己不带 tenant_id 是有意的（带了反而会出现自相矛盾的归属）。 */
export const TABLES_WITHOUT_TENANT = [
  "news_task_event",
  "news_gate_result",
  "news_render",
  "news_script",
  "proto_revision",
] as const;

/** 租户自己就是租户边界，不参与「带 tenant_id」这件事。 */
export const TABLES_TENANT_ROOT = ["sys_tenant"] as const;

/** Platform identity/audit are privileged only, never tenant children. */
export const TABLES_PLATFORM = ["plat_operator", "plat_audit_log", "plat_setting"] as const;

/**
 * 哪些表有逻辑删除（del_flag：'0' 存在 / '1' 已删）。
 *
 * 只有「业务实体」有：删掉的是一条记录本身，而不是一条证据。
 * 证据与流水表（审计、闸门结果、人工确认、发布记录、用量、事件）一律没有这一列——
 * 人工确认与闸门结果是「人审 A、系统发 B」的对照物，删了就没法对账。
 *
 * 键值表（news_setting / plat_setting）也没有：它们的主键就是键本身，
 * 软删掉的行仍然占着这个键，再写同名就撞主键，软删在这里没有意义。
 */
export const TABLES_WITH_SOFT_DELETE = [
  "sys_tenant",
  "sys_user",
  "plat_operator",
  "news_feed_source",
  "news_candidate",
  "news_task",
  "news_script",
  "proto_project",
  "proto_revision",
] as const;
