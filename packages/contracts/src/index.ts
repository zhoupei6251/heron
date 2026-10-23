import { z } from "zod";

export const STAGES = [
  "candidate",
  "claimed",
  "scripting",
  "factcheck",
  "rendering",
  "review",
  "approved",
  "publishing",
  "published",
  "failed",
] as const;

export type Stage = (typeof STAGES)[number];

export const GATES = ["dedupe", "contract", "visual", "compliance", "human"] as const;

export type GateName = (typeof GATES)[number];

/**
 * 台账行的状态（candidates.status）。与任务的 stage 是两回事：
 * stage 是「这台机器正在为它干活」，status 是「这条热点在台账里走到哪了」。
 */
export const CANDIDATE_STATUSES = [
  "candidate",
  "selected",
  "drafted",
  "rendered",
  "published",
  "rejected",
] as const;

export type CandidateStatus = (typeof CANDIDATE_STATUSES)[number];

/**
 * 五档角色，从高到低写死：**数组顺序就是权限高低**，domain 的 RANK 从它推出来。
 * rbac.test.ts 里有一条断言钉住这个顺序——改顺序的人必须知道自己改了什么。
 * 租户内成立：owner 在别的租户什么都不算（users 的唯一键是 (tenant_id, email)）。
 */
export const ROLES = ["owner", "admin", "reviewer", "operator", "viewer"] as const;

export type Role = (typeof ROLES)[number];

/** 发布单状态。failed 之外没有回头路：重试前先靠 verified_at 核实它是不是其实已经发出去了。 */
export const PUBLISH_STATUSES = ["pending", "sent", "confirmed", "failed"] as const;

export type PublishStatus = (typeof PUBLISH_STATUSES)[number];

export const RejectionSchema = z.object({
  ruleId: z.string().min(1),
  snippet: z.string(),
  why: z.string().min(1),
});

export type Rejection = z.infer<typeof RejectionSchema>;

export const GateVerdictSchema = z.object({
  gate: z.enum(GATES),
  passed: z.boolean(),
  rejections: z.array(RejectionSchema).default([]),
  /** 闸自己怎么判的一句话，落进 task_events.reason。占位闸必须在这里写明自己是占位。 */
  note: z.string().optional(),
});

export type GateVerdict = z.infer<typeof GateVerdictSchema>;

export const TaskSchema = z.object({
  id: z.string(),
  capability: z.string(),
  candidateId: z.string().nullable(),
  stage: z.enum(STAGES),
  leaseOwner: z.string().nullable(),
  heartbeat: z.string().nullable(),
  attempts: z.number().int().nonnegative(),
  /**
   * N5 的审计列：谁把这条候选变成生产任务的。认领动作不能只活在台账文案里。
   * v3 之后它是 users.id（uuid）；旧的 SQLite 层还在写自由文本，T8 切完就一致了。
   */
  claimedBy: z.string().uuid().nullable(),
  claimedAt: z.string().nullable(),
  errorReason: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});

export type Task = z.infer<typeof TaskSchema>;

/** 口令强度：太短的直接在边界拒，不进哈希层（一次 scrypt 约 50–100ms，别让垃圾输入白烧 CPU）。 */
export const MIN_PASSWORD_LENGTH = 12;

/**
 * 登录表单。
 * `tenant` 是工作空间 slug：邮箱只在租户内唯一（u_users_tenant_email，T2 的决定），
 * 所以「谁在登录」这句话必须有第二个坐标。它由客户端填（人知道自己家在哪），
 * 但 tid 由服务端查库后签进 token——客户端永远说不出自己属于哪个租户。
 * 邮箱在这里规范化，因为 ck_users_email_lower 只认小写。
 */
export const LoginBodySchema = z.object({
  tenant: z.string().min(1),
  email: z.email().toLowerCase(),
  password: z.string().min(MIN_PASSWORD_LENGTH),
});

export type LoginBody = z.infer<typeof LoginBodySchema>;

/** 会话确立后前端能拿到的「我是谁」：不含口令哈希，也不含 disabled_at——能拿到这张表就说明已经验过。 */
export const AuthUserSchema = z.object({
  id: z.string().uuid(),
  tenantId: z.string().uuid(),
  /**
   * 租户名与 slug 跟着身份一起发：顶栏要显示「在哪个工作空间、什么岗位」，
   * 而界面自己算不出 tenant——它只拿得到 token 里的 tid，那是个给人看的 uuid。
   * 这两列只用于展示，任何判据都不许读它们。
   */
  tenantName: z.string().min(1),
  tenantSlug: z.string().min(1),
  email: z.email(),
  displayName: z.string(),
  role: z.enum(ROLES),
});

export type AuthUser = z.infer<typeof AuthUserSchema>;

/**
 * 已签发会话的载荷。三个字段都是「不会轻易变」的：改名、换邮箱都不必重签 token，
 * 所以 email 与 password_hash 一步都不许进来（进了就等于每次改名都要撤销重发）。
 *
 * sub / tid 在这里就判 uuid：守卫拿 tid 开租户上下文，非 uuid 连 set_config 之后的
 * 策略求值都过不去，那是在 SQL 里炸 22024，而不是在这儿安静地返回 401。
 */
export const JwtPayloadSchema = z.object({
  sub: z.string().uuid(),
  tid: z.string().uuid(),
  role: z.enum(ROLES),
});

export type JwtPayload = z.infer<typeof JwtPayloadSchema>;

export const TenantSchema = z.object({
  id: z.string().uuid(),
  name: z.string().min(1),
  slug: z.string().min(1),
  /** 停租是软关闭：数据不许物理删，「当时这个租户还能不能发」必须可回溯。 */
  suspendedAt: z.string().nullable(),
  createdAt: z.string(),
});

export type Tenant = z.infer<typeof TenantSchema>;

/** 用量台账的单位与种类名单。 */
export const USAGE_UNITS = ["tokens", "count", "seconds"] as const;

export type UsageUnit = (typeof USAGE_UNITS)[number];

export const USAGE_KINDS = [
  "llm_tokens_in",
  "llm_tokens_out",
  "image_gen",
  "render_seconds",
  "publish",
] as const;

export type UsageKind = (typeof USAGE_KINDS)[number];

/**
 * kind→unit 的配对表只留这一份：pg 的 `ck_usage_records_kind_unit_pair` 与下面的 refine 都从它生成。
 * 两处各写一遍 CASE 迟早漂移，而漂移的症状是「接口拒了、库里却收下了」那种对不上账。
 */
export const USAGE_KIND_UNITS: Record<UsageKind, UsageUnit> = {
  llm_tokens_in: "tokens",
  llm_tokens_out: "tokens",
  image_gen: "count",
  render_seconds: "seconds",
  publish: "count",
};

export const UsageRecordSchema = z
  .object({
    tenantId: z.string().uuid(),
    taskId: z.string().uuid().nullable(),
    kind: z.enum(USAGE_KINDS),
    /** numeric 列的形状就是十进制字符串：浮点进账本，每一次四舍五入都是一笔对不平的账。 */
    quantity: z.string().regex(/^\d+(\.\d+)?$/),
    unit: z.enum(USAGE_UNITS),
    detail: z.record(z.string(), z.unknown()).default({}),
    recordedAt: z.string(),
  })
  // kind 与 unit 是两个独立字段，非法组合在类型层看不出来，只能在判据层拦
  .refine((r) => USAGE_KIND_UNITS[r.kind] === r.unit, {
    message: "kind 与 unit 不配对",
    path: ["unit"],
  });

export type UsageRecord = z.infer<typeof UsageRecordSchema>;

export const RetentionBodySchema = z.object({
  days: z.number().int().positive().max(3650),
});

export type RetentionBody = z.infer<typeof RetentionBodySchema>;

export const CreateFakeTaskInputSchema = z.object({
  capability: z.string().min(1),
  title: z.string().min(1),
});

export type CreateFakeTaskInput = z.infer<typeof CreateFakeTaskInputSchema>;

/**
 * 待审队列的一条：reviewer 要回答的问题是「哪条任务、哪一版渲染、联络表指纹是什么」。
 * 指纹随队列发到界面，确认时原样提交回来——服务端拿它与**当时**的当前版本比对，
 * 对不上就拒，这是「人审 A、系统发 B」在接口形状上的拦截位置。
 */
export const ReviewItemSchema = z.object({
  taskId: z.string().uuid(),
  /** 候选标题；任务可以不带候选（candidate_id 为空），界面要能显示这种行。 */
  title: z.string().nullable(),
  /** 没有渲染版本时为 null：这一行没法确认，界面显示原因而不是藏起来。版本号从 1 起。 */
  renderVersion: z.number().int().positive().nullable(),
  contactSheetFingerprint: z.string().nullable(),
  contactSheetPath: z.string().nullable(),
  mp4Path: z.string().nullable(),
});

export type ReviewItem = z.infer<typeof ReviewItemSchema>;

/**
 * 确认提交的只有指纹。确认人是谁由会话说了算（cookie），时间由服务端说了算（nowIso）——
 * 这两样放进请求体就是给「替别人确认、倒填时间」开了口子。
 */
export const ConfirmBodySchema = z.object({
  contactSheetFingerprint: z.string().min(1),
});

export type ConfirmBody = z.infer<typeof ConfirmBodySchema>;

export const TaskEventSchema = z.object({
  type: z.literal("task.updated"),
  taskId: z.string(),
  from: z.enum(STAGES),
  to: z.enum(STAGES),
  /** 台账上那次转移的说明：闸放行也写，所以不叫 errorReason。失败详情在 Task.errorReason。 */
  reason: z.string().nullable().default(null),
  at: z.string(),
});

export type TaskEvent = z.infer<typeof TaskEventSchema>;

export const GateLocationSchema = z.object({
  gate: z.enum(GATES),
  satisfied: z.boolean(),
  detail: z.string(),
});

export type GateLocation = z.infer<typeof GateLocationSchema>;

/** GET /api/gates 的响应：前端拿它决定「发布」「确认」按钮置不置灰。 */
export const GateStateSchema = z.object({
  open: z.boolean(),
  blockers: z.array(z.string()),
  locations: z.array(GateLocationSchema),
});

export type GateState = z.infer<typeof GateStateSchema>;

export const CapabilityManifestSchema = z.object({
  name: z.string().min(1),
  pipeline: z.array(z.enum(STAGES)).min(1),
  gates: z.array(z.enum(GATES)).default([]),
  layouts: z.array(z.string()).default([]),
  promptKeys: z.array(z.string()).default([]),
});

export type CapabilityManifest = z.infer<typeof CapabilityManifestSchema>;
