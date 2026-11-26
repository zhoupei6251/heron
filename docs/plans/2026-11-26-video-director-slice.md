# 视频导演系统 · 垂直切片任务书（第一刀）

> 2026-11-26。上游：`docs/architecture/heron-video-director-adr.md`（架构）、
> `docs/plans/2026-11-26-video-director-contracts.md`（schema 与 TTS 前置接缝）、
> `docs/plans/2026-11-26-video-director-demolition.md`（拆除与重构清单）。
> 目标：**一条真实选题走通全链路**——含 TTS 前置对位、三层 QA 与镜头级返工闭环（Q8 裁决：核心承诺早验证）。

## 切片范围

| 维度 | 取值 | 说明 |
| --- | --- | --- |
| 选题 | `data_board` 线真实选题 1 条 | 走既有机会/洞察入口，不许人工编数据 |
| 画幅 | 仅 9:16（1080×1920） | 单画幅；16:9 等验证后放开 |
| Profile | 仅 `data-board` | 单 Profile；`tokens.css` 迁移即首个 Profile |
| 视觉模式 | 仅 `2d` | `VisualMode` 护栏强制 2D，不碰 Three.js |
| 镜头数 | ≤5 | `Storyboard` 闸首期限额 |
| 返工 | **含** | 镜头级返工闭环是本切片的验证主体，不可裁 |
| 发布 | 草稿池，人工验收 | 不接自动模式 |
| 多 Agent 自由聊天 | 禁止 | 两形态分阶段（ADR §三） |

## 阶段走查（十步全链路）

1. **入口与真值冻结**：`OpportunityFacts/Gate/Score` + `EditorialLineEditor`（去模板后）+ `DatasetStoryPlanner` → 物化 `assets/dataset.json` 与 `AssetManifest`
2. **叙事**：单次结构化 LLM 调用出 `NarrativeTreatment`（`StoryPlanContract` v2 演进），Java 校验
3. **视觉**：单次结构化 LLM 调用出 `VisualTreatment`，Java 校验 Profile 锚定与 `visualMode=2d`
4. **分镜**：单次结构化 LLM 调用出 `Storyboard`（≤5 镜），Java 校验（叙事对位、`techniques` 词汇、预算时长闸）
5. **声音先测（新增关键接缝）**：TTS 合成整片/分镜旁白 + `subs.vtt` 词级对齐 → 产 `audio-timing.json`（每镜精确 `targetShotDurSec` + `wordAnchors`）
6. **逐镜创作**：AgentScope per-session ReAct 循环 ×N 镜——按 `targetShotDurSec` 与 `dataset.json` 生成 standalone `<shotId>.html` → 跑第 ① 层静态检查（含 `hyperframes lint`）→ hyperframes 单镜渲染 `shots/<shotId>.mp4`；`maxIters=3` 起步
7. **三层 QA**：① 静态闸 → ② `shot-motion-check.mjs` + `VideoSignalCheck` 帧级量化 → ③ VLM 抽帧（4~6 帧对照 `dataset.json`）；`verdict` 写 `RenderReport`
8. **返工闭环**：`rework` 镜头带 `reworkBrief` 续跑该镜 ReAct 会话（同 `sessionId`），改码 → 重渲 → 复检（不重跑 TTS）；`giveup` 进人工
9. **成片合成**：Java 生成并校验 `edl.json` → FFmpeg 按 EDL 拼接各镜 `mp4`（`cut`/`fade`/`push`）+ `BgmCatalog` 选曲 + `SoundCue`/`SoundCueMixer` 混音 + `SubtitleBurn` 烧录 ASS + `NewsCover` 生成封面与方图 + AIGC 元数据
10. **出口**：成片 + 全部契约产物进草稿池；JEV / `ToolCallLedger` 全程留痕

## 验收判据

| # | 判据 | 挪不走 |
| --- | --- | --- |
| 1 | 全链路一次跑通，音画严格对位，成片可播、进草稿池 | |
| 2 | 至少 1 个镜头真实触发返工并复检通过（人工触发也认——改坏一帧再跑即得） | 返工闭环是验证主体 |
| 3 | 每个失败可答「哪条闸 + 哪镜 + 哪段输入」（JEV 可查） | 闸门铁律 |
| 4 | 生成代码 100% 过第 ① 层静态闸（`hyperframes lint` 零 error、令牌 `@import`、零硬编码、`__HERON_SHOT__` 声明、真值数据引用） | Profile 锚定与确定性实效 |
| 5 | 第 ② 层量化闸全镜达标（`maxStillSec ≤ 3s`、末拍稳定 ≥30 帧、闪烁 ≤1、无黑场） | anything2explainer 判据本地落地 |
| 6 | 成本可核算（每镜 `attempt` 数、token、渲染时长入 `RenderReport` + 成本闸联动） | 成本体系不回退 |

## 施工顺序（每步独立可验证，对应拆除清单施工顺序）

| 步 | 内容 | 依赖 |
| --- | --- | --- |
| S0 | **资产抢救与 B 类去耦合**：迁出 `NotoSansSC-Regular.otf`/`gsap.min.js` 到 `data/profiles/_shared/`；完成 `EditorialLineEditor`/`NewsCover`/`SubtitleBurn`/`BgmCatalog`/`SoundCue` 去模板改造（保留 `model-stub.mjs`） | 拆除清单 §施工顺序 1 |
| S1 | **拆除 A 类**：S1a 删构建链与模板目录 → S1b 删 Java 模板耦合类/旧 `StoryboardContract` + 清理 `heron-ui` 模板页并落位契约骨架 → S1c Flyway 迁移，每步构建绿灯 | S0 |
| S2 | **Profile 与知识资产落地**：`data/profiles/data-board/`（`tokens.css` 迁移 + `skeleton-rules.json` + `caption.css`）+ `ProfileRegistry` + 首批 `creative-knowledge/`（含 `diagram-design` 反模板规则） | 与 S1 可并行 |
| S3 | **契约与静态/量化检查器**：四新建契约 + `AudioTimingGate` Java 校验器（`violations()` 纯函数）+ `data/tools/shot-motion-check.mjs` + `ShotStaticCheck`（集成 `hyperframes lint`）+ 单测 | S2 |
| S4 | **Agent 执行面**：`ShotRenderTools` + `ShotDirectorAgentFactory`（沿 `ResearchAgentFactory` 模式，禁用 Skill/Memory/Sandbox）+ 单镜渲染封装 + 单测 | S2，可与 S3 并行 |
| S5 | **`DirectorPipeline` 编排**：串联十步流水线（含 TTS 前置、`VlmFrameReviewer`、镜头级返工闭环、FFmpeg EDL 拼片混音与成本联动） | S3 + S4 |
| S6 | **真实选题端到端验收**：跑真实 `data_board` 选题，按六条验收判据逐条过关 | S5 |
| S7 | **文档收口**：更新 `video_study.md` 导演架构结论、`CLAUDE.md` 模板章节改写为 Profile/导演产线口径 | S6 |

## 预算与风险

- **token 预算**：首跑上限沿成本闸既有配置；超限即停，不为跑通而放宽闸值。
- **风险 1 · VLM 审美闸误杀率未知**：首跑记录每镜 `issues` 分布，误杀判例回填 VLM prompt；不预设阈值调优。
- **风险 2 · 生成代码渲染/lint 失败率未知**：失败样例全部留存（`shots/` 不清），归因后才谈 prompt 修正。
- **风险 3 · 拆除期间前端模板管理页悬空**：S1b 删 `TemplateController` 时同步清理 `../heron-ui`（`src/views/heron/asset/template/index.vue`、`src/api/heron/template/*` 及类型引用），避免死链接与 TS 编译错误。
- **风险 4 · `StoryPlanContract` 演进破坏既有测试**：`NewsAutoDecisionTrailTest` 等沿改沿修，语义不变只改词汇源。

## 不做（本切片明确排除）

- 16:9 与其他 Profile；>5 镜头；自动发布；跨 Profile 组合；HTML 字幕组件（V1 走 `SubtitleBurn`）；Three.js/WebGL；参考片拆解与表现数据反哺回路（`TrendVideoDecomposer` / `MetricInsightAnalyzer` 接 `creative-knowledge/`，下一切片）；文章解说线迁移（切片跑通后）。
