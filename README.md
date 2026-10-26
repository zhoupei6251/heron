# heron

自主部署的多租户新闻视频工作室，以人工确认连接选题、生产、审片与发布。

**当前可用：** 统一登录与权限菜单、工作空间与平台管理、成员与角色管理、任务与审片界面、用量和审计、Postgres 行级隔离、Docker Compose 部署。

**仍在建设：** 自动采集调度、模型生成、真实渲染与发布平台接入、计费。演示任务不会调用外部发布平台；生产门禁未就绪时，不应把演示流程视为可用于真实发布的生产线。

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

API 默认要求登录。本机连接 Postgres 前，需要先启动数据库并创建初始工作空间所有者：

```bash
docker compose -f deploy/docker-compose.yml --env-file .env up -d db
# .env 里填 HERON_OWNER_EMAIL / HERON_OWNER_PASSWORD（跑完这两行可以删）
pnpm db:seed-owner
```

所有账号都在 `/login` 登录：工作空间成员填写工作空间标识、邮箱与密码；平台管理员将工作空间留空。登录后按服务端返回的角色、权限与菜单进入首页，不需要选择控制台。相同邮箱不代表相同账号，系统不会跨工作空间尝试登录，也不会把工作空间所有者自动提升为平台管理员。

要创建初始平台管理员，显式配置 `HERON_PLATFORM_EMAIL`、`HERON_PLATFORM_PASSWORD`（12–128 位）和可选的 `HERON_PLATFORM_NAME`，再运行 `pnpm db:seed-platform`。引导命令不会重置已有账号的密码，完成后应移除引导密码配置。

### 不连接业务数据库的界面开发

在本地环境提供一次性的 `HERON_DEMO_PASSWORD`（12–128 位，无内置默认值），然后使用两个终端：

```bash
# 终端 1：完整 API 路由，数据库使用内存 PGlite
PORT=3101 pnpm api:dev:pglite
# 终端 2：Vite，仅代理到上述开发 API
HERON_DEV_API_URL=http://127.0.0.1:3101 pnpm web:dev
```

演示工作空间标识为 `default`，账号为 `owner@example.com`、`reviewer@example.com`、`viewer@example.com`；独立平台账号为 `platform@example.com`。密码均使用你显式提供的演示密码。重启 API 后数据清空；此方式不启动 worker，也不进行真实发布。环境加载逻辑仍与普通开发相同，演示环境不要使用生产密钥。

### 前端与控制台

只有 **一个 Vue 前端应用、一套登录与会话、一套管理布局**。参考 plus-ui 的用户信息与权限路由模式，服务端返回当前账号的 `roles`、`permissions` 和 `menus`；前端只从本地组件白名单注册允许的页面，侧栏和快捷导航共用这份菜单。

| 入口     | 功能                                                                                         |
| -------- | -------------------------------------------------------------------------------------------- |
| 工作空间 | 概览、候选、生产、审片、发布记录、成片库、成员、权限、数据源配置、设置、用量、审计、个人安全 |
| 平台运营 | 概览、租户开通/停用/恢复、全局用量、平台审计、服务健康                                       |

成员管理只对工作空间所有者开放，数据源、设置和审计要求管理员及以上。页面可见性与操作权限分别控制：例如只读成员可看审片队列，但不能确认。平台账号不因此获得租户内容操作权限；服务端仍独立鉴权并执行 RLS，隐藏菜单不替代安全检查。

视频版式在 `apps/web/src/entries/` 单独构建，不是第三套管理后台。

- 顶栏支持明亮、深色、跟随系统三种配色；偏好保存在当前浏览器，不存储任何登录凭证。
- `Ctrl+K` / `⌘K` 打开快捷导航，按页面名称、分组或路径搜索；方向键选择，Enter 进入，Esc 关闭。快捷导航只显示当前账号获准访问的页面。
- 表格、抽屉和审片工作台适配窄屏；动画尊重系统的减少动态效果设置。
- 用量统计不是账单，健康检查不是外部发布服务的可用性证明；数据源配置保存不代表自动采集已经执行。

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
| `pnpm db:seed-platform`        | 创建独立平台管理员（显式密码；不修改已有账号）                                        |
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

只有一条数据面：**Postgres + Drizzle + RLS**（`@heron/db/pg`）。租户业务通过 `withTenant` 的
`SET LOCAL ROLE heron_app` + `set_config('app.tenant_id', …)` 隔离；平台管理、身份引导和跨租户调度使用
受控的特权仓储入口，不向客户端开放任意 SQL 或任意租户绕过能力。包根（`@heron/db`）只保留路径、时间、
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

### 自动构建与发版（阿里云 ACR）

镜像仓库 `registry.cn-shanghai.aliyuncs.com/heron_docker/heron`（ACR 个人版，cn-shanghai）。
链路是 **push → ACR 云端构建 → 宿主 cron 拉取重建**，人只在第一次动手：

1. 控制台（一次性，只有账号能做）：实例 → 仓库管理 → 代码源 → 绑定 GitHub；
   仓库的构建规则加两条——分支 `main` → 版本 `latest`、分支 `dev` → 版本 `dev`，
   构建上下文目录 `.`、Dockerfile 文件名 `Dockerfile`，并打开「代码变更自动构建」；
   「访问凭证」里设一个**固定密码**（不是阿里云登录密码）。
2. 宿主（一次性）：`docker login registry.cn-shanghai.aliyuncs.com`——凭据落 `~/.docker/config.json`，cron 才拿得到。
3. 部署 `.env` 加一行：`HERON_IMAGE=registry.cn-shanghai.aliyuncs.com/heron_docker/heron:latest`。
4. cron 每 5 分钟跑一次 `deploy/pull-latest.sh`：

```
*/5 * * * * HERON_DEPLOY_DIR=/docker/heron bash /docker/heron/src/deploy/pull-latest.sh >> /docker/heron/data/pull-latest.log 2>&1
```

- compose 里镜像写成 `${HERON_IMAGE:-heron:local}`：设了就是只拉不建，没设还是本地 build——
  **不接仓库的自部署者照样起**，ACR 因此是加速器不是前提（与 Redis 同一条规矩）。
- `dev` 是日常开发分支，`main` 才是发版线：ACR 不跑测试，推上去就构建、构建完就被拉走，
  所以 `main` 只接住过了 `pnpm check` 的提交。想把这台机器切成开发线，把 `HERON_IMAGE` 换成 `:dev`。
- 发版是「换容器」不是「原地更新」：新容器起不来，旧的一直服务到它起来为止。回滚就是把 `HERON_IMAGE`
  改回上一个版本号——除 `latest` 外再按 `v${build_number}` 留一条规则，不然 `latest` 只有一个版本可回。
- 个人版构建超时 30 分钟。`.npmrc` 没配国内源，`pnpm install` 走 registry.npmjs.org；真嫌慢就加
  `registry=https://registry.npmmirror.com` 一行——lockfile 只存 integrity、不存 tarball 地址，换源不用重生成它。
- 拉取失败时**不要**顺手 `docker compose up -d`：服务里有 `build:`，compose 会退到本地构建并把
  仓库地址打上 tag，于是「跑的是仓库里的哪一版」这件事就再也问不出来了。脚本里 `pull` 在 `up` 前面，
  拉不到就停手（实测报错：`pull access denied ... or may require 'docker login'`）。

## 安全边界（硬约束）

- `reference/` 是 oracle：heron 代码不 import 它，构建不读它，CI 不跑它。
- 会话只在同一个 HttpOnly cookie 里，响应体与 token 都不带口令哈希。一次会话只对应一个账号；租户与平台保留不同 JWT audience、账号记录及后端权限边界，不按邮箱合并。旧平台 cookie 不再用于认证。
  守卫每次请求都复查启用状态与 `sessionVersion`。修改密码、角色、停用或退出会使旧会话失效，不依赖等待 JWT 到期。
- 同源写请求校验 `Origin` 与服务端看到的协议/Host；反向代理必须保留 Host，公网部署必须配置 HTTPS 与安全 cookie。
- 抖音登录态在 `~/.heron/state/douyin/`，仓库与 DB 都不存 cookie 本体；DB 只存路径 + sha256 指纹，
  指纹不匹配就红，**绝不静默用一份来源不明的旧登录态去发东西**。
- 模型密钥只走环境变量，调用集中在一个 client 模块里。
- 发布重试前必须先核实"是不是其实已经发出去了"（`publishes.verified_at`），回执丢失不等于没发出去。

## 不做

微服务（拆库不等于拆服务，仍是 api + worker 两个进程）· Next.js/Nuxt（会把前端与版式拆成两套世界观）·
serverless（V8 isolate 没有子进程与 ffmpeg/Chromium）· 无人值守自动直发。

翻过的案：Postgres、Docker、多租户原本都在这份否决名单里，2026-10-22 按「往 SaaS 主流形态做」重议后
改为落地——决策、代价与顺序见 [docs/plans/2026-10-22-saas-multi-tenant-plan-1.md](docs/plans/2026-10-22-saas-multi-tenant-plan-1.md)。
队列中间件（Redis + BullMQ）同批翻案，但只让它当派发：job 里只有一个 taskId，状态机、租约、尝试次数
与闸痕全在 Postgres 的 tasks 表；REDIS_URL 不配时 worker 退回自己轮询，Redis 因此不是前提。
