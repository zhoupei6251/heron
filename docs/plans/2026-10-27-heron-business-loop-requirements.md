# heron 业务闭环需求文档（2026-10-27）

> 结论先行：**流程闭环是通的，业务闭环不是**。从「建任务」到「发布」的 9 个环节全部能跑完，
> 但其中 5 个是显式占位（写稿、核查、渲染、三道闸、发布），真实的只有鉴权、多租户、人工确认和部署。
> 这份文档把「还差什么」逐条落到文件和行上，并给出建议顺序；不含实现细节。

## 0. 什么叫业务闭环

一条新闻从**热榜上出现**走到**抖音上可见**，中间每道闸都有真实判定，人只做「确认」这一个动作。
判定标准（本文档的验收口径）：

1. 输入一个热榜条目，5 分钟内产出一条可审的成片；
2. 成片不合契约或视觉闸时被拒，界面能展开「哪条闸 + 输入片段 + 为什么」；
3. 人确认后自动发布，数据库里有回执与用量记录；
4. 重试发布前先查外部状态，绝不发两遍。

## 1. 现状盘点

| # | 环节 | 现状 | 证据 |
|---|---|---|---|
| 1 | 热榜采集 | **无**。`feedSources` 表与增删改查都在，但没有抓取任务，`lastFetchAt` 除 SQLite 导入外无人写 | `packages/db/src/pg/schema/tasks.ts:50` |
| 2 | 候选入库 | **无运行时入口**。`candidates` 表、`insertCandidate` 仓储齐全，但只被测试与一次性导入调用 | `packages/db/src/pg/candidates.ts:23` |
| 3 | 去重闸 | **真实现，但没跑过**。`normKey` 在入库时算，然而没有生产候选的路径 | `packages/db/src/pg/candidates.ts:19` |
| 4 | 认领 | **有，但认领的是任务不是候选**。任务靠 `POST /tasks` 手工建，入参只有 capability + title，界面写死 `p0-fake` | `packages/contracts/src/index.ts:206`、`apps/web/src/pages/console/CandidateFeed.vue:51` |
| 5 | 写稿 scripting | **无**。`walkTask` 只把 stage 往前推，全仓没有任何模型调用代码 | `apps/worker/src/pipeline.ts:117` |
| 6 | 核查 factcheck | **占位放行** | `apps/worker/src/pipeline.ts:35-42` |
| 7 | 渲染 rendering | **假文件**：写 `not-a-real-video` 与一份文本联络表；镜像里没有 ffmpeg，也没有 Chromium | `apps/worker/src/pipeline.ts:76`、`Dockerfile:42` |
| 8 | 契约 / 视觉 两道闸 | **占位放行**，`GET /gates` 报 `open:false`（发布按钮因此置灰，这是故意的） | `apps/worker/src/pipeline.ts:35` |
| 9 | 合规三件套 | **只有声明文案常量**，mp4 元数据与审计未接 | `packages/domain/src/gates/compliance.ts:6` |
| 10 | 人工确认 | **真的**：版本指纹比对、确认人/时间/指纹三者同落、确认后推队列 | `apps/api/src/review/review.service.ts:80` |
| 11 | 发布 publishing | **stub 回执** `p0-stub-receipt`；无抖音链路，无「重试前查外部状态」 | `apps/worker/src/pipeline.ts:105` |
| 12 | 用量与成本 | 表、聚合、页面都在，但 `recordUsage` **没有调用者** → 用量页永远是空的 | `packages/db/src/pg/usage.ts:25` |
| 13 | 资产保留清理 | 保留天数可设，清理任务没接（README 已承认） | `apps/api/src/settings/settings.service.ts` |
| 14 | capability 接缝 | 21 行 manifest，`layouts` 与 `promptKeys` 是空数组；版式与提示词无处声明 | `capabilities/news/src/index.ts:19-20` |

已完成、不在本次范围：认证与会话、RBAC、多租户 RLS、平台台、队列与租约、SSE、Docker/ACR 发版、审计日志。

## 2. 缺口清单（按依赖排序）

### R1 热榜采集
- **要做什么**：一个抓取任务（worker 内定时 + 手动触发），按 `feedSources.url` 拉取，解析成候选，
  同一条按 `normKey` 合并并累加 `hits` / `heat` / `rankPeak`，写 `lastFetchAt`。
- **验收**：造两条同标题不同来源的新闻 → 候选表里只有一条、`hits=2`；抓取失败要在 feed 上留下失败原因与下次重试时间，不静默。
- **依赖**：无（表和去重函数都在）。

### R2 候选 → 任务
- **要做什么**：认领对象从「任务」改成「候选」：候选详情页一个「开始生产」，生成一条带 `candidate_id` 的任务。
  手工建任务的入口保留，但去掉 `p0-fake` 写死。
- **验收**：同一条候选不能被两次认领生成两条在途任务；认领人与时间来自会话（接口上不能塞值）。
- **依赖**：R1（没候选就只能手工建）。

### R3 写稿 + 契约闸
- **要做什么**：单一 model client 模块（OpenAI 兼容 base URL，密钥只走环境变量），
  提示词在 capability manifest 里自声明；产出按契约 8 条校验，不合就退回并列出哪几条不过。
- **验收**：故意让模型少一个字段 → 任务被拒、`error_reason` 指出缺哪条；重试后稿子落库，字数/结构符合版式约束。
- **依赖**：模型供应商确定（见 §4-D2）。**已定：七牛云免费模型（OpenAI 兼容），换别家只改三个环境变量。**

### R4 渲染 + 视觉闸
- **要做什么**：稿子 → 版式 SFC 渲染 → headless 截图 → ffmpeg 合成 mp4 → 联系方式与联络表指纹入库；
  视觉几何闸（对比度 / 填幅 / 占位）在出片后判定。镜像补 ffmpeg 与 Chromium。
- **验收**：同一次渲染产出的 mp4 能与联络表逐格对上；改一版未重确认时系统拒绝进入 `approved`（这条人工确认已经拦得住，要保证渲染版本号递增）。
- **依赖**：R3；版式来源确定（见 §4-D3）。

### R5 合规三件套
- **要做什么**：mp4 写入 `AIGC` 元数据、画面/描述带「内容由AI生成」、审计留痕。
- **验收**：`ffprobe` 能读出 AIGC 标记；缺任一项，合规闸拒绝且不可跳过。
- **依赖**：R4。**必须在 R6 之前**，否则发出去的是无标识内容。

### R6 真实发布
- **要做什么**：发布适配器落地（按 §4-D1 的选择），幂等键沿用 `taskId:v版本号`，
  **重试前先查外部状态**，回执与失败原因入库。
- **验收**：用测试号/草稿模式跑通；人为断网后重试不会发两遍；回执丢失时以外部查询结果为准。
- **依赖**：R5。

### R7 用量与配额
- **要做什么**：模型调用与渲染处调用 `recordUsage`，租户级配额拦截。
- **验收**：跑一条完整任务后用量页出现 token 与次数；超额时任务在建单阶段就被拒并说明原因。
- **依赖**：R3、R4。

### R8 保留策略清理
- **要做什么**：按 `retentionDays` 清理 `data/videos/<task-id>`，渲染失败的 `.keep` 目录不参与。
- **验收**：把保留天数设成 0（或过去）→ 老目录被删、`.keep` 留着。
- **依赖**：无。

## 3. 建议顺序

| 里程碑 | 内容 | 完成后能做什么 |
|---|---|---|
| M1 | R3 | 手工建一条任务，能拿到真实稿子并看到契约拒稿原因 |
| M2 | R4 + R5 | 手工一条新闻 → 真片 → 审片台看真联络表 → 确认 |
| M3 | R6 | **闭环**：确认后真的发出去 |
| M4 | R1 + R2 | 入口自动化：热榜进来、人认领 |
| M5 | R7 + R8 | 运营面：成本可见、磁盘不涨 |

M1–M3 期间入口用手工建任务顶着，不影响验证闭环；M4 只是把入口从手工换成自动。

## 4. 需要拍板的三件事

- **D1 发布落点**：抖音开放平台 `video.create`（官方授权链路，需要应用审核与用户授权，稳定）vs
  Playwright 模拟网页上传（见效快，但随平台改版即碎，且有账号风控风险）。
  **建议开放平台**；Playwright 只在开放平台拿不到资质时作为降级，且必须写清楚它的脆弱点。
- **D2 模型供应商**：OpenAI 兼容 base URL 指向谁（方舟 / 通义 / DeepSeek / 本地）。选型只影响 env 与一个 client，
  但影响提示词与成本口径，越早定越好。
- **D3 版式来源**：`reference/` 是 oracle（只对照、不许 import），所以版式必须自己写；
  视觉阈值与版式结构以它为尺子。要不要先只做 1 套竖版 1080×1920，把 17 条不变量留到 P5。

## 5. 闭环验收（端到端一次）

1. 往 `feedSources` 加一个热榜源 → 下一轮抓取出现候选；
2. 人认领候选 → 任务自动走到 `review`；
3. 审片台看到成片与联络表，指纹与版本号一致 → 点确认；
4. worker 自动发布 → `publishes` 有回执、`usage_records` 有用量；
5. 抖音侧可见，且视频带 AIGC 标识与声明；
6. 故意让渲染失败一次：任务 `failed`，`error_reason` 指出是哪道闸、哪段输入、为什么。

六步全过 = 业务闭环完成。任何一步靠「占位放行」通过，都不算完成。

## 6. 这次不扩的东西

多 capability 平台抽象、第二套版式、多人协作冲突解决、移动端适配、通知渠道（邮件/IM）、
计费与支付。等闭环跑通再谈。

## 7. 实施进展（2026-10-27）

- **R1 热榜采集 — 已落地**：`packages/domain/src/feed.ts`（RSS/Atom/纯文本解析，零依赖）、
  `packages/db/src/pg/feeds.ts`（到期领取 CAS、同批合并、失败写 `last_error`）、
  `apps/worker/src/collect.ts` + `main.ts`（30s 定时、与渲染循环隔离、停机中止不记成源的错）、
  `POST /manage/feeds/:id/fetch` 与数据源页「立即采集」/「上次尝试」失败原因。
  测试覆盖：同标题两源 → 一条候选 `hits=2`；坏源留 502 且不推进 `last_fetch_at`。
- **R2 候选 → 任务 — 已落地**：`startProductionFromCandidate`（候选行 `FOR UPDATE`，同候选只许一条在途任务）、
  `POST /tasks/from-candidate/:id`（认领人来自会话，入队排在事务之后）、候选页「开始生产」。
  手工建任务保留，但请求体只剩 `{ title }`：capability 由服务端定为 `news`，界面不再写死 `p0-fake`。
- **R8 保留清理 — 已落地**：`listExpiredTaskDirs`（以「最后一版渲染」判过期，避免删掉新片）、
  `listTenantIds`（特权通道，租户名单）、`apps/worker/src/cleanup.ts`（每小时、按租户天数、`.keep` 跳过、
  路径必须在成片根内）。渲染行保留：发布单与审计仍指向它，文件没了但账要在。
  - 与 §R8 验收口径的一处偏离：保留天数**不接受 0**（`setRetentionDays` 拒绝非正整数），
    测试改用「1 天 + 回填旧时间戳」。理由：0 是一次误操作删光全部成片的功率，输入框不该有。
- **R3 写稿 + 契约闸 — 已落地（layout 无关部分）**：
  - 契约：`NewsScriptSchema`（title ≤30 字、2–8 镜、body 非空、onscreen 可选）在 `@heron/contracts`；
    判定在 `packages/domain/src/gates/contract.ts`（缺稿 / 字段形状 / title 与屏句去标点后相同）。
  - 存储：`scripts` 表（一任务一份，重写覆盖）+ 迁移 0003，走 `TABLES_WITHOUT_TENANT` 继承租户。
  - 模型：`apps/worker/src/model.ts` 单一 client；默认 `https://api.qnaigc.com/v1`。
    **页面配置（2026-10-27 追加）**：「工作空间设置 → 写稿模型」按租户配地址 / 模型 / API key，存 settings 表
    （键 `model_base_url` / `model_name` / `model_api_key`，RLS 隔离）；下拉预置七牛三个免费模型
    `qiniu/conatus-free`、`nvidia/nemotron-3-ultra-550b-a55b0-free`、`moonshotai/kimi-k3-free`，也可直接输入别家 id。
    优先级：页面 > 环境变量（`HERON_MODEL_BASE_URL` / `_API_KEY` / `_NAME`）> 默认地址；密钥留空提交表示不改。
    接口只回显 `hasApiKey`，密钥不出库；审计记 `model.updated`（地址 + 模型 + 是否换了密钥）。
    只有回环地址允许空密钥（本机 Ollama / LM Studio）。单次调用 50s 超时，短于 60s 租约。
  - 流水线：manifest 的 `promptKeys` 声明 `news.script.v1` 时，`scripting` 调模型、落稿、记 token 用量；
    `factcheck → rendering` 用真契约闸；未声明的 capability（`p0-fake`）保持占位放行。
  - 偏离：契约 8 条里与版式有关的几条（字号 / 填幅 / 领句位几何）要等 D3 layouts，现在不冒充已实现；
    token 用量只在供应商返回 `usage` 时记账，没返回记空，不拿 0 充数。
  - 待用户提供：七牛 API key（可在页面上填，或填 `.env` 的 `HERON_MODEL_API_KEY`）。
- **R4–R7 未做**：R4/R5 需要镜像补 ffmpeg/Chromium 与版式定稿（D3）；R6 等发布落点与测试号（D1）；
  R7 的用量记账已有 token 这一路调用者，配额与计费口径未做。
- **已知风险**：数据源地址没有出口白名单/解析后 IP 校验（SSRF）。单租户自部署可接受，
  开放注册或对外托管前必须补。
