import { z } from "zod";

/**
 * 主键与所有实体 id 都是标准 64 位雪花 id，十进制字符串。
 *
 * 为什么是字符串而不是 number：雪花 id 量级在 2^53 以上，JS number 会丢精度——
 * 两个不同的 id 会变成同一个数，查询就拿错了行。库里存 `numeric(19,0)`，
 * node-postgres 原样读成字符串，于是 JS 侧与过去的 uuid 一样是不透明字符串。
 *
 * 格式规则只此一份：packages/db 的生成器 import 同一个 pattern 自检，两边各写一遍正则迟早会飘。
 */
export const SNOWFLAKE_ID_PATTERN = /^[0-9]{1,19}$/;

/** 2^63-1：数据库 numeric(19,0) 的上界，也是雪花 id 的取值上界。 */
const SNOWFLAKE_ID_MAX = 9223372036854775807n;

export const snowflakeIdSchema = z
  .string()
  .regex(SNOWFLAKE_ID_PATTERN, "id 必须是十进制数字串")
  .refine((value) => {
    // 正则失败时 refine 照样会跑，所以 BigInt 要自己兜住：这里抛异常会把
    // 「校验失败」变成「进程崩了」——契约层不能因为一个脏字段炸掉。
    try {
      const parsed = BigInt(value);
      return parsed > 0n && parsed <= SNOWFLAKE_ID_MAX;
    } catch {
      return false;
    }
  }, "雪花 id 必须在 1..2^63-1 之间");

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
 * 原型工坊的状态机。与新闻线是两套：同一条任务表装不下「需求 → .rp」这种产物与闸，
 * 共用只会让两条线的失败原因互相污染。
 */
export const PROTO_STAGES = [
  "draft",
  "scripting",
  "compiling",
  "review",
  "approved",
  "delivered",
  "failed",
] as const;

export type ProtoStage = (typeof PROTO_STAGES)[number];

export const PROTO_TARGETS = ["web", "mobile"] as const;
export type ProtoTarget = (typeof PROTO_TARGETS)[number];

/** 引擎内置主题。target 与 theme 的搭配由接口层校验，存储层只认名单。 */
export const PROTO_THEMES = [
  "enterprise-light",
  "modern-dark",
  "cobalt-pro",
  "mobile-lowfi",
  "mobile-hifi",
] as const;
export type ProtoTheme = (typeof PROTO_THEMES)[number];

/** 失败分类：谁该被修，界面据此说人话（模型 / 引擎 / 契约或视觉闸）。 */
export const PROTO_FAILURE_KINDS = ["model", "contract", "visual", "engine"] as const;
export type ProtoFailureKind = (typeof PROTO_FAILURE_KINDS)[number];

export const PROTO_REQUIREMENT_MAX = 8000;

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
   * 它是 users.id（雪花 id）。
   */
  claimedBy: snowflakeIdSchema.nullable(),
  claimedAt: z.string().nullable(),
  errorReason: z.string().nullable(),
  createTime: z.string(),
  updateTime: z.string(),
});

export type Task = z.infer<typeof TaskSchema>;

/** 口令强度：太短的直接在边界拒，不进哈希层（一次 scrypt 约 50–100ms，别让垃圾输入白烧 CPU）。 */
export const MIN_PASSWORD_LENGTH = 10;

/** 平台账号的用户名：小写字母或数字开头，可含 . _ -；登录与引导共用这一份判据。 */
export const PlatformUsernameSchema = z
  .string()
  .trim()
  .toLowerCase()
  .min(2)
  .max(64)
  .regex(/^[a-z0-9][a-z0-9._-]*$/);

/**
 * 界面不再问工作空间：带 `@` 的是租户邮箱，不带的是平台运营用户名，形状即身份。
 * `tenant` 仍接受（脚本与旧链接显式指定空间），但空值不再等于「一定是平台账号」。
 * 租户邮箱不是全局身份，跨空间的歧义由服务层判，不在这里放宽。
 */
export const LoginBodySchema = z
  .object({
    tenant: z
      .string()
      .trim()
      .toLowerCase()
      .max(63)
      .regex(/^(?:[a-z0-9]+(?:-[a-z0-9]+)*)?$/)
      .default(""),
    identifier: z.string().trim().toLowerCase().min(1).max(254),
    password: z.string().min(MIN_PASSWORD_LENGTH).max(128),
  })
  .superRefine((value, ctx) => {
    const email = z.email().safeParse(value.identifier).success;
    const username = PlatformUsernameSchema.safeParse(value.identifier).success;
    const valid = value.tenant === "" ? email || username : email;
    if (!valid)
      ctx.addIssue({
        code: "custom",
        path: ["identifier"],
        message: value.tenant === "" ? "Invalid username or email" : "Invalid email",
      });
  });

export type LoginBody = z.infer<typeof LoginBodySchema>;
/** 界面发出去的那一份：tenant 有默认值，所以可以不填（服务端按账号形状定 realm）。 */
export type LoginBodyInput = z.input<typeof LoginBodySchema>;

/** 会话确立后前端能拿到的「我是谁」：不含口令哈希，也不含 disabled_at——能拿到这张表就说明已经验过。 */
export const AuthUserSchema = z.object({
  id: snowflakeIdSchema,
  tenantId: snowflakeIdSchema,
  /**
   * 租户名与 slug 跟着身份一起发：顶栏要显示「在哪个工作空间、什么岗位」，
   * 而界面自己算不出 tenant——它只拿得到 token 里的 tid，那是个给人看的租户 id。
   * 这两列只用于展示，任何判据都不许读它们。
   */
  tenantName: z.string().min(1),
  tenantSlug: z.string().min(1),
  /** 展示用，不参与判断；平台运营查看时这一格是运营账号的用户名，不是邮箱。 */
  email: z.string().min(1).max(254),
  displayName: z.string(),
  role: z.enum(ROLES),
  /** 缺格 = 空间成员。platform-view 由服务端签发，接口侧只读（见 api 的 RolesGuard）。 */
  scope: z.enum(["member", "platform-view"]).optional(),
  /** 这次请求是哪个登录会话发出的；旧票与新签的平台票都没有这一格。 */
  sessionId: snowflakeIdSchema.optional(),
});

export type AuthUser = z.infer<typeof AuthUserSchema>;

/**
 * 已签发会话的载荷。身份字段稳定；ver 用于撤销会话，缺少版本的旧 token 必须重新登录。
 * 改名、换邮箱都不必重签 token，
 * 所以 email 与 password_hash 一步都不许进来（进了就等于每次改名都要撤销重发）。
 *
 * sub / tid 在这里就判雪花 id：守卫拿 tid 开租户上下文，非数字串连 set_config 之后的
 * 策略求值都过不去，那是在 SQL 里炸 22024，而不是在这儿安静地返回 401。
 * act=platform 的票属于平台运营查看某个空间：audience 与租户票相同（所以 tid 仍旧是
 * 服务端签进去的唯一租户坐标），但 sub 是 platform_operators.id，写动作在闸口被拒。
 */
export const JwtPayloadSchema = z.object({
  sub: snowflakeIdSchema,
  tid: snowflakeIdSchema,
  role: z.enum(ROLES),
  ver: z.number().int().nonnegative(),
  act: z.literal("platform").optional(),
  /**
   * 登录会话 id（sessions.id）。ver 只能整户撤销，逐条强退得知道「这是哪一条」。
   * 可缺：这个字段上线前签出的票没有它，人被踢的判据仍旧看 ver。
   */
  sid: snowflakeIdSchema.optional(),
});

export type JwtPayload = z.infer<typeof JwtPayloadSchema>;

/**
 * 档位只差额度，不差功能：功能分档会让以后每条新功能都得先回答「哪一档能用」，
 * 而自托管场景里没人愿意为了一个开关去维护第二份代码路径。
 */
export const PLAN_IDS = ["free", "standard", "enterprise"] as const;

export const PlanSchema = z.enum(PLAN_IDS);

export type PlanId = z.infer<typeof PlanSchema>;

export interface PlanLimits {
  seats: number;
  rendersPerMonth: number;
  publishesPerMonth: number;
}

/**
 * 额度的唯一定义处：worker 的拦、控制台的显示、审计记录里的数字都读这一份。
 * 三处各写一遍的症状是「界面允许、worker 拒绝」——那种不一致没人会当成额度问题去查。
 */
export const PLAN_LIMITS: Record<PlanId, PlanLimits> = {
  free: { seats: 3, rendersPerMonth: 100, publishesPerMonth: 30 },
  standard: { seats: 20, rendersPerMonth: 1_000, publishesPerMonth: 300 },
  enterprise: { seats: 100, rendersPerMonth: 10_000, publishesPerMonth: 3_000 },
};

export const PlanLimitsSchema = z.object({
  seats: z.number().int().nonnegative(),
  rendersPerMonth: z.number().int().nonnegative(),
  publishesPerMonth: z.number().int().nonnegative(),
});

/** 当月用量：座位是当前值，渲染与发布按 UTC 自然月计。 */
export const PlanUsageSchema = z.object({
  seats: z.number().int().nonnegative(),
  renders: z.number().int().nonnegative(),
  publishes: z.number().int().nonnegative(),
});

/** 控制台据此显示「本月发布 30/30」并置灰超额的动作。 */
export const QuotaStatusSchema = z.object({
  plan: PlanSchema,
  limits: PlanLimitsSchema,
  usage: PlanUsageSchema,
});
export type QuotaStatus = z.infer<typeof QuotaStatusSchema>;

export const TenantSchema = z.object({
  id: snowflakeIdSchema,
  name: z.string().min(1),
  slug: z.string().min(1),
  plan: PlanSchema,
  /** 停租是软关闭：数据不许物理删，「当时这个租户还能不能发」必须可回溯。 */
  suspendedAt: z.string().nullable(),
  createTime: z.string(),
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
    tenantId: snowflakeIdSchema,
    taskId: snowflakeIdSchema.nullable(),
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

/**
 * 写稿模型的页面配置。apiKey 留空表示「不改」——界面只回显「已配置」，不回显密钥。
 * 地址走 http/https 且不许带内嵌凭据：worker 会去请求它，凭据只该在 Authorization 头里。
 */
export const ModelSettingsBodySchema = z.object({
  baseUrl: z
    .string()
    .trim()
    .max(2048)
    .url()
    .refine(
      (value) => {
        const url = new URL(value);
        return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password;
      },
      { message: "模型地址必须是不带凭据的 HTTP(S) URL" },
    )
    // 存归一形：请求拼 `${baseUrl}/chat/completions`，结尾多一个斜杠就多一个空段
    .transform((value) => value.replace(/\/+$/, "")),
  model: z.string().trim().min(1).max(200),
  apiKey: z.string().trim().max(500).optional(),
});

export type ModelSettingsBody = z.infer<typeof ModelSettingsBodySchema>;

/** 手工建任务只收标题：capability 由服务端定（当前只有 news），请求体里没有第二个能指定它的位置。 */
export const CreateTaskInputSchema = z.object({
  title: z.string().min(1),
});

export type CreateTaskInput = z.infer<typeof CreateTaskInputSchema>;

/**
 * 待审队列的一条：reviewer 要回答的问题是「哪条任务、哪一版渲染、联络表指纹是什么」。
 * 指纹随队列发到界面，确认时原样提交回来——服务端拿它与**当时**的当前版本比对，
 * 对不上就拒，这是「人审 A、系统发 B」在接口形状上的拦截位置。
 */
export const ReviewItemSchema = z.object({
  taskId: snowflakeIdSchema,
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

/**
 * 写稿产出的最小形状。版式还没定，所以这里只钉「不钉就会静默丢内容」的那几条：
 * body 为空配音会停机；屏句可选，但和 title 同句会让版式把同一句并排打两遍。
 * 版式相关的 8 条（落点、条目容量、变量填得出）等版式定稿后再加，别在这里提前发明。
 */
export const ScriptSceneSchema = z.object({
  /** 口播正文：配音读它。 */
  body: z.string().trim().min(1).max(200),
  /** 屏句：可选，出现时必须非空。 */
  onscreen: z.string().trim().min(1).max(40).optional(),
});

export const NewsScriptSchema = z.object({
  title: z.string().trim().min(1).max(30),
  scenes: z.array(ScriptSceneSchema).min(2).max(8),
});

export type NewsScript = z.infer<typeof NewsScriptSchema>;

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
  id: snowflakeIdSchema,
  email: z.email(),
  displayName: z.string(),
  role: z.enum(ROLES),
  disabledAt: z.string().nullable(),
  createTime: z.string(),
});
export type Member = z.infer<typeof MemberSchema>;

/**
 * 一条在线会话。没有「当前密码可改」这种自我声明：self / current 都由服务端算——
 * 界面上的「这是我自己」若由前端比对，伪造一个 id 就能把别人的会话标成自己的。
 */
export const SessionSchema = z.object({
  id: snowflakeIdSchema,
  userId: snowflakeIdSchema,
  userName: z.string(),
  userEmail: z.string(),
  userRole: z.enum(ROLES),
  createTime: z.string(),
  lastSeenAt: z.string(),
  userAgent: z.string().nullable(),
  /** 就是发起这次请求的那条：强退自己等于退出登录，按钮要区别对待。 */
  current: z.boolean(),
  /** 属于当前登录的人。没有 session.manage 的人只看得到自己的，所以这条只影响措辞。 */
  self: z.boolean(),
});
export type Session = z.infer<typeof SessionSchema>;
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
  id: snowflakeIdSchema,
  lastFetchAt: z.string().nullable(),
  lastAttemptAt: z.string().nullable(),
  lastError: z.string().nullable(),
  createTime: z.string(),
});
export type FeedSource = z.infer<typeof FeedSourceSchema>;

export const PlatformLoginBodySchema = z.object({
  username: PlatformUsernameSchema,
  password: z.string().min(MIN_PASSWORD_LENGTH).max(128),
});
export type PlatformLoginBody = z.infer<typeof PlatformLoginBodySchema>;
export const PlatformUserSchema = z.object({
  id: snowflakeIdSchema,
  username: PlatformUsernameSchema,
  displayName: z.string(),
});
export type PlatformUser = z.infer<typeof PlatformUserSchema>;
/** 平台运营进入某个工作空间查看。只收 id：空间坐标由服务端回库确认，不接名字与 slug。 */
export const WorkspaceViewBodySchema = z.object({ tenantId: snowflakeIdSchema });
export type WorkspaceViewBody = z.infer<typeof WorkspaceViewBodySchema>;

/**
 * 租户台的页面清单。菜单配置只能在这些页面里挑：后端可以决定「放哪、叫什么、给谁看」，
 * 但决定不了「有哪些页面」——页面组件仍由前端白名单注册，配置写不进任意路径。
 */
export const TENANT_MENU_IDS = [
  "overview",
  "candidates",
  "production",
  "review",
  "publish",
  "library",
  "proto",
  "members",
  "roles",
  "feeds",
  "settings",
  "usage",
  "audit",
  "sessions",
  "profile",
] as const;
/** 平台台的页面固定，不参与菜单配置：改坏它的人可能连配置页都进不去。 */
export const PLATFORM_MENU_IDS = [
  "platform-overview",
  "tenants",
  "platform-settings",
  "platform-menus",
  "platform-usage",
  "platform-audit",
  "health",
] as const;
export const CONSOLE_MENU_IDS = [...TENANT_MENU_IDS, ...PLATFORM_MENU_IDS] as const;

export type TenantMenuId = (typeof TENANT_MENU_IDS)[number];
export type ConsoleMenuId = (typeof CONSOLE_MENU_IDS)[number];

/** 路径是代码资产，不是配置项：菜单配置只存 id，服务端按这张表回填路径。 */
export const MENU_PAGES: Record<ConsoleMenuId, { path: string; label: string }> = {
  overview: { path: "/overview", label: "工作概览" },
  candidates: { path: "/candidates", label: "候选管理" },
  production: { path: "/production", label: "生产任务" },
  review: { path: "/review", label: "审片工作台" },
  publish: { path: "/publish", label: "发布记录" },
  library: { path: "/library", label: "成片库" },
  proto: { path: "/proto", label: "原型工坊" },
  members: { path: "/members", label: "成员管理" },
  roles: { path: "/roles", label: "角色与权限" },
  feeds: { path: "/feeds", label: "数据源配置" },
  settings: { path: "/settings", label: "工作空间设置" },
  usage: { path: "/usage", label: "用量统计" },
  audit: { path: "/audit", label: "操作审计" },
  sessions: { path: "/sessions", label: "在线会话" },
  profile: { path: "/profile", label: "个人与安全" },
  "platform-overview": { path: "/platform", label: "运营概览" },
  tenants: { path: "/platform/tenants", label: "租户管理" },
  "platform-settings": { path: "/platform/settings", label: "平台设置" },
  "platform-menus": { path: "/platform/menus", label: "菜单配置" },
  "platform-usage": { path: "/platform/usage", label: "全局用量" },
  "platform-audit": { path: "/platform/audit", label: "平台审计" },
  health: { path: "/platform/health", label: "服务健康" },
};

/** 模块图标是展示资产，只认这一份名单；配置存的是键，不是组件。 */
export const MENU_GROUP_ICONS = [
  "newspaper",
  "layout",
  "settings",
  "monitor",
  "operations",
  "configuration",
  "folder",
] as const;
export type MenuGroupIcon = (typeof MENU_GROUP_ICONS)[number];

export const ConsoleMenuGroupSchema = z.object({
  key: z.string().regex(/^[a-z][a-z0-9-]{0,31}$/, "模块标识只能是小写字母、数字与连字符"),
  label: z.string().trim().min(1).max(24),
  icon: z.enum(MENU_GROUP_ICONS),
});
export type ConsoleGroup = z.infer<typeof ConsoleMenuGroupSchema>;

export const ConsoleMenuSchema = z.object({
  id: z.enum(CONSOLE_MENU_IDS),
  path: z.string().regex(/^\/(?!\/)[a-z/-]+$/),
  label: z.string().min(1),
  group: z.string().min(1),
  /** 仍注册路由，只是不出现在侧栏与快捷导航里（个人设置走右上角入口）。 */
  hidden: z.boolean(),
});
export type ConsoleMenu = z.infer<typeof ConsoleMenuSchema>;
export const ConsoleAccessSchema = z.object({
  roles: z.array(z.string()),
  permissions: z.array(z.string()),
  groups: z.array(ConsoleMenuGroupSchema),
  menus: z.array(ConsoleMenuSchema),
});
export type ConsoleAccess = z.infer<typeof ConsoleAccessSchema>;

/**
 * 菜单配置：只收「页面 id + 文案 + 显隐 + 角色」，不收路径与组件。
 *
 * `roles` 是**收窄**，不是授权：空数组表示所有通过动作门槛的角色都能看；
 * 写了名单也只能写该页面动作门槛之上的人，服务端会再判一次。
 */
export const ConsoleMenuItemConfigSchema = z.object({
  id: z.enum(TENANT_MENU_IDS),
  label: z.string().trim().min(1).max(24),
  hidden: z.boolean(),
  roles: z.array(z.enum(ROLES)),
});
export type ConsoleMenuItemConfig = z.infer<typeof ConsoleMenuItemConfigSchema>;
export const ConsoleMenuGroupConfigSchema = ConsoleMenuGroupSchema.extend({
  items: z.array(ConsoleMenuItemConfigSchema).max(30),
});
export type ConsoleMenuGroupConfig = z.infer<typeof ConsoleMenuGroupConfigSchema>;
export const ConsoleMenuConfigSchema = z
  .object({ groups: z.array(ConsoleMenuGroupConfigSchema).max(12) })
  .superRefine((value, ctx) => {
    const groupKeys = new Set<string>();
    const pageIds = new Set<string>();
    for (const group of value.groups) {
      if (groupKeys.has(group.key)) {
        ctx.addIssue({ code: "custom", message: `模块标识重复：${group.key}` });
      }
      groupKeys.add(group.key);
      for (const item of group.items) {
        if (pageIds.has(item.id)) {
          ctx.addIssue({ code: "custom", message: `页面重复出现：${item.id}` });
        }
        pageIds.add(item.id);
        if (new Set(item.roles).size !== item.roles.length) {
          ctx.addIssue({ code: "custom", message: `页面 ${item.id} 的可见角色重复` });
        }
      }
    }
  });
export type ConsoleMenuConfig = z.infer<typeof ConsoleMenuConfigSchema>;
export const ConsoleMenuConfigViewSchema = z.object({
  config: ConsoleMenuConfigSchema,
  isDefault: z.boolean(),
});
export type ConsoleMenuConfigView = z.infer<typeof ConsoleMenuConfigViewSchema>;

/**
 * 产品默认菜单：两条业务线各成一个大模块，其余是系统管理与系统监控。
 * 个人设置不进侧栏（hidden），走右上角头像入口——和 RuoYi 把个人中心放顶栏同一取舍。
 */
export const DEFAULT_CONSOLE_MENU_CONFIG: ConsoleMenuConfig = {
  groups: [
    {
      key: "news",
      label: "新闻生视频",
      icon: "newspaper",
      items: (
        ["overview", "candidates", "production", "review", "publish", "library"] as const
      ).map((id) => ({ id, label: MENU_PAGES[id].label, hidden: false, roles: [] })),
    },
    {
      key: "proto",
      label: "产品原型",
      icon: "layout",
      items: [{ id: "proto", label: MENU_PAGES.proto.label, hidden: false, roles: [] }],
    },
    {
      key: "system",
      label: "系统管理",
      icon: "settings",
      items: (["members", "roles", "feeds", "settings"] as const).map((id) => ({
        id,
        label: MENU_PAGES[id].label,
        hidden: false,
        roles: [],
      })),
    },
    {
      key: "monitor",
      label: "系统监控",
      icon: "monitor",
      items: [
        { id: "usage", label: MENU_PAGES.usage.label, hidden: false, roles: [] },
        { id: "audit", label: MENU_PAGES.audit.label, hidden: false, roles: [] },
        { id: "sessions", label: MENU_PAGES.sessions.label, hidden: false, roles: [] },
        { id: "profile", label: MENU_PAGES.profile.label, hidden: true, roles: [] },
      ],
    },
  ],
};
export const ConsoleSessionSchema = z.discriminatedUnion("kind", [
  ConsoleAccessSchema.extend({ kind: z.literal("tenant"), user: AuthUserSchema }),
  ConsoleAccessSchema.extend({ kind: z.literal("platform"), user: PlatformUserSchema }),
]);
export type ConsoleSession = z.infer<typeof ConsoleSessionSchema>;

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
/** 改档位只有平台侧能做：额度是商务决定，租户自己改等于自己给自己加额度。 */
export const TenantPlanBodySchema = z.object({ plan: PlanSchema });
export type TenantPlanBody = z.infer<typeof TenantPlanBodySchema>;
export const PlatformTenantSchema = TenantSchema.extend({
  userCount: z.number().int().nonnegative(),
  taskCount: z.number().int().nonnegative(),
});
export type PlatformTenant = z.infer<typeof PlatformTenantSchema>;

/** 七牛云 AI 推理的 OpenAI 兼容入口。换厂商在平台设置里改，或覆盖 HERON_MODEL_BASE_URL。 */
export const DEFAULT_MODEL_BASE_URL = "https://api.qnaigc.com/v1";

/**
 * 平台级默认值：一台机器一份，租户自己没配时才轮到它。
 *
 * 这四项**没有一项是密钥**：模型密钥只活在租户设置或环境变量里，进表就等于进了备份与审计导出。
 * 键名、类型、兜底值也只有这一份——api 的读写、worker 的解析链、页面的表单都读它。
 */
export const PLATFORM_SETTING_KEYS = [
  "default_model_base_url",
  "default_model_name",
  "default_retention_days",
  "default_plan",
] as const;

export type PlatformSettingKey = (typeof PLATFORM_SETTING_KEYS)[number];

const PlatformModelUrl = z
  .string()
  .trim()
  .max(2048)
  .url()
  .refine(
    (value) => {
      const url = new URL(value);
      return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password;
    },
    { message: "模型地址必须是不带凭据的 HTTP(S) URL" },
  )
  // 与租户设置同一把尺子：请求拼 `${baseUrl}/chat/completions`，结尾多一个斜杠就多一个空段
  .transform((value) => value.replace(/\/+$/, ""));

const PositiveDays = z
  .string()
  .trim()
  .regex(/^\d+$/, { message: "只能写正整数天数" })
  .refine((value) => Number(value) >= 1 && Number(value) <= 3650, {
    message: "在 1–3650 天之间",
  })
  .transform((value) => String(Number(value)));

export interface PlatformSettingDef {
  label: string;
  hint: string;
  /** 写入前用它校验；读回来的值也过一遍，坏数据按「未配置」处理，不让流水线因为一行脏数据崩掉。 */
  schema: z.ZodType<string, string>;
  /** 库里没有这一行、或值不合法时用它。空串表示「没有默认值」，缺了就得租户自己配。 */
  fallback: string;
}

export const PLATFORM_SETTING_DEFS: Record<PlatformSettingKey, PlatformSettingDef> = {
  default_model_base_url: {
    label: "默认模型地址",
    hint: "OpenAI 兼容入口。租户在「工作空间设置」里自己填了地址，就以租户的为准。",
    schema: PlatformModelUrl,
    fallback: DEFAULT_MODEL_BASE_URL,
  },
  default_model_name: {
    label: "默认模型名",
    hint: "租户没配模型时用它写稿。七牛在「AI 大模型广场」复制完整 id。",
    schema: z.string().trim().min(1).max(200),
    fallback: "",
  },
  default_retention_days: {
    label: "默认成片保留天数",
    hint: "租户没设保留天数时用它；到期后成片目录与桶里的对象一起删。",
    schema: PositiveDays,
    fallback: "30",
  },
  default_plan: {
    label: "新空间默认档位",
    hint: "平台台开通新工作空间时用它开局；之后按家改档。",
    schema: PlanSchema,
    fallback: "free",
  },
};

export function isPlatformSettingKey(value: string): value is PlatformSettingKey {
  return (PLATFORM_SETTING_KEYS as readonly string[]).includes(value);
}

/** 一项的当前值与来源：`isDefault` 为真说明库里没有这一行（或值不合法），显示的是兜底值。 */
export const PlatformSettingItemSchema = z.object({
  key: z.enum(PLATFORM_SETTING_KEYS),
  value: z.string(),
  isDefault: z.boolean(),
  updateTime: z.string().nullable(),
  updateBy: z.string().nullable(),
});
export type PlatformSettingItem = z.infer<typeof PlatformSettingItemSchema>;

export const PlatformSettingsSchema = z.object({ items: z.array(PlatformSettingItemSchema) });
export type PlatformSettings = z.infer<typeof PlatformSettingsSchema>;

/**
 * 只收改动过的那几项；空串表示「恢复默认」（删掉这一行）。
 * 每项的值在这里就按它自己的 schema 判一次：格式不对当场 400 并点名是哪一项，
 * 而不是写进库、等 worker 下次写稿时才炸。
 */
export const PlatformSettingsBodySchema = z
  .object({ values: z.partialRecord(z.enum(PLATFORM_SETTING_KEYS), z.string().max(2048)) })
  .superRefine((body, ctx) => {
    for (const [key, value] of Object.entries(body.values)) {
      if (!isPlatformSettingKey(key)) continue;
      const def = PLATFORM_SETTING_DEFS[key];
      if (value.trim() === "") continue;
      const parsed = def.schema.safeParse(value);
      if (!parsed.success) {
        ctx.addIssue({
          code: "custom",
          path: ["values", key],
          message: `${def.label}${parsed.error.issues[0]?.message ?? "格式不对"}`,
        });
      }
    }
  });
export type PlatformSettingsBody = z.infer<typeof PlatformSettingsBodySchema>;

export const CandidateSchema = z.object({
  id: snowflakeIdSchema,
  title: z.string(),
  summary: z.string(),
  sourceId: snowflakeIdSchema.nullable(),
  sourceName: z.string().nullable(),
  heat: z.number(),
  rankPeak: z.number().nullable(),
  hits: z.number().int(),
  status: z.enum(CANDIDATE_STATUSES),
  firstSeenAt: z.string(),
  lastSeenAt: z.string(),
  createTime: z.string(),
  updateTime: z.string(),
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
  id: snowflakeIdSchema,
  taskId: snowflakeIdSchema,
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
  createTime: z.string(),
});
export type Asset = z.infer<typeof AssetSchema>;
export const PublishSummarySchema = z.object({
  id: snowflakeIdSchema,
  taskId: snowflakeIdSchema,
  renderId: snowflakeIdSchema,
  title: z.string().nullable(),
  renderVersion: z.number().int().positive(),
  platform: z.string(),
  status: z.enum(PUBLISH_STATUSES),
  declaration: z.string(),
  postUrl: z.string().nullable(),
  verifiedAt: z.string().nullable(),
  createTime: z.string(),
  updateTime: z.string(),
});
export type PublishSummary = z.infer<typeof PublishSummarySchema>;
export const UsageSummarySchema = z.object({
  kind: z.string(),
  unit: z.string(),
  quantity: z.string().regex(/^\d+(\.\d+)?$/),
});
export type UsageSummary = z.infer<typeof UsageSummarySchema>;
export const PlatformUsageSummarySchema = UsageSummarySchema.extend({
  tenantId: snowflakeIdSchema,
  tenantName: z.string(),
});
export type PlatformUsageSummary = z.infer<typeof PlatformUsageSummarySchema>;
export const AuditLogSchema = z.object({
  id: snowflakeIdSchema,
  actorId: snowflakeIdSchema.nullable(),
  actorName: z.string().nullable(),
  action: z.string(),
  targetId: z.string().nullable(),
  detail: z.record(z.string(), z.unknown()),
  createTime: z.string(),
});
export type AuditLog = z.infer<typeof AuditLogSchema>;
export const PlatformAuditLogSchema = AuditLogSchema.omit({
  actorId: true,
  actorName: true,
}).extend({
  operatorId: snowflakeIdSchema.nullable(),
  operatorName: z.string().nullable(),
});
export type PlatformAuditLog = z.infer<typeof PlatformAuditLogSchema>;

/**
 * 原型工坊。需求原文只在详情里发：列表每页 20 条 × 8000 字会把表格接口变成下载接口。
 * 前端上限用同一个 PROTO_REQUIREMENT_MAX，两个数字各写一遍迟早漂移。
 */
export const CreateProtoBodySchema = z
  .object({
    title: WorkspaceNameSchema,
    requirement: z.string().trim().min(1, "需求描述不能为空").max(PROTO_REQUIREMENT_MAX),
    target: z.enum(PROTO_TARGETS),
    theme: z.enum(PROTO_THEMES),
  })
  .refine(
    (value) =>
      value.target === "mobile"
        ? value.theme.startsWith("mobile-")
        : !value.theme.startsWith("mobile-"),
    { message: "画布与主题不匹配：移动端用 mobile-*，Web 用其余主题", path: ["theme"] },
  );
export type CreateProtoBody = z.infer<typeof CreateProtoBodySchema>;

export const ProtoProjectSummarySchema = z.object({
  id: snowflakeIdSchema,
  title: z.string(),
  target: z.enum(PROTO_TARGETS),
  theme: z.enum(PROTO_THEMES),
  stage: z.enum(PROTO_STAGES),
  errorReason: z.string().nullable(),
  latestVersion: z.number().int().positive().nullable(),
  createTime: z.string(),
  updateTime: z.string(),
});
export type ProtoProjectSummary = z.infer<typeof ProtoProjectSummarySchema>;

export const ProtoRevisionSchema = z.object({
  id: snowflakeIdSchema,
  projectId: snowflakeIdSchema,
  version: z.number().int().positive(),
  model: z.string().nullable(),
  tokensIn: z.number().int().nonnegative().nullable(),
  tokensOut: z.number().int().nonnegative().nullable(),
  engineVersion: z.string().nullable(),
  failureKind: z.enum(PROTO_FAILURE_KINDS).nullable(),
  failureReason: z.string().nullable(),
  rpSha256: z.string().nullable(),
  rpBytes: z.number().int().nonnegative().nullable(),
  previewSha256: z.string().nullable(),
  hasSource: z.boolean(),
  hasAudit: z.boolean(),
  hasPreview: z.boolean(),
  hasRp: z.boolean(),
  confirmedBy: snowflakeIdSchema.nullable(),
  confirmedByName: z.string().nullable(),
  confirmedAt: z.string().nullable(),
  axureVerified: z.boolean(),
  axureVerifiedAt: z.string().nullable(),
  deliveredAt: z.string().nullable(),
  createTime: z.string(),
});
export type ProtoRevision = z.infer<typeof ProtoRevisionSchema>;

export const ProtoProjectDetailSchema = ProtoProjectSummarySchema.extend({
  requirement: z.string(),
  revisions: z.array(ProtoRevisionSchema),
  /** 审计报告里的闸结论；只发结论，原文走下载接口。 */
  gates: z.array(z.object({ key: z.string(), label: z.string(), passed: z.boolean() })),
});
export type ProtoProjectDetail = z.infer<typeof ProtoProjectDetailSchema>;

/** 原生闸的勾选是「人证」：不勾也能交付，但这一版会永久带着「未在 Axure 验证」。 */
export const ProtoConfirmBodySchema = z.object({
  revisionId: snowflakeIdSchema,
  previewSha256: z.string().min(1),
  axureVerified: z.boolean().default(false),
});
export type ProtoConfirmBody = z.infer<typeof ProtoConfirmBodySchema>;

export const PROTO_ARTIFACT_KINDS = ["rp", "preview", "audit", "source"] as const;
export const ProtoArtifactKindSchema = z.enum(PROTO_ARTIFACT_KINDS);
export type ProtoArtifactKind = z.infer<typeof ProtoArtifactKindSchema>;

/** 页面据此决定「开始生成」是否可点：引擎或模板缺失时置灰并显示同一条原因。 */
export const ProtoEngineStatusSchema = z.object({
  engine: z.object({ ok: z.boolean(), detail: z.string(), version: z.string().nullable() }),
  template: z.object({ ok: z.boolean(), detail: z.string() }),
  inflight: z.number().int().nonnegative(),
  maxInflight: z.number().int().positive(),
});
export type ProtoEngineStatus = z.infer<typeof ProtoEngineStatusSchema>;
