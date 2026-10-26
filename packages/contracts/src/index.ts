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
 * 已签发会话的载荷。身份字段稳定；ver 用于撤销会话，缺少版本的旧 token 必须重新登录。
 * 改名、换邮箱都不必重签 token，
 * 所以 email 与 password_hash 一步都不许进来（进了就等于每次改名都要撤销重发）。
 *
 * sub / tid 在这里就判 uuid：守卫拿 tid 开租户上下文，非 uuid 连 set_config 之后的
 * 策略求值都过不去，那是在 SQL 里炸 22024，而不是在这儿安静地返回 401。
 */
export const JwtPayloadSchema = z.object({
  sub: z.string().uuid(),
  tid: z.string().uuid(),
  role: z.enum(ROLES),
  ver: z.number().int().nonnegative(),
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

// Console DTOs extend the existing tenant and task contracts.
export const PageQuerySchema = z.object({
  page: z.coerce.number().int().min(1).default(1),
  pageSize: z.coerce.number().int().min(1).max(100).default(20),
  q: z.string().trim().max(200).default(""),
});
export type PageQuery = z.infer<typeof PageQuerySchema>;
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

const ConsoleEmailSchema = z.string().trim().toLowerCase().max(254).pipe(z.email());
const DisplayNameSchema = z.string().trim().min(1).max(80);
const WorkspaceNameSchema = z.string().trim().min(1).max(100);
const NewPasswordSchema = z.string().min(MIN_PASSWORD_LENGTH).max(128);

export const MemberSchema = z.object({
  id: z.uuid(),
  email: z.email(),
  displayName: z.string(),
  role: z.enum(ROLES),
  disabledAt: z.string().nullable(),
  createdAt: z.string(),
});
export type Member = z.infer<typeof MemberSchema>;
export const CreateMemberBodySchema = z.object({
  email: ConsoleEmailSchema,
  displayName: DisplayNameSchema,
  password: NewPasswordSchema,
  role: z.enum(ROLES),
});
export type CreateMemberBody = z.infer<typeof CreateMemberBodySchema>;
export const UpdateMemberBodySchema = z
  .object({
    displayName: DisplayNameSchema.optional(),
    role: z.enum(ROLES).optional(),
    disabled: z.boolean().optional(),
  })
  .refine((v) => Object.values(v).some((value) => value !== undefined), {
    message: "At least one member field is required",
  });
export type UpdateMemberBody = z.infer<typeof UpdateMemberBodySchema>;
export const ProfileBodySchema = z.object({ displayName: DisplayNameSchema });
export type ProfileBody = z.infer<typeof ProfileBodySchema>;
export const PasswordBodySchema = z.object({
  currentPassword: z.string().min(1).max(128),
  newPassword: NewPasswordSchema,
});
export type PasswordBody = z.infer<typeof PasswordBodySchema>;
export const WorkspaceBodySchema = z.object({ name: WorkspaceNameSchema });
export type WorkspaceBody = z.infer<typeof WorkspaceBodySchema>;

export const FeedSourceBodySchema = z.object({
  name: WorkspaceNameSchema,
  url: z
    .string()
    .trim()
    .max(2048)
    .url()
    .refine(
      (value) => {
        if (!URL.canParse(value)) return false;
        const url = new URL(value);
        return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password;
      },
      { message: "Feed URL must use HTTP(S) without credentials" },
    ),
  frequencySec: z.number().int().min(60).max(86400),
  enabled: z.boolean().default(true),
});
export type FeedSourceBody = z.infer<typeof FeedSourceBodySchema>;
export const FeedSourceSchema = FeedSourceBodySchema.extend({
  id: z.uuid(),
  lastFetchAt: z.string().nullable(),
  createdAt: z.string(),
});
export type FeedSource = z.infer<typeof FeedSourceSchema>;

export const PlatformLoginBodySchema = z.object({
  email: ConsoleEmailSchema,
  password: z.string().min(1).max(128),
});
export type PlatformLoginBody = z.infer<typeof PlatformLoginBodySchema>;
export const PlatformUserSchema = z.object({
  id: z.uuid(),
  email: z.email(),
  displayName: z.string(),
});
export type PlatformUser = z.infer<typeof PlatformUserSchema>;
export const CreateTenantBodySchema = z.object({
  name: WorkspaceNameSchema,
  slug: z
    .string()
    .min(3)
    .max(63)
    .regex(/^[a-z0-9]+(?:-[a-z0-9]+)*$/),
  ownerEmail: ConsoleEmailSchema,
  ownerDisplayName: DisplayNameSchema,
  ownerPassword: NewPasswordSchema,
});
export type CreateTenantBody = z.infer<typeof CreateTenantBodySchema>;
export const TenantStatusBodySchema = z.object({ suspended: z.boolean() });
export type TenantStatusBody = z.infer<typeof TenantStatusBodySchema>;
export const PlatformTenantSchema = TenantSchema.extend({
  userCount: z.number().int().nonnegative(),
  taskCount: z.number().int().nonnegative(),
});
export type PlatformTenant = z.infer<typeof PlatformTenantSchema>;

export const CandidateSchema = z.object({
  id: z.uuid(),
  title: z.string(),
  summary: z.string(),
  sourceId: z.uuid().nullable(),
  sourceName: z.string().nullable(),
  heat: z.number(),
  rankPeak: z.number().nullable(),
  hits: z.number().int(),
  status: z.enum(CANDIDATE_STATUSES),
  firstSeenAt: z.string(),
  lastSeenAt: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type Candidate = z.infer<typeof CandidateSchema>;
export const TaskSummarySchema = TaskSchema.omit({ leaseOwner: true, heartbeat: true }).extend({
  title: z.string().nullable(),
});
export type TaskSummary = z.infer<typeof TaskSummarySchema>;

export const WorkspaceOverviewSchema = z.object({
  candidates: z.number().int().nonnegative(),
  tasks: z.number().int().nonnegative(),
  review: z.number().int().nonnegative(),
  published: z.number().int().nonnegative(),
  failed: z.number().int().nonnegative(),
  members: z.number().int().nonnegative(),
  recentTasks: z.array(TaskSummarySchema),
});
export type WorkspaceOverview = z.infer<typeof WorkspaceOverviewSchema>;

/** Public metadata only. A browser supplies taskId + kind, never a server path. */
export const ARTIFACT_KINDS = ["mp4", "contact-sheet", "aigc-sidecar"] as const;
export const ArtifactKindSchema = z.enum(ARTIFACT_KINDS);
export type ArtifactKind = z.infer<typeof ArtifactKindSchema>;
export const AssetSchema = z.object({
  id: z.uuid(),
  taskId: z.uuid(),
  title: z.string().nullable(),
  version: z.number().int().positive(),
  pack: z.string(),
  fingerprint: z.string(),
  mp4Sha256: z.string().nullable(),
  mp4Bytes: z.number().nullable(),
  width: z.number().nullable(),
  height: z.number().nullable(),
  fps: z.number().nullable(),
  durationMs: z.number().nullable(),
  hasMp4: z.boolean(),
  hasContactSheet: z.boolean(),
  hasAigcSidecar: z.boolean(),
  createdAt: z.string(),
});
export type Asset = z.infer<typeof AssetSchema>;
export const PublishSummarySchema = z.object({
  id: z.uuid(),
  taskId: z.uuid(),
  renderId: z.uuid(),
  title: z.string().nullable(),
  renderVersion: z.number().int().positive(),
  platform: z.string(),
  status: z.enum(PUBLISH_STATUSES),
  declaration: z.string(),
  postUrl: z.string().nullable(),
  verifiedAt: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type PublishSummary = z.infer<typeof PublishSummarySchema>;
export const UsageSummarySchema = z.object({
  kind: z.string(),
  unit: z.string(),
  quantity: z.string().regex(/^\d+(\.\d+)?$/),
});
export type UsageSummary = z.infer<typeof UsageSummarySchema>;
export const PlatformUsageSummarySchema = UsageSummarySchema.extend({
  tenantId: z.uuid(),
  tenantName: z.string(),
});
export type PlatformUsageSummary = z.infer<typeof PlatformUsageSummarySchema>;
export const AuditLogSchema = z.object({
  id: z.uuid(),
  actorId: z.uuid().nullable(),
  actorName: z.string().nullable(),
  action: z.string(),
  targetId: z.string().nullable(),
  detail: z.record(z.string(), z.unknown()),
  createdAt: z.string(),
});
export type AuditLog = z.infer<typeof AuditLogSchema>;
export const PlatformAuditLogSchema = AuditLogSchema.omit({
  actorId: true,
  actorName: true,
}).extend({
  operatorId: z.uuid().nullable(),
  operatorName: z.string().nullable(),
});
export type PlatformAuditLog = z.infer<typeof PlatformAuditLogSchema>;
