# heron 项目鸟瞰

## 项目是什么

- **名称**: heron
- **定位**: 一台自己部署的机器上的新闻视频生产线——定时收热榜 → 人认领 → 写稿 / 核查 / 渲染全自动 → 人在网页点确认 → 服务端发布。按**多租户 SaaS** 做（租户台 + 平台台两套控制台），目标是开源
- **入口**: `apps/api`（NestJS：REST + SSE + 托管 web/dist）、`apps/worker`（租约调度、渲染、发布）、`apps/web`（Vue 两个控制台）
- **规格书**: `docs/plans/`——`2026-10-22-heron-news-webapp-design.md`（决策记录 N1–N6、五道闸、分期 P0–P5）、`2026-10-22-heron-stack-v2-design.md`（技术栈与表设计）、`2026-10-22-saas-multi-tenant-plan-1.md`（多租户）、`2026-10-27-heron-business-loop-requirements.md`（**业务闭环还缺什么，按它排期**）；与本鸟瞰冲突时以规格书为准

## 技术栈

- 语言 / 运行时: TypeScript + Node **>= 24**（`engines` 写死，pnpm 12 workspace）
- 前端 / 版式: Vue 3 + Vite + **Tailwind 4 + Element Plus**。**版式与页面共用同一个 Vue 栈**（SFC → build → headless 截图 → ffmpeg 合成）
- 后端: **NestJS**（REST + SSE；schema 校验与结构化日志是审计要求，不是舒适品）
- 持久化: **Postgres + Drizzle + RLS**（`@heron/db/pg`，`withTenant` 是设租户上下文的单点）；测试与开发台用 pglite，pg 主版本对齐 18；成片落 `data/videos/<task-id>/`
- 队列: **BullMQ（Redis）只派发 taskId**，账本仍在 Postgres；不配 Redis 就退回 DB 租约轮询（`lease_owner` / `heartbeat`），单 worker 串行
- 部署: Docker 单镜像跑 api + worker（同一个 `Dockerfile`），ACR 云端构建 + 宿主 cron 拉取重建
- **已经接上**: 热榜定时采集（worker 30s 扫到期源 + 数据源页手动触发，失败原因留在 feed 上）、候选直接「开始生产」、按租户保留天数清理成片目录（`.keep` 不清）、写稿模型调用（`apps/worker/src/model.ts` 单一 OpenAI 兼容 client，换供应商只改三个环境变量；稿子落 `scripts`，token 用量落用量台账）、与版式无关的契约闸（缺稿 / 字段形状 / title 与屏句重复）
- **还没接**（规格书里有、代码里还没有）: 视觉/合规两道闸的尺子（R4–R5）、与版式有关的契约（字号 / 填幅 / 领句位几何，要等 layouts）、真渲染（`walkTask` 目前写假 mp4 与 stub 回执）、真实发布（R6）、计费（R7，用量记账已有调用者，但还没有额度与计费口径）

## 目录结构

```
heron/
├── apps/api/                 NestJS：REST + SSE + 静态托管 dist（按域分目录）
├── apps/worker/              租约调度、BullMQ 派发、写稿（OpenAI 兼容 client）、流水线推进、渲染与发布
├── apps/web/                 Vite + Vue：pages/console 租户台、pages/platform 平台台、entries/demo 版式入口
├── packages/contracts/       zod schema + 状态机类型 + 契约判定类型（前后端同一份）
├── packages/domain/          五道闸纯函数与状态机规则
├── packages/db/              Postgres + Drizzle + RLS、迁移、pglite 入口
├── packages/redis/           连接与探针（BullMQ 派发、登录失败计数）
├── capabilities/news/        新闻这一个 capability 的 manifest（promptKeys 声明写稿提示词；layouts 仍空）
├── deploy/                   docker-compose.yml + pull-latest.sh
├── docs/plans/               设计规格
└── reference/                oracle：只对照用，代码不许 import、构建不许读
```

## 状态机与五道闸

```
candidate → claimed → scripting → factcheck → rendering → review → approved → publishing → published
                                                                    ↘ failed(带原因)
```

`factcheck`、`review` 是**闸**不是步骤。五道闸：去重（norm_key）、契约（8 条；目前落地与版式无关的 3 条——缺稿 / 字段形状 / title 与屏句重复，几何几条要等 layouts）、视觉几何（对比度 / 填幅 / 占位）、合规三件套（mp4 元数据 `AIGC` + 声明「内容由AI生成」）、人工确认。
人工确认必须同时记录**确认人、确认时间、当时那张联络表的版本指纹**——少第三样就会出现"人审 A、系统发 B"。

## 编码约定

- **失败必须报原因**：`error_reason` = 哪条闸 + 输入片段 + 为什么拒，界面展开全文。只显示"失败"不合格。
- **不静默**：不吞异常、不自动改稿（LLM 产出不合契约就退回并列出哪几条不过）、渲染失败保留部分产物供诊断。
- **发布重试前先确认它是不是其实已经发出去了**——回执丢失不等于没发出去，直接重试就是发两遍。
- **闸门要能置灰界面**：未达断言密度门槛时仓库标 `experimental`，"发布"按钮读门禁位置灰。闸门点不动才算闸门。
- 抽象只在第二次出现时才做：现在只有 `news` 一个 capability，只留接缝（capability 注册表 / 表分层 / 发布适配器按平台切），不做平台抽象。

## 不做（否决过，别顺手加回来）

微服务拆分（两个进程是为了故障隔离，够用）· Next.js（会把前端与版式拆成两套世界观）· Cloudflare Workers / serverless（V8 isolate 无子进程与 ffmpeg / Chromium，且数据中心 IP 对平台风控不利）· 无人值守自动直发（人的确认是闸，不是可选项）· 为第二个 capability 预先做平台抽象（现在只有一个，只留接缝）。

> Postgres、Redis/BullMQ、Docker 与多租户原本在这张否决榜上，2026-10-22 已经翻案并落地——别再拿旧文档当约束。
