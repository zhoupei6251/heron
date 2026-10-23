# heron

一台自己部署的机器上的新闻视频生产线：定时收热榜 → 人认领 → 写稿 / 核查 / 渲染全自动 → 人在网页点确认 → 服务端发布。

规格书在 `docs/plans/`：

- `2026-10-22-heron-news-webapp-design.md` — 决策记录 N1–N6、状态机、五道闸语义、分期判据（冲突时以它为准）
- `2026-10-22-heron-stack-v2-design.md` — 技术栈定案版本、目录与架构、数据模型 v2、P0 实测结论与围栏

## 快速开始

需要 Node **24.22.1**（`.nvmrc`）与 pnpm 12（`.npmrc` 开 `engine-strict`）。

```bash
nvm use
pnpm install
cp .env.example .env        # POSTGRES_PASSWORD / DATABASE_URL / HERON_JWT_SECRET 现在必填
pnpm web:build              # 先出前端产物，api 直接托管它
pnpm api:dev                # http://127.0.0.1:3000
pnpm worker:dev             # 另开一个终端：串行 worker（等 api 把 schema 建出来）
pnpm p0                     # 不启动任何进程：开内存 pglite，跑一条假任务走完十态
```

前端热更新用 `pnpm web:dev`（5173，`/api` 代理到 3000）。

计划 T6 之后 api 带全局会话守卫，所以本机跑之前要先把库和第一个账号备出来：

```bash
docker compose -f deploy/docker-compose.yml --env-file .env up -d db
# .env 里填 HERON_OWNER_EMAIL / HERON_OWNER_PASSWORD（跑完这两行可以删）
pnpm db:seed-owner
```

**已知的过渡状态**：登录页要等计划 T9，而守卫已经生效——界面打得开，但 `/api/tasks` 一律 401。
现在唯一的入口是 `curl -c - -X POST localhost:3000/api/auth/login -d '{"tenant":"default","email":"…","password":"…"}'`。

## 命令

| 命令                           | 作用                                                                                  |
| ------------------------------ | ------------------------------------------------------------------------------------- |
| `pnpm check`                   | 出口判据：`format:check → lint → typecheck → test`，四步全绿才算过                    |
| `pnpm typecheck`               | 七个包各自 `tsc -p`，web 走 `vue-tsc`                                                 |
| `pnpm lint` / `lint:fix`       | ESLint 10 flat config，带类型感知；`reference/` 不扫                                  |
| `pnpm format` / `format:check` | Prettier，`docs/` 不动（规格书的排版是手排的）                                        |
| `pnpm test`                    | vitest：domain 判定 + db 约束 + api 会话 + worker 流水线（review 停/人确认/发布幂等） |
| `pnpm web:build`               | 两次 vite 构建：应用与版式包分开出                                                    |
| `pnpm db:seed-owner`           | 建第一个租户与 owner（幂等；已存在的账号不改口令）                                    |
| `pnpm p0`                      | P0 出口判据：空 capability 也能跑完一条假任务                                         |
| `pnpm api:dev:pglite`          | 浏览器验证用开发台：真 API 路由 + 内存 pglite（需 `HERON_DEMO_PASSWORD`；重启即清空） |

## 进程与目录

只有两个进程，分界是故障隔离不是微服务：Chromium / ffmpeg 崩溃不应带走 API。

```
apps/api/         NestJS：REST + SSE 进度 + 托管 web/dist（按域分目录 auth/tasks/gates/settings/events/health）
apps/worker/      单 worker：领租约 → 写稿 / 截图 / 合成 / 发布；到 review 放租约等人，确认后接着跑
apps/web/         Vite + Vue 3 + Tailwind 4 + Element Plus；entries/ 下是版式 SFC
packages/contracts/  zod 契约：DTO + 十态状态机 + 判定类型（前后端同一份）
packages/domain/     五道闸纯函数与状态机规则（闸只有这一份实现，api 与 worker 都读它）
packages/db/         Postgres + Drizzle + RLS（`@heron/db/pg`）；包根只剩路径、时间、指纹、设置键
capabilities/news/   这一个 capability 的 manifest（只留接缝，不做平台抽象）
Dockerfile           单阶段镜像，api 与 worker 共用；apps/web/dist 在镜像里
deploy/              docker-compose.yml（db + api + worker）
```

## 状态机与五道闸

```
candidate → claimed → scripting → factcheck → rendering → review → approved → publishing → published
                                                                    ↘ failed(带原因)
```

`factcheck`、`review` 是**闸**不是步骤。闸挂在转移上：去重（`norm_key`）、契约（8 条）、视觉几何、
合规三件套（mp4 元数据 `AIGC` + 声明「内容由AI生成」）、人工确认。

**当前门禁位：4 道闸的尺子还在 harness-factory，`GET /api/gates` 报 `open:false`，界面的「发布」按钮必须置灰。**
worker 里那三道是显式占位放行，台账会写明「占位放行：尺子还在 harness-factory」；人的闸不在 worker 里，
只能由确认动作（`confirmByHuman`）产出，worker 领不到 review 阶段的任务。

## 数据

只有一条数据面：**Postgres + Drizzle + RLS**（`@heron/db/pg`）。租户隔离由 `withTenant` 的
`SET LOCAL ROLE heron_app` + `set_config('app.tenant_id', …)` 单点保证；特权通道（`withAdmin` /
`heron_owner` / `BYPASSRLS`）只有跨租户调度的 `apps/worker/src/scheduler.ts` 能按深路径 import，
eslint 的 `no-restricted-imports` 挡住别处「偷偷拿特权连接」。包根（`@heron/db`）只剩路径、时间、
指纹与设置键，不 import 驱动也不开库。

成片与诊断产物在 `${HERON_HOME:-data}/videos/<task-id>/`（DB 只存路径与指纹）；
渲染失败留下 `.keep` 标记的目录不参与自动清理。保留天数存在 `settings` 里，清理任务本身还没接
（不假装有）。所有 `*_at` 列一律 UTC ISO-8601 带 `Z`，由 `packages/db/src/time.ts` 单点写入。
表名与列的设计依据见 stack-v2 §7。

`.env` 由 `packages/db/src/home.ts` 读取（api 与 worker 共用同一处路径计算）；
真环境变量优先于文件。两个进程都读 `DATABASE_URL`（没配就拒绝启动，不给默认值），
api 另读 `HERON_JWT_SECRET`（没配或短于 32 字符同样拒绝启动）与 `HERON_COOKIE_SECURE`。

## 部署

```bash
cp .env.example .env                                                  # 必填 POSTGRES_PASSWORD 与 HERON_JWT_SECRET
docker compose -f deploy/docker-compose.yml --env-file .env build
docker compose -f deploy/docker-compose.yml --env-file .env up -d
pnpm db:seed-owner                                                    # 在宿主跑：走 .env 里那条 127.0.0.1:5432
```

`--env-file .env` 不能省：compose 只在 compose 文件所在目录找 `.env`，而仓库的那份在根上。

- 三个服务：`db`（postgres:18-alpine，命名卷 `pg_data`）、`api`、`worker`。**api 与 worker 同一个镜像**
  （`Dockerfile` 单阶段，只是入口命令不同）——拆开就得把 `packages/*` 发成 npm 包。
- `api` 顺带静态托管 `apps/web/dist`，所以没有第四个服务，也不需要 nginx 才能看界面。
- 端口只绑回环（`127.0.0.1:3000`、`127.0.0.1:5432`）；要公网访问是你自己改 compose，不是默认。
- `heron_data` 卷被 api 与 worker 共享：成片要能被 api 直接读出来给前端。
- `worker` 等 `api` 的 healthcheck 通过才启动（`depends_on: service_healthy`）：迁移由 api 独占，
  worker 连上太早只会报 relation 不存在；它自己也有 `waitForSchema` 兜底。
- 会话是 cookie 里的 JWT（`HttpOnly` + `SameSite=Lax` + `Path=/`，故意不带 Expires），口令用 node 内置
  scrypt 校验；`HERON_COOKIE_SECURE=1` 才是 `Secure`，因为默认这台机器是 `http://127.0.0.1` 上自己用。
  安全头走 Nest 12 原生 `useSecurityHeaders()`，**CSP 目前只 report-only**（摘掉之前先按 T9 的违例清单收紧）。
- pg 主版本钉在 18 是硬要求：测试用的 pglite 实测报 `PostgreSQL 18.6`，对齐才叫测的就是生产行为。
- **当前状态**：compose 已在 WSL2（Ubuntu-22.04）的 Docker 28.5.1 + compose 2.40.0 上真跑过
  `build` / `up -d`：db 与 api healthy、worker 启动即领任务、`GET /api/health` 报 `PostgreSQL 18.6`，
  真 HTTP 登录与 `/api/auth/me` 往返通过；`heron_data` 卷属主是 `node`。镜像里没有 ffmpeg 与 Chromium，
  P4 接真渲染时才补进 base；反向代理、TLS 与备份随计划 2/3 补。
- 容器内 api 绑 `0.0.0.0`（compose 注入 `HOST`），对外仍由 `ports: 127.0.0.1` 收口；
  容器内绑回环会让宿主的端口映射收到空响应，而容器自己的 healthcheck 还是绿的。
- 多租户改造前留下的 SQLite 开发库用 `pnpm db:import-sqlite` 一次性导入：目标租户必须没有业务行，
  成功后旧库改名为 `<source>.pre-multi-tenant`（不删，便于对账）；合成 actor 不可登录。

## 安全边界（硬约束）

- `reference/` 是 oracle：heron 代码不 import 它，构建不读它，CI 不跑它。
- 会话只在 httpOnly cookie 里，响应体与 token 都不带口令哈希；载荷只有 `sub`/`tid`/`role`，
  而守卫每次请求都回库复查「人还在、没停用、租户没停租」——已签发的票在 12 小时 TTL 内可以被库里一列作废。
- 抖音登录态在 `~/.heron/state/douyin/`，仓库与 DB 都不存 cookie 本体；DB 只存路径 + sha256 指纹，
  指纹不匹配就红，**绝不静默用一份来源不明的旧登录态去发东西**。
- 模型密钥只走环境变量，调用集中在一个 client 模块里。
- 发布重试前必须先核实"是不是其实已经发出去了"（`publishes.verified_at`），回执丢失不等于没发出去。

## 不做

微服务（拆库不等于拆服务，仍是 api + worker 两个进程）· Next.js/Nuxt（会把前端与版式拆成两套世界观）·
serverless（V8 isolate 没有子进程与 ffmpeg/Chromium）· 无人值守自动直发 · 队列中间件（Redis/BullMQ
等计划 2 再定，现在进程内 + DB 租约是够的）。

翻过的案：Postgres、Docker、多租户原本都在这份否决名单里，2026-10-22 按「往 SaaS 主流形态做」重议后
改为落地——决策、代价与顺序见 [docs/plans/2026-10-22-saas-multi-tenant-plan-1.md](docs/plans/2026-10-22-saas-multi-tenant-plan-1.md)。
