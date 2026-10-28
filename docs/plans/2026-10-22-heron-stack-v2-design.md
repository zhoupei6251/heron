# heron — 技术栈与架构 v2

日期：2026-10-22 · 状态：**已批准，P0 开工**
作用域：只覆盖原规格书的 **§2 形状 / §3 选型 / 目录结构**。
五道闸、状态机、数据模型语义、分期判据、§8 接缝、§10 风险全部继承原文档，不重述。
原始文档：`2026-10-22-heron-news-webapp-design.md`

---

## 0. 与 v1 的关系

| 项 | v1 | v2 |
|---|---|---|
| 后端框架 | Fastify 5 | **NestJS 12.2.0** |
| 运行时下限 | `>=22` | **`>=24`（锁 24.22.1 LTS Krypton）** |
| 语言版本 | “锁稳定大版本再验证” | **TypeScript 7.0.2 + P0 三绿门禁 + 5.9.3 回退线** |
| 样式 | 无组件库、原生 CSS、否决 Tailwind | **Tailwind 4.3.3 + Element Plus 2.14.7**（带围栏，见 §3） |
| 包布局 | `src/*` 单包 | **pnpm workspace：3 apps + 3 packages** |

N1–N6 六项决策**全部保留不变**。`不做` 清单里只有一处被这次讨论改写：
**Tailwind 从“否决”变为“采用但围栏”**，版式目录仍在豁免区。

---

## 1. 排除项及理由（防止被重新提出）

**排除 Nuxt。** 不是审美：

1. Nuxt 接管 Vite 配置与产物目录，而版式的契约是“带导出符号的 ES bundle +
   `host.html` loader 调 `mod.mount(target, compId)`”
   （证据：`reference/from-harness-factory/vite.config.ts:70`、`:88` `preserveEntrySignatures: "strict"`）。
   框架产物结构容纳不下这条 strict-signature 多入口库构建，只能把版式拆成独立包——
   即拆掉 v1 §3 那笔“前端与版式共用一个 Vue 栈”的最大省心智。
2. 常驻进程从 2 个变 3 个（Nest + Nitro + runner）。
3. SSR 在此无收益：无公开 SEO、tailscale 私网访问、页面全是 SSE 推的实时任务态。

**排除现成 admin 脚手架**（vben / soybean / pure-admin）。它们默认携带登录 + RBAC + 多标签 +
主题 + i18n，而本项目明确不做鉴权与多租户；且接管 `vite.config` 与目录约定、依赖 auto-import 魔法。
要删的比留下的多。它们的正当用途是**交互参考**（表格 + 进度 + 抽屉预览怎么写），不是骨架。

**不选 Reka UI**（用户裁决选 EP）。技术判据上它与 Tailwind 同世界观、更契合审计可读性；
此决定以落地速度换取样式主权共存成本，代价在 §3 用具体机制兜住。

---

## 2. 定案版本（2026-10-22 实测 npm `dist-tags.latest`）

| 类别 | 包 | 版本 | 实测约束 |
|---|---|---|---|
| 运行时 | Node | **24.22.1** | LTS *Krypton*（2026-10-20）。Current 线 26.12.1 不选：常驻自部署机器要 LTS |
| | npm / corepack | 11.19.1 / 0.36.0 | pnpm 由 corepack 启 |
| | pnpm | 12.14.0 | |
| 语言 | typescript | **5.9.3**（不是 latest 7.0.2） | 见 §5「TS 7 的实测结论」：`latest` 是 7.0.2，但 vue-tsc 3.3.13 加载不了它 |
| 前端 | vue | 3.5.44 | |
| | vite | 8.3.5 | `^20.19.0 \|\| >=22.12.0` |
| | @vitejs/plugin-vue | 6.0.10 | 同上 |
| | vue-router | 5.4.0 | |
| | pinia | 4.1.0 | |
| | @vueuse/core | 15.0.0 | 与 EP 内部依赖同版，不会装出两份 |
| 样式 | tailwindcss / @tailwindcss/vite | 4.3.3 | plugin peer `vite ^5.2\|\|^6\|\|^7\|\|^8` ✓ |
| | element-plus | 2.14.7 | peer `vue ^3.3.7` ✓ |
| | @element-plus/icons-vue | 2.3.2 | |
| 后端 | @nestjs/core | 12.2.0 | `engines >= 20` |
| 契约 | zod | 4.6.7 | |
| 存储 | postgres + drizzle-orm | 3.4.9 / 0.45.4 | RLS 隔离；测试用 pglite 0.5.11（实测 PostgreSQL 18.6）；T8 已删 better-sqlite3 |
| 渲染/发布 | playwright | 1.64.1 | 截图与抖音上传共用一个浏览器依赖，不引 Puppeteer |
| | gsap | 3.15.0 | 版式 timeline |
| 测试 | vitest | 5.0.5 | **`^22.12 \|\| ^24 \|\| >=26`**（Node 20 直接不支持） |
| 工具链 | tsx | 4.23.15 | |
| | vue-tsc | 3.3.13 | peer `typescript >=5.0.0`，但实现靠 `require.resolve('typescript/lib/tsc')` —— TS 7 无此导出，见 §5 |
| 规范闸 | eslint | 10.13.0 | flat config；`typescript-eslint 8.72.0`、`eslint-plugin-vue 10.11.1`、`vue-eslint-parser 10.4.1` 的 peer 都已声明支持 ESLint `^10` |
| | prettier | 3.9.10 | `eslint-config-prettier 10.1.8` 放最后一段，格式化不与 lint 抢规则 |

**规范闸的三条边界。** ① 用 `recommendedTypeChecked`（带类型感知），因为本案要拦的是"漏了 await 的
租约续期"这类错误，不是空格分号。② `reference/` 与 `harness-factory/` 在 `ignores` 与 `.prettierignore`
里，`reference/` 同时补进 `.gitignore`——它是本地尺子，进 heron 的提交历史就等于把 oracle 焊死在产物里。
③ 唯一一条豁免：挂载入口（`apps/web/src/main.ts`、`entries/*/main.ts`）关掉 `no-unsafe-argument`，
因为 tsserver 不解析 `.vue`，SFC 导出在 lint 程序里是 error type；这条判据真正的落点是 `vue-tsc`。
命令：`pnpm check` = `format:check → lint → typecheck → test`。

---

## 3. Tailwind 与 Element Plus 共存的两处围栏

**围栏一 · preflight 必须关。** Tailwind v4 的 preflight 属于基底重置层，会盖掉 Element Plus 的
组件基底样式。处置：应用样式入口不写 `@import "tailwindcss"`，改为只引入 theme 与 utilities 两层
（跳过 preflight），Tailwind 只用于间距/布局/排版工具类，组件外观主权完全交给 EP。

**围栏二 · 版式豁免。** `apps/web/src/entries/**` 永不 import 应用那份 CSS 入口；
EP 的 `dist/index.css` 只在 `main.ts` 应用入口 import 一次；审片台预览只通过 `dist/<name>.html`
的 iframe 嵌入。Tailwind 亦配 `source(none)` + 显式 `@source` 只扫 `src/pages/**`、`src/components/**`。
三重保证 v1 §3 反对 Tailwind 的那句“默认 token 跟实现对打”被关在应用外壳之外，
版式几何闸（§5 第三道闸）的尺子不受任何影响。

**不引入 auto-import。** `unplugin-auto-import` / `unplugin-vue-components` 均不装。
EP 组件显式 `import`，样式全量一次引入。理由：本项目法则要求“失败能展开全文、闸门可读”，
目录魔法与隐式符号会增加人和 AI 的导航成本；内部工具的 bundle size 不是约束。

---

## 4. 目录与架构

```
heron/
├─ apps/
│  ├─ api/      NestJS 12：REST + @Sse 进度 + 静态托管 web/dist + @nestjs/schedule 抓榜入队
│  │  └─ src/
│  │     ├─ main.ts / app.module.ts        启动、SPA 回退、全局前缀、装配五个域模块
│  │     ├─ config/runtime.ts              WEB_DIST + PORT（只有 api 才有的两个事实）
│  │     ├─ common/pipes/                  ZodValidationPipe（请求体进契约的唯一闸）
│  │     └─ tasks/ gates/ settings/        每域一个目录：controller + service + module
│  │        events/ health/
│  ├─ worker/   单 worker：领租约 → 写稿/截图/合成/发布；到 review 放租约等人
│  │     ├─ src/config.ts                  OWNER + MAX_ATTEMPTS（worker 自己的身份与重试上限）
│  │     ├─ src/scheduler.ts               跨租户领活与租约回收（唯一允许拿特权通道的文件）
│  │     └─ src/db.ts                      连库并等 api 把 schema 建出来（worker 不迁移）
│  └─ web/      Vite 8 + Vue 3.5 + Router 5 + Pinia 4 + Tailwind 4 + Element Plus
│     └─ src/
│        ├─ pages/          候选流 / 生产台 / 审片台 / 发布台 / 设置
│        ├─ components/     应用外壳组件
│        ├─ api/            http.ts(fetch + ApiError) + tasks/gates/settings 各域
│        ├─ composables/    useTaskStream（SSE 订阅）
│        ├─ router/ stores/ 路由与 Pinia
│        ├─ styles/app.css  Tailwind（无 preflight）
│        └─ entries/<pack>/ 版式 SFC：main.ts 导出 mount + main.html 预览页
│        └─ host.html       渲染器 loader（调 mod.mount）
├─ packages/
│  ├─ contracts/  zod 4：DTO + 十态状态机 + 判定类型 + capability manifest 类型
│  ├─ domain/     五道闸纯函数 + 租约与转移规则（去重/契约/视觉/合规/人工确认）
│  └─ db/         Postgres + Drizzle + RLS：src/pg/ 是数据面，drizzle/ 是生成迁移
│                 src/home.ts：REPO_ROOT / HERON_HOME / VIDEOS_DIR / DATABASE_URL / .env 加载（唯一一处）
├─ capabilities/news/  这一个 capability 的提示词 + manifest + 版式清单
├─ fixtures/           golden 判定样本（oracle 靶子）
├─ data/videos/<task-id>/
└─ pnpm-workspace.yaml  tsconfig.base.json  .nvmrc  .npmrc(engine-strict=true)
```

**api 的分域切法。** 按资源切（tasks/gates/settings/events/health），不按"层"切——层切会把
一个用例的判定散在三个目录里，而本案每条转移的理由必须一眼能追到。`health` 是真探针不是心跳：
写一行 `settings` 再读回来（只 SELECT 探不出 WAL 锁、磁盘满、迁移没跑），`web/dist` 缺文件即
`ok:false`；**门禁位关闭不算不健康**（进程健康 ≠ 允许发布，混在一起会把「尺子还没搬齐」误报成
「服务挂了」）。`GET /api/health` 失败时带整份报告抛 503，理由必须在响应体里。

**「数据在哪」只许有一处计算。** 初版 api 数了四层 `..`、runner 数了三层，两个进程各自算
路径——这类错误不报错，只是两个进程悄悄指向两个位置，比崩掉难查。现在数据目录、租约 TTL、
`DATABASE_URL` 与 `.env` 加载都在 `packages/db/src/home.ts`，`WEB_DIST`/`PORT` 归 api，
`OWNER`/`MAX_ATTEMPTS` 归 worker。`.env` 由该模块在首次 import 时读，**真环境变量优先于文件**
（生产由 systemd/shell 注入）；`HERON_HOME` 必须是绝对路径——相对值按进程工作目录解析，
两个终端启动就落到两处。库连接由 `DATABASE_URL` 一个值决定，两个进程没有第二处可算：
api 与 worker 都读同一份环境，容器里由 compose 注入。

**承重点一 · 闸门只有一份实现。** 判定逻辑是 `packages/domain` 的纯函数，两个调用方：
api 读它算门禁位状态（“发布”按钮置灰规则），worker 用它拦 stage 转移。写进任一侧都会让另一侧
变成第二份真相——这是 `packages/` 必须存在的唯一理由，也是 §8 接缝 1、2 的落点。

**承重点二 · 版式不拆包，但产物单向流动。** 版式与页面共用同一 Vite 根与 `vite.config.ts`；
worker 只消费 `apps/web/dist/` 的 bundle 与 `host.html`，**永不 import 源码**。构建顺序固定：
`web build` → api 托管 / worker 截图。

**进程边界维持 v1 §2：两个进程。** api 与 worker 分离不是微服务，是故障隔离：
Chromium/ffmpeg 崩溃不应带走 API。`@nestjs/schedule` 只负责“抓榜 + 入队”这类轻量动作，
重活一律在 worker。capability 注册表只能有一处（`packages/contracts` 定义类型，
`capabilities/news/manifest.ts` 声明），api 与 worker 各自只读自己需要的部分。

---

## 5. P0 门禁（本次初始化的出口判据）

1. `node -v` 为 24.22.1；`.npmrc` 开 `engine-strict=true`；api 启动断言 `process.version`。
2. **TS 7 三绿**：`tsc --noEmit` + `vue-tsc --noEmit` + Nest 编译。若 vue-tsc 3.3.13 或装饰器
   emit 在原生编译器线阻塞，按 v1 §3 那条警告退回 **5.9.3**，并在本文档追加退回原因——
   **退回是允许的，静默放宽门禁不是**。
3. `web build` 产出 `dist/vue/<pack>.js` 中确实含导出的 `mount`（v1 记录过：只把 HTML 当入口时
   产物无 `export{}`，渲染期报 `mod.mount is not a function`）。
4. runner 领一条假任务，走完 `candidate → published` 全转移并落库原因字段——即 v1 §9 P0 的出口
   判据“空 capability 也能跑完一条假任务”。

### 实测结果（2026-10-22，`pnpm typecheck` / `pnpm test` / `pnpm web:build` / `pnpm p0` 全绿）

- **TS 7 退回已执行**，原因就是上面预感的那条，实测证据：
  `vue-tsc@3.3.13` 启动时 `require.resolve('typescript/lib/tsc')`，而 typescript@7.0.2 的
  `exports` 只放 `.` 与 `./unstable/*`，没有 `./lib/tsc` → `ERR_PACKAGE_PATH_NOT_EXPORTED`。
  原生编译器本身跑 `tsc -p` 是通的（contracts / domain / news 三个包在 7.0.2 下先绿过一次），
  卡的是 Vue SFC 类型检查这条线——而版式与前端同栈是本案最不能让步的结构（v1 §3），
  所以整仓退回 5.9.3，不是只给 web 装一份旧编译器。**换回 7.x 的触发条件：vue-tsc 或
  `@vue/language-core` 适配 TS 7 的 `./unstable/*` API**。tsconfig 已按 TS 7 的要求写
  （无 `baseUrl`、`paths` 用相对路径、`module/moduleResolution: nodenext`），届时要动的只有版本号。
- 判据 3 已验：`apps/web/dist/vue/demo.js` 内容为 `export{t as mount}from"./chunks/main-*.js"`，
  独立预览页 `dist/demo.html` 由 closeBundle 插件从 `dist/src/entries/demo/main.html` 搬平；
  版式产物 CSS 440 字节且不含任何工具类（围栏二成立），应用产物含 `.max-w-4xl` 等类且**无 preflight**。
- 判据 4 已验，另加两条：认领前 7s 内 runner 不动它（N5 生效）；重复认领返回
  `400 非法状态转移 published -> claimed`。
- **Nest 的构造注入必须写显式 `@Inject(Service)`**：`tsx`/esbuild 不执行 `emitDecoratorMetadata`，
  tsconfig.base 里那行对 api 运行时是摆设；只靠参数类型的隐式注入会在 `NestFactory.create` 时
  报 `Nest can't resolve dependencies`。显式 token 自带元数据，实测 tsx 起服务、路由全部映射、
  八个端点与 SSE 帧全通。以后谁把 `@Inject` 删成裸类型，运行期才炸——所以这条写在这，不靠记性。

### 验证期间改掉的三处（不是洁癖，是判据不成立）

1. **runner 会抢未被认领的 candidate**：`claimNextTask` 原 SQL 只排除终态，等于 N5 的「人认领才生产」
   形同虚设；同时 API 的认领只打租约不改状态，界面上那枚按钮是装饰。现改为：认领 =
   `candidate → claimed` 的状态转移并记认领人，runner 只领 `claimed` 及之后的阶段（不占租约，
   租约仍由 runner 领取时打）。
2. **SSE 只有帧没有载荷**：Nest 的 SSE 消息形状是 `{type,id,data}`，直接把 `TaskEvent`（含
   `type: "task.updated"`）丢进 Observable，会被当成"有事件名、无 data"，浏览器收不到消息。
   现 emit `{ data: taskEvent }`。
3. **闸的放行说明串错了字段**：台账 `reason` 列同时装"拒绝原因"和"占位放行说明"，若照原名进前端就叫
   `errorReason`，健康任务会渲染成红色错误块。现 `TaskEvent.reason` 与 `Task.errorReason` 分开，
   前端只在 `to === "failed"` 时回读任务详情；占位闸必须自带 `note`，落进台账写成
   `闸 contract 放行（占位放行：尺子还在 harness-factory，门禁位不因此开启）`。

---

## 6. 新增风险（追加进 v1 §10）

- **pnpm 11+ 的构建脚本白名单换了键名**：`onlyBuiltDependencies` 已被 `allowBuilds`（`包名: true`）
  取代，写错的后果不是报错而是**静默跳过生命周期脚本**，装完 `better-sqlite3`/`esbuild` 缺二进制。
  heron 的 `.npmrc` 保留 `engine-strict=true`，遇 ignored-builds 直接失败退出，比静默好。
  另注：better-sqlite3 13.0.3 走 `prebuilds/` 不需要编译，白名单里留着它是为了防止以后换版本被跳过。
- **DB 时间口径（已收口，见 §7）**：原 `datetime('now')` 存的是 `2026-10-22 08:13:41` 这种无时区
  后缀的形状，前端 `new Date()` 会按本地解析，台账/认领/确认三张表都差 8 小时。现在库里只允许
  `nowIso()` 写入的 `2026-10-22T09:14:12.129Z`，迁移文件的 DEFAULT 用
  `strftime('%Y-%m-%dT%H:%M:%fZ','now')` 生成同一形状；`db.test.ts` 用正则守着这个形状。
- **本机 in-app 浏览器无可视面**：P0 的 UI 走查由可访问性树 + 脚本点击完成，指针类交互（真实 hover、
  EP 弹层层叠）没验。P2 审片台前要在真实浏览器里过一遍。

- **样式双系统共存**：EP 的 `--el-*` 变量与 Tailwind 工具类同处应用外壳。已用围栏一压制基底冲突，
  但组件内部尺寸仍须走 EP 主题变量而非工具类。约定：**组件外观改 EP 变量，布局间距用 Tailwind 类**。
- **HyperFrames 不可 import**：v1 §3 的目录注释里 runner 用“HyperFrames 渲染”，但
  `reference/README.md` 的硬规矩是 heron 不许 import 它。故 P1 的渲染通路要在 heron 内自建
  （Playwright 截图 + ffmpeg 合成），HyperFrames 只作行为参照。
- **本机 nvm 全局切换**：`nvm use 24.22.1` 改的是本机所有项目的默认 node（用户已授权）。
  其它项目若锁 node 20，需各自 `nvm use` 回切。
- **npm 10 → 11**：随 Node 24 装的是 npm 11.19.1，全局 registry 行为（如 `engine-strict`）随之变化。

---

## 7. 数据模型 v2（取代 v1 §4 那张草表）

字段形状取自 harness-factory 的真实产物（`ledger.jsonl`、`t003-scenes.json`、
`evidence/*.md`、`aigc.json` sidecar、版式普查 csv），不是想象出来的列。

**core（不许出现任何新闻字段）**

| 表 | 职责 |
|---|---|
| `settings` | 保留天数等 KV；健康探针也写这里（写真行才探得出手锁） |
| `feed_sources` | 热榜抓取源、频率、上次抓取时间（原名 `sources`，把名字让给 news 的出处表） |
| `candidates` | 台账现状视图：`norm_key` 唯一、`rank_peak/hits/first_seen_at/last_seen_at`、`status` |
| `tasks` | 十态 stage + 租约三列 + `attempts` + **`claimed_by/claimed_at`**（认领人不再只活在文案里） |
| `task_events` | 只追加台账，`reason` 闸放行也写 |
| `gate_results` | 每次闸判定一行：`passed/note/rejections(JSON)`，界面展开全文与 oracle 对账的数据源 |
| `renders` | 版本、pack、mp4 路径与 sha256、字节数、宽高/fps/时长、联络表 `fingerprint`、sidecar 路径、各阶段耗时 |
| `confirmations` | 确认三样：人、时间、当时联络表指纹（v1 §5 的硬要求） |
| `publishes` | 平台、幂等键、声明、**登录态路径 + sha256**、状态、`post_url`、`verified_at` |
| `aigc_posture` | 角标/声明姿态的历史全量，"现行最多一条"由唯一索引兜 |

**capabilities/news（外挂表，表名一律带 `news_` 前缀：所有 capability 同库，靠前缀分域）**

`news_drafts`（稿件版本）→ `news_shots`（逐镜，`payload` 就是那个 scene 对象）；
`news_sources`（S1..Sn 出处清单）→ `news_fact_checks`（整体 `passed/flagged`）→
`news_fact_check_items`（逐句：稿件表述 · 核查结果 · 来源 · 判定 · 处理说明）；
`news_images`（授权/分级/人脸，合规与肖像风险的原始证据）。

**约束即判据（写进 DDL，不靠自觉）**

- `news_fact_check_items`：`verdict='confirmed'` 必须 `json_array_length(source_labels) >= 2`——
  harness 那张表的图例规则（≥2 源才算已证实，单源标 ⚠️）。
- `publishes`：`credential_path` 与 `credential_sha256` 必须同生同死（P0 假发布两列皆空，
  P3 真发布必须有，缺一个就是 CHECK 红）。
- `news_shots` / `news_fact_checks`：复合外键指向 `news_drafts(task_id, version)`，逐镜不许挂在不存在的稿版上。
- `gate_results.rejections`、`renders.timings`、`news_shots.payload`：`json_valid` 守住，
  半截 JSON 进不了库。
- `renders(task_id, version)`、`candidates(norm_key)`、`publishes(idempotency_key)` 唯一。

**对 v1 草表的三处修正**

1. **删 `cost`**：harness 全仓没有任何花费字段，模型侧也没采 token 用量。留一个恒为 0 的列，
   将来只会被读成"这条片子免费"。要计成本，先让调用模型的那一层真的返回用量再加分支。
2. **`ContentProducer` 从 `renders` 移走**：它是 `aigc.json` sidecar 里的元数据键（合规三件套之一），
   不是渲染属性；`renders` 只存 sidecar 路径，键值由合规闸去断言。
3. **`hit_words` 换成台账真实列**：harness 的台账里没有命中词，有的是 `rank_peak/hits/first_seen/last_seen`，
   去重与候选排序靠这三样。

**`norm_key` 与 oracle 对齐**：改成与 `ledger.py` 的 `re.sub(r"[\W_]+", "", title)` 逐字符等价
（`title.replace(/[^\p{L}\p{N}]/gu, "")`），**取消原来的小写折叠**——折叠要先改 `ledger.py`
再重生成 fixtures，两边一起动，否则 §6 那条 oracle diff 非空即红直接爆。

**索引按查询形状建（2026-10-28，迁移 `0005_tenant_first_indexes.sql` / `0006_list_order_indexes.sql`）**：
租户列表查询的形状都是 `tenant_id` 等值 + 时间倒序 + `id` 兜平同一时刻，索引因此一律 tenant-first：
`ix_candidates_tenant_recent(tenant_id, last_seen_at DESC, id)`、`ix_tasks_tenant_recent`、
`ix_publishes_tenant_recent`、`ix_audit_logs_tenant_time`，以及 `0006` 补上的成员、数据源两条。
平台审计没有租户维度，同形只按 `created_at DESC, id`（`ix_platform_audit_logs_time`）。
平台租户列表与成片库也没有租户可等值（前者是平台视角，后者是子表、租户由 join tasks 过滤），
只按 `created_at DESC, id`：`ix_tenants_recent`、`ix_renders_recent`。原先按 `status` 单列的三条索引删了：
状态取值少、选择度低，而 RLS 的每次查询都已经先过 `tenant_id`。调度是唯一跨租户的读，保留部分索引
`ix_tasks_claimable(created_at) WHERE stage NOT IN ('published','failed')`——领取查询都是
「非终态 + 租约过期，取最早创建」，前缀列不能带 `tenant_id`，否则每轮轮询都要全表扫再排序。

**本期读写状态**：`gate_results` 从 P0 起就在写，读它的界面在 P2；news 五张表由 `db.test.ts`
建表并验约束，写它们的是 P1 的写稿通路；运行期按 capability 清单决定挂哪组迁移同样是 P1 接缝
（现在两个进程都只 `migrate(db, ["core"])`）。
