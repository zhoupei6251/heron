# 借鉴 RuoYi-Vue-Plus：四项治理能力 + 七牛对象存储

> 合并文档（Tier 2+）：spec + plan + 证据段合一，按 `harness-factory/CLAUDE.harness.md` §3 写。
> 关联规格书：`2026-10-22-heron-news-webapp-design.md`（五道闸）、`2026-10-22-heron-stack-v2-design.md`（表设计）、
> `2026-10-22-saas-multi-tenant-plan-1.md`（多租户）。
> 改动规模判断：动 schema、加依赖、跨 api/worker/web 三个 app 与四个 package → **Tier 2+**，不走 Tier 1 合并文档。

## 0. 一句话

从 RuoYi-Vue-Plus / plus-ui 里**只借四件 heron 现在缺的治理能力**（租户套餐、平台级配置、在线会话与逐条强退、可切换的对象存储），
并把成片落到**七牛 Kodo 华南（z2）私有空间**；AK/SK 只从环境变量读，不进库、不进镜像、不进 git。

## 1. 决策：借什么、不借什么

### 借（四件）

| 能力 | RuoYi 里的原形 | heron 现在的缺口 |
|---|---|---|
| 租户套餐与额度闸 | `sys_tenant` 的套餐/到期/账号数上限 | `tenants` 只有 `id/name/slug/suspendedAt/createdAt`，没有档位，也没有任何额度判据 |
| 平台级配置 | `sys_config`（key/value，带分组与类型） | `settings` 主键是 `(tenant_id, key)`，**只有租户级**；平台没有一处可改的默认值 |
| 在线会话 + 强退 | `sys_user_online` + 「强退」 | 会话是无状态 JWT + `session_version`：能「全部踢掉」，不能列出「谁在线」，也不能踢单个设备 |
| 对象存储适配 | `OssFactory` + 多种 `OssClient` | 成片只落本地盘（`VIDEOS_DIR`），`renders` 只存绝对路径；没有 key / bucket / 外链概念 |

### 不借（逐条说明为什么 heron 已经有更好的那一半）

| RuoYi 的做法 | 不借的理由 |
|---|---|
| `@DataScope` 部门树 + SQL 拼 `dept_id IN (...)` | heron 的隔离是 Postgres RLS + `withTenant()` 单点降权（`packages/db/src/pg/rls.ts:193`），策略在库上，漏写 WHERE 也不跨租户 |
| 租户插件按列改写 SQL | 同上：列过滤是应用层自觉，RLS 是库强制（`FORCE ROW LEVEL SECURITY`） |
| `v-hasPermi` 指令 + 后管菜单表 | heron 用 `ACTIONS × MIN_ROLE`（`packages/domain/src/rbac.ts:38`）+ 服务端下发菜单，菜单里没有的页面根本不注册路由 |
| 工作流引擎 / 代码生成器 / 多数据源 / SM2 加密 | 与产品无关（见项目 CLAUDE.md「不做」） |
| 字典、幂等、审计日志 | heron 已有：`publishes.idempotencyKey` 唯一索引、`audit_logs` / `platform_audit_logs` |

## 2. 现状（要改的那几处）

```
worker: pipeline.onEnter(rendering)  → 写 fake.mp4 / contact-sheet.txt → insertRender(路径)
                                     → onEnter(publishing) → insertPublish + updatePublish(stub)
api:    GET /api/manage/artifacts/:taskId/:kind?version=N
          → ArtifactService.serve → getArtifactForTask(RLS) → realpath 包含性校验 → res.sendFile
        保留清理 cleanupArtifacts → 按租户保留天数 rmSync 任务目录
```

关键事实（证据见 §5）：

- 产物只有**本地绝对路径**：`renders.mp4_path / contact_sheet_path / aigc_sidecar_path`，`.env` 里没有任何存储相关配置。
- 取产物**必须带 cookie**：`ARTIFACT_ROOT` 注入的是 `VIDEOS_DIR`（`apps/api/src/manage/manage.module.ts:13`），
  响应头是 `Cache-Control: private, no-store`；前端两个 helper 生成的是相对 `/api/...`（`artifactUrl` / `protoArtifactUrl`）。
- 会话**无登记**：`sessions` 表不存在；登录签 `{sub,tid,role,ver}`，撤销靠 bump `users.session_version`（全是全量踢）。
- 设置**只有租户级**：`settings` 主键 `(tenant_id, key)`，键名在 `packages/db/src/setting-keys.ts`，四项（保留天数 + 模型三项）。
- 平台台**五个菜单**，没有「平台设置」，`PlatformController` 六条路由里没有 settings。

## 3. 设计

### 3.1 存储适配器（`packages/storage`，新包）

新增 workspace 包 `packages/storage`，api 与 worker 共用；`qiniu` 依赖只在这一层。

```ts
export interface ObjectStore {
  readonly driver: "local" | "qiniu";
  /** 上传本地文件（同名覆盖），返回对象键；local 驱动直接返回 key，不动文件。 */
  put(key: string, localPath: string, contentType: string): Promise<{ key: string; bytes: number }>;
  /** 私有桶的短时效下载地址；local 驱动返回 null（调用方走本地 sendFile）。 */
  url(key: string, ttlSec?: number): string | null;
  /** 删除对象；对象不存在算成功（清理要可重跑）。 */
  remove(key: string): Promise<void>;
}
export function createObjectStore(env?: NodeJS.ProcessEnv): ObjectStore;
export function objectKey(input: { tenantId: string; taskId: string; version: number; kind: ArtifactKind }): string;
```

- **对象键布局**：`tenants/<tenantId>/tasks/<taskId>/v<version>/<kind>.<ext>`（`mp4|png|json`）。
  带租户前缀是为了生命周期规则与「按租户导出/删除」能按前缀做；带版本号是因为重渲会换片。
- **`local` 驱动（默认）**：`put` 返回 key 但不动文件（文件本来就由 worker 写在 `VIDEOS_DIR`），`url` 返回 `null`。
  这样**没配七牛的环境行为一字不变**，现有 e2e（写真实文件再取）不需要改判据。
- **`qiniu` 驱动**：私有空间 + 华南 z2。
  - SDK `qiniu@7.15.2`（`dist-tags.latest` 实测），CJS + 自带 `.d.ts`；`module: nodenext` 下 `import * as qiniu from "qiniu"` 可用。
  - 上传：`new qiniu.conf.Config({ zone: qiniu.zone.Zone_z2, useHttpsDomain: true })` +
    `new qiniu.form_up.FormUploader(config).putFile(token, key, localPath, null)` —— **从磁盘流式上传**，不把 mp4 读进内存；表单上传单文件上限 1GB。
  - 令牌：`new qiniu.rs.PutPolicy({ scope: `${bucket}:${key}`, expires: 3600 }).uploadToken(new qiniu.auth.digest.Mac(ak, sk))`
    （scope 绑到具体 key，一张票据只能写这一个对象）。
  - 下载：`new qiniu.rs.BucketManager(mac, config).privateDownloadUrl(domain, key, deadline)` —— 私有空间的签名直链，**自己不做 HMAC**。
  - 上传域名不写死：`Zone_z2` 给出 `up-z2.qiniup.com` 一族，SDK 自己挑；`QINIU_UPLOAD_HOST` 只在自建加速域名时要填。
- **配置（只从环境变量读，缺失或半配立刻抛）**：
  ```
  HERON_STORAGE=local|qiniu        # 默认 local
  QINIU_ACCESS_KEY=                # 你之后提供
  QINIU_SECRET_KEY=
  QINIU_BUCKET=                    # 例：heron-videos
  QINIU_DOMAIN=                    # 绑定到该空间的下载域名（不带 scheme 也行，统一补 https://）
  ```
  `HERON_STORAGE=qiniu` 而四项缺任意一项 → **启动即失败**并指名缺哪项。半配能跑起来的存储配置，
  症状是「渲染时才发现」，而那时已经付过一次渲染成本。

### 3.2 落库与取址

- `renders` 加三列（可空）：`mp4_key`、`contact_sheet_key`、`aigc_sidecar_key`。
  路径列**保留**——本地盘仍是发布与诊断的现场，key 是它的异地副本；两者都不许互相推导。
- `ArtifactService.serve` 取址顺序：**本地文件在根目录内且存在 → `sendFile`（今天的行为）；否则该 kind 有 key → 302 到签名直链**。
  302 带 `Cache-Control: private, no-store`（签名链接会过期，浏览器缓存住就等于给了一个永久链接）。
- 上传时机：`pipeline.onEnter` 的 `rendering` 分支，**先传再落库**，把 key 一起写进 `insertRender`。
  上传失败 → 抛错 → 事务回滚 → 任务按既有重试机制失败并带原因（不静默降级成「只有本地有」）。
- 清理：`cleanupArtifacts` 保留天数到期时**同时删远端对象**（先按 `renders` 行取 key 逐个 `remove`，再删本地目录）。
  远端删除失败不阻断其余任务，但计数进 `CleanupResult.objectsFailed` 并逐条 `console.error`（key + 原因）。
  任务行不删，所以下一轮会重试同一批 key —— 失败是自愈的，不需要失败队列。

### 3.3 租户套餐（plan + 额度闸）

```ts
// packages/contracts
export const PLAN_IDS = ["free", "standard", "enterprise"] as const;
export const PLAN_LIMITS: Record<PlanId, { seats: number; rendersPerMonth: number; publishesPerMonth: number }> = {
  free:       { seats: 3,  rendersPerMonth: 100, publishesPerMonth: 30 },
  standard:   { seats: 20, rendersPerMonth: 1000, publishesPerMonth: 300 },
  enterprise: { seats: 100, rendersPerMonth: 10000, publishesPerMonth: 3000 },
};
```

- `tenants.plan text not null default 'free'` + `check (plan in (...))`（由 `inEnum` 生成，与 `PLAN_IDS` 同源）。
- 额度判据是纯函数（`packages/domain/src/plan.ts`，进 `browser.ts`）：
  `exceeds(plan, usage) → { kind, limit, used } | null`，界面据此置灰并显示「本月发布 30/30，升档请到平台台调整」。
- **闸门位置**（都在数据层，绕不过接口）：
  - 席位：`createManagedMember` 前数本租户 `users`，超限抛 `ManagementError("conflict", ...)`；
  - 月渲染 / 月发布：worker 进 `rendering` / `publishing` 前，按**当前 UTC 自然月**数 `renders` / `publishes`（join `tasks` 拿租户）。
    不新增 usage kind —— 账本里 `render_seconds` 是秒数，与「月渲染条数」不是一回事，混用会把账做错。
- 改档：`PATCH /platform/tenants/:id/plan`（平台台），写 `platform_audit_logs` 的 `tenant.plan_changed`
  （`{from, to}`），平台租户列表多一列档位标签 + 改档选择。
- 顺带补齐两笔**已声明但从未写**的账：渲染成功记 `render_seconds`、发布确认记 `publish`（`USAGE_KINDS` 里本来就有）。

### 3.4 平台级配置

新表 `platform_settings`（平台域，`TABLES_PLATFORM`）：

```
key text primary key, value text not null, updated_at text not null, updated_by uuid
```

- 键与类型只有一份（`packages/contracts` 的 `PLATFORM_SETTING_DEFS`，每个键带 zod schema 与默认值）：

| 键 | 类型 | 作用 |
|---|---|---|
| `default_model_base_url` | URL | 租户没配模型地址时的兜底（压过环境变量） |
| `default_model_name` | 非空字符串 | 同上，模型名 |
| `default_retention_days` | 正整数 ≤ 3650 | 租户没配保留天数时的兜底 |
| `default_plan` | `PLAN_IDS` | 新开通租户的默认档 |

- **密钥不在此表**：`model_api_key` 仍然只走租户设置或 `HERON_MODEL_API_KEY`。
  平台级共享密钥 = 一个所有租户都能用的额度，而它落在一张谁都能读的配置表里（RuoYi 的 `sys_config` 正是这个形状）。
- 读取顺序：**租户设置 → 平台设置 → 环境变量 → 内置默认**。解析点只有两处：
  worker 的 `resolveModelConfig` 调用方（`scripting.ts`）、`cleanupArtifacts` 的保留天数。
- 缓存：api 进程内 30s TTL，写入即失效；worker 每个任务读一次（不缓存，进程可能活很久）。
- 接口：`GET /platform/settings`、`PUT /platform/settings`（平台 audience），写入进 `platform_audit_logs`
  （`platform_setting.updated`，value 里含非密钥项全文）。
- 平台台加一页「平台设置」（菜单 id `platform-settings`）。

### 3.5 在线会话 + 逐条强退（只做租户成员）

新表 `sessions`（带 `tenant_id`，进 `TABLES_WITH_TENANT`）：

```
id uuid pk, tenant_id uuid not null, user_id uuid not null,
created_at text, last_seen_at text, user_agent text, ip text,
revoked_at text, revoked_by uuid
index (tenant_id, user_id) / index (tenant_id, last_seen_at desc)
```

- JWT 载荷加 `sid?: uuid`（平台查看票没有 session 行，保持可选 → 老票形状仍能验过 schema）。
- 登录：口令通过后在**同一个租户事务**里插一行，签 `sid`。`AuthUser.sessionId?: string` 带给界面，用来标出「当前这台设备」。
- 守卫复查（`AuthService.userFor`）在既有那次 `withTenant` 里多一条语句：
  ```sql
  UPDATE sessions
     SET last_seen_at = CASE WHEN last_seen_at < :throttle THEN :now ELSE last_seen_at END
   WHERE id = :sid
  RETURNING revoked_at
  ```
  没有行或 `revoked_at` 非空 → 401。`last_seen_at` 只在超过 60s 时更新，避免每个请求都写一行。
- 语义调整（**这条要在文档里写明**）：登出从「bump `session_version` 全量踢」改成「吊销当前 sid」；
  「踢掉其他设备」「改口令」仍走 `sessionVersion`（改口令必须让全部旧票失效，那是安全语义不是偏好）。
- 接口（`user.manage`? 不 —— 新增动作 `session.manage`，最低 `admin`）：
  - `GET /manage/sessions?page&pageSize&q`：本租户未吊销的会话，含邮箱、显示名、UA、IP、最近活跃、是否当前设备；
  - `DELETE /manage/sessions/:id`：吊销单条（`revoked_at`/`revoked_by`），审计 `session.revoked`（含被踢用户与 UA）。
  - 未删行、只标吊销：`last_seen_at` 与「当时从哪台机器登进来」是可以回溯的事实，物理删就没有了。列表只显示未吊销行。
- 租户台加一页「在线会话」（菜单 id `sessions`，组「安全与治理」，`action: "session.manage"`）。

## 4. 批次与文件清单

五批，每批独立可验证；顺序即依赖顺序。

### 批次 A：存储适配器 + 七牛（用户明确要的那件）

| 文件 | 动作 |
|---|---|
| `packages/storage/{package.json,tsconfig.json,src/index.ts,src/local.ts,src/qiniu.ts,src/key.ts}` | 新增 |
| `packages/storage/src/config.test.ts`、`key.test.ts` | 新增（配置解析 / 键布局；qiniu 客户端用注入的 fake，不打网络） |
| `pnpm-workspace.yaml` | 无需改（`packages/*` 已覆盖） |
| `apps/api/package.json`、`apps/worker/package.json` | 加 `"@heron/storage": "workspace:*"` |
| `packages/db/src/pg/schema/artifacts.ts` | `renders` 加三个 key 列 |
| `packages/db/drizzle/0008_*.sql` + `meta/` | 生成迁移 |
| `packages/db/src/pg/artifacts.ts` | `NewRender` / `insertRender` / `latestRender` / `getArtifactForTask` 带上 key |
| `apps/worker/src/pipeline.ts` | `rendering` 分支上传 + 落 key；`cleanup` 取 key |
| `apps/worker/src/cleanup.ts` | 远端删除 + `objectsFailed` |
| `apps/api/src/manage/artifact.service.ts` | 本地优先，miss 时 302 到签名直链 |
| `apps/api/src/manage/manage.module.ts` | 注入 `OBJECT_STORE` |
| `.env.example` | `HERON_STORAGE` / `QINIU_*` 五条 |

### 批次 B：租户套餐与额度闸

`packages/contracts`（`PLAN_IDS`/`PLAN_LIMITS`/`Tenant.plan`/`PlatformTenant.plan`/`TenantPlanBody`）、
`packages/domain/src/plan.ts`（+ `browser.ts` 导出 + `plan.test.ts`）、
`packages/db/src/pg/schema/tenants.ts`（plan 列 + check）、
`packages/db/src/pg/quota.ts`（月度计数 + 席位计数 + `assertQuota`）、
`packages/db/src/pg/{management,platform,tenants}.ts`（创建成员闸、改档、列表带 plan）、
`apps/api/src/platform/platform.controller.ts`（`PATCH /platform/tenants/:id/plan`）、
`apps/worker/src/pipeline.ts`（进渲染/发布前判额度 + 记 usage）、
`apps/web/src/pages/platform/PlatformTenantsView.vue`（档位列 + 改档）。

### 批次 C：平台级配置

`packages/db/src/pg/schema/management.ts`（或新 `platform-settings.ts` 表定义）+ 迁移 0010、
`packages/db/src/pg/platform-settings.ts`（特权通道读写 + 审计）、
`packages/contracts`（`PLATFORM_SETTING_DEFS` + 视图类型）、
`apps/api/src/platform/{platform.controller.ts,platform-settings.service.ts}`、
`apps/worker/src/{scripting.ts,cleanup.ts}`（读取链）、
`apps/web/src/pages/platform/PlatformSettingsView.vue` + 菜单四处（contracts `CONSOLE_MENU_IDS`、`rbac.ts` `PLATFORM_MENUS`、`menu-routes.ts`、`ConsoleFrame.vue` 的 `ICONS`）。

### 批次 D：在线会话与强退

`packages/db/src/pg/schema/identity.ts`（或 `sessions.ts`）+ 迁移 0011、
`packages/db/src/pg/sessions.ts`（插入 / 触摸 / 列表 / 吊销）、`schema/index.ts` 加进 `TABLES_WITH_TENANT`、
`packages/contracts`（`JwtPayload.sid`、`AuthUser.sessionId`、`SessionItem` / `Page<SessionItem>`）、
`packages/domain/src/rbac.ts`（`session.manage` + 菜单）、
`apps/api/src/auth/{auth.service.ts,console-auth.service.ts,auth.controller.ts}`（登录落行、守卫查吊销、登出改语义）、
`apps/api/src/manage/{sessions.service.ts,manage.controller.ts}`、
`apps/web/src/pages/console/SessionsView.vue` + 菜单四处。

### 批次 E：验证与文档

`.env.example` 补完、`docs/plans/` 本文件、`verifications/2026-10-28-admin-borrows-verification-lite.md`。

## 5. 证据（Evidence）

### 5.1 改动触及的代码（from `search_graph`）

| 符号 | qualified_name | 行 | 角色 |
|---|---|---|---|
| `insertRender` | `D-zhoupei-heron.packages.db.src.pg.artifacts.insertRender` | 97–118 | 直接改（带 key） |
| `getArtifactForTask` | `…packages.db.src.pg.management.getArtifactForTask` | 659–697 | 直接改（返回 key） |
| `ArtifactService` | `…apps.api.src.manage.artifact.service.ArtifactService` | 13–81 | 直接改（本地优先 / 302） |
| `cleanupArtifacts` | `…apps.worker.src.cleanup.cleanupArtifacts` | 25–57 | 直接改（删远端） |
| `walkTask` | `…apps.worker.src.pipeline.walkTask` | 142–185 | 直接改（额度闸 + 记账） |
| `resolveModelConfig` | `…apps.worker.src.model.resolveModelConfig` | 58–90 | 调用点改（多一层平台默认） |
| `getModelSettings` | `…packages.db.src.pg.settings.getModelSettings` | 66–72 | 调用者 |
| `createManagedMember` | `…packages.db.src.pg.management.createManagedMember` | 156–183 | 直接改（席位闸） |
| `provisionTenant` | `…packages.db.src.pg.platform.provisionTenant` | 197–236 | 直接改（默认档） |
| `changeTenantStatus` | `…packages.db.src.pg.platform.changeTenantStatus` | 238–262 | 参照（改档照它的审计形状写） |
| `listPlatformTenants` | `…packages.db.src.pg.platform.listPlatformTenants` | 154–195 | 直接改（带 plan） |
| `AuthService.userFor` | `…apps.api.src.auth.auth.service.AuthService.userFor` | 215–236 | 直接改（查 sids 吊销） |
| `consoleAccess` | `…packages.domain.src.rbac.consoleAccess` | 89–109 | 直接改（新菜单/新动作） |
| `recordUsage` | `…packages.db.src.pg.usage.recordUsage` | 25–35 | 复用（补两笔记账） |

### 5.2 影响面（from `trace_path`）

- `insertRender`（inbound 2）：`pipeline.onEnter`（hop 1）、`pipeline.walkTask`（hop 2）——都在 worker 内，签名加可选字段即可。
- `AuthService.userFor`（inbound 3）：`jwt.strategy.validate`、`AuthService.userForToken`、`ConsoleAuthService.sessionFor`。
  三者共用同一次复查，所以**吊销判据只能加在 `userFor` 里一次**；加到别处就会有一条路径漏判。
- `getArtifactForTask`（inbound 1）：只被 `ArtifactService.serve` 调；返回值是 INTERNAL 形状（注释已写明不许序列化给浏览器），加 key 不扩大暴露面。
- `createManagedMember`（inbound 2）：`ManageController` 建成员、`tools/seed-owner` 引导首个 owner —— 引导路径必须**跳过席位闸**
  （否则空库的 free 档在第一个 owner 之前就把自己锁住；实际是 owner=1 ≤ 3 不触发，但这条要在测试里钉住）。
- `cleanupArtifacts`（inbound 2）：worker 的 tick 与 `cleanup.test.ts`；`CleanupResult` 加字段是向后兼容的扩展。

### 5.3 迁移与约束

- 迁移编号续 `0007_proto_workshop.sql` → **0008 / 0009 / 0010 / 0011**（一批一条，便于回滚定位：
  `0008_artifact_objects` / `0009_tenant_plan` / `0010_platform_settings` / `0011_sessions`）。
- `applyRls()` 每次启动幂等重放（`packages/db/src/pg/migrate.ts:25`）：新表只加进 `TABLES_WITH_TENANT` / `TABLES_PLATFORM` 就会被建策略；
  `sessions` **必须**进 `TABLES_WITH_TENANT`（它自带 `tenant_id`，不是子表，`TENANT_CHILD_PARENT` 不用动）。
- `schema.test.ts` 拿四张清单对 `information_schema` 要求并集正好覆盖：新表漏登记 → 该测试红，这是第一道自检。

## 6. 验证断言（改完逐条核）

> 勾选状态对应 2026-10-29 的落盘实况；逐条证据见 `verifications/2026-10-28-admin-borrows-verification-lite.md`。

- [x] `HERON_STORAGE` 未配 = 今天的行为：`artifact.e2e.test.ts` / `cleanup.test.ts` / `pipeline.test.ts` 全绿，断言只加不改。
- [x] `HERON_STORAGE=qiniu` 缺任一 `QINIU_*` → `createObjectStore` 抛错并指名缺项（单测）。
- [x] `objectKey()` 对同一输入稳定、对版本/租户/kind 敏感（单测）；键里不含 `..`、不含反斜杠。
- [x] qiniu 驱动：`put` 用 `scope: bucket:key` 的令牌、`Zone_z2`、`useHttpsDomain: true`；`url` 走 `privateDownloadUrl`（fake 客户端断言调用参数）。
- [x] `ArtifactService`：本地有文件 → `sendFile`；本地缺失但有 key → 302，且带 `Cache-Control: private, no-store`；都没有 → 404（e2e）。
- [x] 上传失败 → 任务 failed 且 `error_reason` 含上传错误片段，`renders` 里没有半条记录（事务回滚）。
- [x] 清理：过期任务先删远端再删本地；远端删失败计入 `objectsFailed` 且下一轮会重试同一 key（单测用 fake store）。
- [x] 额度：free 档第 4 个成员被拒并给出「3/3 席位」；free 档本月第 101 次渲染、第 31 次发布被拒且任务带原因。
- [x] 改档：`PATCH /platform/tenants/:id/plan` 落 `platform_audit_logs`，`{from,to}` 可查。
- [x] 平台设置：**写入即刻生效**——没有 api 侧缓存，读取一律穿到 `platform_settings`（原计划的「写入即失效缓存」不适用，见 §10.2）。
      worker 读取顺序 租户 > 平台 > env > 默认（单测三条链各一例）。
- [x] 会话：登录后有行；踢单条后该票立刻 401、同用户另一条票仍可用；改口令后全部 401。没有 `sid` 的旧票一律 401（强制重登一次）。
- [x] 新菜单：`platform-settings` 只在平台台出现；`sessions` 对所有租户成员出现（成员只看得到自己那几条）——
      与原计划的「viewer/reviewer 不出现」不同，理由见 §10.3。
- [x] `pnpm check`（format:check → lint → typecheck → test）在 Node 24.22.1 下通过。

## 7. 风险与盲点（主动声明）

- **七牛链路没有联网单测**：`qiniu` 客户端只能靠注入 fake 验证调用形状，真实上传/下载/删除**必须**用你给的 AK/SK 做一次手工冒烟
  （传一个 1MB 文件 → 取私有链接 → 删）。这条不写成「已验证」。
- **会话多一条 SQL**：每个受保护请求 +1 条 `UPDATE ... RETURNING`。在 pglite 单连接下测试会变慢；已用 60s 节流压住写放大，
  但吞吐影响未实测——盲点。
- **登出语义变化**：从「全量踢」变成「踢当前设备」。多设备登出的用户会认为自己「已经退了」而另一台还在。这是有意的，
  但要用界面文案（在线会话页）补上认知，否则是静默的行为改变。
- **私有桶链接是 bearer 凭据**：拿到链接的任何人在 TTL（默认 10 分钟）内可读该成片。TTL 不能长（签名会泄漏到浏览器历史/日志）。
- **额度按 UTC 自然月**：中国用户看到的「本月」在月初有 8 小时偏差。要么接受（并在界面写明 UTC），要么把窗口改成 +08:00——本轮取「写明 UTC」。
- **对象存储开启后 `HERON_HOME` 仍是真相来源之一**：发布（将来接 Playwright 上传）读的是本地文件。跨实例部署时
  「A 生成、B 发布」仍会失败。这个盲点不是本轮引入的，但对象存储会让人以为已经解决了。

## 8. 本轮不做

- 平台管理员会话的逐条强退（仍用 `session_version` 全量踢；`platform_operations` 只有几个账号）。
- 原型工坊产物上对象存储（`PROTOS_DIR` 保持本地；它有自己的 sha256 校验与 50MB 上限，换存储要重写那一套）。
- 套餐到期时间、按量计费、发票（`PLAN_LIMITS` 只做额度上限）。
- 存储类的 RuoYi「多驱动注册表 + 前端选驱动」：驱动由环境变量定，一个部署一种，不做运行时切换。

## 9. 需要你提供

1. 七牛四项：`AK`、`SK`、空间名（bucket）、绑定到该空间的**下载域名**（私有不影响：域名相同，链接带签名）。
2. 空间所在区域确认是**华南-广东（z2）**；若你建的是别区（华东 z0 / 华北 z1），改 `QINIU_REGION` 一处即可（默认 z2）。
3. 三个档位（免费/标准/企业）的真实额度：我先按 §3.3 的默认值落地，你给数字后只改 `PLAN_LIMITS` 一处。

---

**确认开始实现吗？** 建议按批次 A（七牛对象存储）先行独立合入，其余三批再按 B → C → D 推进。

> 用户在 2026-10-28 明确答复「确认开始实现，A→B→C→D 四批依次做完」。以下为落盘实况。

## 10. 落盘实况（as built，2026-10-29）

### 10.1 实际落点

| 批次 | 落点 |
|---|---|
| A | `packages/storage`（`config` / `key` / `local` / `qiniu` + 各一个测试）、迁移 `0008_artifact_objects`、`renders` 三列、`pipeline` 上传 + 失败原因、`cleanup` 删远端、`ArtifactService` 本地优先 → 302 签名直链 |
| B | `PLAN_IDS` / `PLAN_LIMITS`、`tenants.plan`（迁移 `0009_tenant_plan`）、`quota.ts`（席位 + 月度渲染/发布）、`PATCH /platform/tenants/:id/plan`、平台台档位列 + 改档 |
| C | `platform_settings` 表（迁移 `0010_platform_settings`）、`platform-settings.ts`（特权通道读写 + 审计）、`GET/PUT /platform/settings`、`PlatformSettingsView.vue`、worker 解析链 |
| D | `sessions` 表（迁移 `0011_sessions`）、`packages/db/src/pg/sessions.ts`、`JwtPayload.sid` / `AuthUser.sessionId`、登录落行 + 每次请求复查、`GET/DELETE /manage/sessions`、`SessionsView.vue` |

### 10.2 与计划的偏差（逐条给理由）

1. **平台设置不做 api 侧缓存。** 计划写的是「写入即失效缓存」，前提是「每请求都读平台设置」。实际读取方只有 worker 的模型解析链与清理保留期——
   一条查询摊到一次渲染 / 一轮清理上，不构成热路径。加起来只是一个失效点，收益抵不过复杂度。所以 `platform_settings` 读穿，写入即刻生效（§6 已按此改写）。
2. **平台默认模型也进了 `proto.ts`。** 计划只点了 `scripting.ts`；原型工坊是第二条模型调用点，漏了就会出现「写稿用平台默认、工坊不肯用」。
3. **`sessions` 菜单不设权限门槛。** 成员能看自己的登录、退自己的登录；`session.manage` 只决定「能不能动别人的」。
   因此菜单对全体成员出现，页面按权限收窄数据与按钮（列表 SQL 里有 `eq(sessions.userId, viewer.id)` 兜底，不靠界面藏）。
   这一点与原计划「viewer/reviewer 不出现」相反，**以落盘为准**。
4. **迁移编号**：`0010` 给了平台设置，会话顺延到 `0011`。
5. **登出只作废当前这一条。** 原来靠拉高 `session_version` 整户撤销，那等于「退出登录顺手把手机上的我也踢了」。
   现在 `sid` 指向的那行被标 `revoked_at` 即失效；`session_version` 退居整户撤销（改口令 / 停用 / 改角色 / 停租 / 一键踢全部）。
   没有 `sid` 的旧票一律 401——要求重新登录一次换带 `sid` 的票，因为旧票撤销不了单条。
6. **平台运营账号仍不逐条会话**（§8 原样保留：`platform_operations` 只有几个账号，仍用 `session_version` 全量踢）。

### 10.3 验证

逐条证据、跑过的命令与输出摘要见 `verifications/2026-10-28-admin-borrows-verification-lite.md`。
