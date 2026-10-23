# heron 项目鸟瞰

## 项目是什么

- **名称**: heron
- **定位**: 一台自己部署的机器上的新闻视频生产线——定时收热榜 → 人认领 → 写稿 / 核查 / 渲染全自动 → 人在网页点确认 → 服务端发布
- **入口**: `src/server`（Fastify，网页 + API + SSE）、`src/runner`（串行任务队列、渲染、发布）
- **规格书**: `docs/plans/2026-10-22-heron-news-webapp-design.md`（决策记录 N1–N6、五道闸、分期 P0–P5 都在里面；本鸟瞰与它冲突时以它为准）

## 技术栈

- 语言 / 运行时: TypeScript + Node **>= 22**（`engines.node` 写死，且启动自检断言 `process.version`）
- 前端 / 版式: Vue 3 + Vite，无组件库，原生 CSS。**版式与页面共用同一个 Vue 栈**（SFC → build → headless 截图 → ffmpeg 合成）
- 后端: Fastify 5（schema 校验 + 结构化日志是审计要求，不是舒适品）
- 持久化: better-sqlite3 + 手写迁移 SQL，不引 ORM；成片落 `data/videos/<task-id>/`（保留策略必须配）
- 队列: 进程内 + DB 租约（`status` / `lease_owner` / `heartbeat`），单 runner 串行——一次渲染 ≈ 60s 墙钟，串行是物理事实
- 模型调用: 单一 client 模块 + OpenAI 兼容 base URL，密钥走环境变量，别处不许散落调用
- 发布: Playwright 自研抖音上传；cookie 存工作目录之外（`~/.heron/state/douyin/`），DB 只存路径 + sha256 指纹

## 目录结构

```
heron/
├── src/shared/                    zod schema + 状态机类型 + 契约判定类型（前后端同一份）
├── src/server/                    Fastify：API + SSE + 静态托管 dist
├── src/web/                       Vite + Vue：四个页面 + 版式 SFC
├── src/runner/                    队列、渲染、发布
├── src/capabilities/news/         新闻这一个 capability（pipeline / 闸门 / 版式 / 提示词自声明）
├── fixtures/                      从 harness-factory 导出的 golden 判定样本
└── docs/plans/                    设计规格
```

## 状态机与五道闸

```
candidate → claimed → scripting → factcheck → rendering → review → approved → publishing → published
                                                                    ↘ failed(带原因)
```

`factcheck`、`review` 是**闸**不是步骤。五道闸：去重（norm_key）、契约（8 条）、视觉几何（对比度 / 填幅 / 占位）、合规三件套（mp4 元数据 `AIGC` + 声明「内容由AI生成」）、人工确认。
人工确认必须同时记录**确认人、确认时间、当时那张联络表的版本指纹**——少第三样就会出现"人审 A、系统发 B"。

## 编码约定

- **失败必须报原因**：`error_reason` = 哪条闸 + 输入片段 + 为什么拒，界面展开全文。只显示"失败"不合格。
- **不静默**：不吞异常、不自动改稿（LLM 产出不合契约就退回并列出哪几条不过）、渲染失败保留部分产物供诊断。
- **发布重试前先确认它是不是其实已经发出去了**——回执丢失不等于没发出去，直接重试就是发两遍。
- **闸门要能置灰界面**：未达断言密度门槛时仓库标 `experimental`，"发布"按钮读门禁位置灰。闸门点不动才算闸门。
- 抽象只在第二次出现时才做：现在只有 `news` 一个 capability，只留接缝（capability 注册表 / 表分层 / 发布适配器按平台切），不做平台抽象。

## 不做（否决过，别顺手加回来）

微服务与 Redis/Postgres/BullMQ · Docker（第一期）· Next.js（会把前端与版式拆成两套世界观）· 多租户 · Cloudflare Workers / serverless（V8 isolate 无子进程与 ffmpeg/Chromium，且数据中心 IP 对平台风控不利）· 无人值守自动直发 · Tailwind。
