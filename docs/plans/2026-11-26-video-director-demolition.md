# 视频导演系统 · 模板产线拆除与重构清单

> 2026-11-26。依据《视频导演系统 ADR（V2）》：开发阶段，模板产线整体删除，不双轨不兜底。
> 三类资产：**A 删（模板本体与旧契约）** / **B 留与重构（生产基础设施，含原争议项已定案归属）** / **C 改挂（知识与令牌资产换角色续用）**。
> 本清单是**施工图**，执行时逐项勾；git 历史可找回一切已提交内容。

---

## A 类 · 删除（模板中心架构本体）

### A1. 模板 composition 数据（`data/templates/`）

> **前置迁移警告**：执行本节删除前，必须先把模板目录下共用的本地字体（`data/templates/*/fonts/NotoSansSC-Regular.otf`）与前端动效库（`gsap.min.js`、`three.min.js`）提取到 `data/profiles/_shared/`（见 C 类），否则单镜渲染无法满足 HyperFrames 本地 `@font-face` 规则。

| 目标 | 说明 |
| --- | --- |
| `data/templates/news-16x9/` `news-9x16/` `news-data-16x9/` `news-data-3d-9x16/` `news-data-9x16/` `news-minimal-9x16/` `news-rank-9x16/` `news-trend-9x16/` `mood-night-9x16/` | 9 套模板目录全部（含各自 `.hyperframes/`、`.thumbnails/` 产物缓存） |
| `data/templates/layouts/article/` `data-board/` `data-investigation/` `mood/` `sprint-board/` | 模板级布局 CSS（`build-templates` 消费方随模板消失） |
| `data/templates/_shared/base.css` `skeletons.css` `storyboard.css`、`data/templates/_gen/motion.js` `skeleton.html`、`data/templates/_tests/` | 模板拼装共用的旧骨架与测试（`_shared/tokens/*.css`、`_shared/styles.json`、`_gen/behaviors/` 见 C 类迁移） |

### A2. 模板构建链（`data/tools/`）

| 目标 | 说明 |
| --- | --- |
| `data/tools/build-templates.mjs` | 模板构建器（`LAYOUTS`/`_shared` 拼装、骨架闸检查都为模板服务） |
| `data/tools/test-template-motion.mjs` `test-headline-takeover.mjs` `test-visual-focus.mjs` `test-compare-pairs.mjs` | 模板动效测试 |

> 注：`data/tools/model-stub.mjs` 与模板构建无关，已移入 **B 类保留**。

### A3. Java 侧模板耦合类与旧契约（`ruoyi-heron`）与前端对应页（`heron-ui`）

| 目标 | 说明 |
| --- | --- |
| `NewsTemplatePicker` | 选模板逻辑，新架构无此概念 |
| `TemplateCatalogService` / `TemplateController` / `VideoTemplateVo` / `VideoStyleVo` | 模板注册表 API |
| `StoryStyleMap` | 风格→叙事归属映射，词汇真源迁 C 类后删 |
| `NewsProductionDomain` / `NewsScriptContract` / `DatasetScriptContract` | 旧模板场景契约与硬绑定域逻辑；由六契约及新域组件接管后删 |
| `StoryboardContract` | **旧版分镜契约**（校验 `BEATS` + `SCENE_TYPES` + `on_screen_text`），由新建 `StoryboardGate` 取代后删 |
| `SceneReworkService` / `HeronSceneRework` | 旧场景返工；镜头级返工由 ReAct 循环接管 |
| `HeronNewsProperties` 中模板相关配置项 | `template` 前缀配置清除；管线开关、TTS、封面、字幕、成本配置保留 |
| `heron-ui` 模板管理页与类型残留 | 同步删除 `src/views/heron/asset/template/index.vue`、`src/api/heron/template/{index.ts,types.ts}`，并清理 `editorial/types.ts`、`newsWork/types.ts`、`metrics/index.ts` 中的 `templateId`/`templateKey`/`'template'` 字段 |

> **关键纠偏**：`EditorialLineEditor` **不删**！它是上游四条内容线的选题路由与「没故事就不做」门禁，仅剥离内部的 `NewsTemplatePicker` 依赖与 `template` 字段（见 B 类重构项）。

### A4. 数据库

| 目标 | 说明 |
| --- | --- |
| `video_template` / `video_style` 等模板注册表（以实际表名为准） | 新迁移 drop；对 `news_work` 的 template 外键改挂 `profile_id` / `profile_version` / job 产物字段 |
| 迁移文件命名 | `V{yyyyMMddHHmmssSSS}__video_director_drop_templates.sql`，只新增不改历史迁移 |

---

## B 类 · 保留与去耦合重构（导演系统直接续用的底座）

### B1. 直接续用的生产底座

| 资产 | 说明 |
| --- | --- |
| `data/tools/` 工具链 | `hyperframes`（^0.9.6）、Node ≥ 22、`package.json`/`package-lock.json`、FFmpeg 调用 |
| `data/tools/model-stub.mjs` | **已定案保留**：本地 OpenAI 兼容假端点（`127.0.0.1:8899/v1`），用于 Java HTTP 往返、记账与契约闸联调；后续补导演契约桩响应 |
| TTS + `subs.vtt` 词级对齐 | 旁白与时间戳对齐链路（V2 前置到逐镜创作之前） |
| 机会/洞察体系 | `OpportunityFacts` / `OpportunityGate` / `OpportunityScore` / `OpportunityScorer` + `DatasetStoryPlanner` |
| 闸门与留痕 | JEV、`ToolCallLedger`、成本上限、失败自动暂停、单段时长闸、`VideoSignalCheck`、`VisualMode`（2D/3D 护栏） |
| AgentScope 接入 | `agentscope-harness` + openai/mysql/jev 依赖；`ResearchAgentFactory` 模式为镜头创作 Agent 的蓝本 |
| 发布链路 | 业务规格 §3.10、草稿池、人工验收、sau CLI |
| 知识与反哺底座 | `MetricInsightAnalyzer` 系、`TrendVideoDecomposer` / `TrendLearningAgent`（下一切片接通回灌 `creative-knowledge/`） |

### B2. 原争议项与核心类的「去模板耦合」小改清单（全部保留）

| 类 / 文件 | 现存模板耦合点 | 最小去耦合改法 |
| --- | --- | --- |
| `EditorialLineEditor` | 注入了 `NewsTemplatePicker`，向 `EditorialSelection` 填 `template` 字符串 | 删除 `templatePicker` 依赖；选线/筛稿/故事门禁/跨线回退逻辑**原样保留**，改为传递 `bizLine` / `intent` 给导演管线 |
| `NewsCover` | `sizeOf(templateDir)` 读 `template.config.json` 宽高 | 将入参 `Path templateDir` 替换为 `int width, int height`（取自 `Profile.canvas`）与首镜时间窗 |
| `SubtitleBurn` | `styleOf(templateDir)` 读 `template.config.json` 和 `../_shared/tokens/<style>.css` | 将入参改为 `VisualDesignProfile` 路径（直接读 `data/profiles/<id>/profile.json` + `tokens.css`）；ASS 生成与像素/时长验证闸零改动保留 |
| `BgmCatalog` | `storyOf(script, templateDir)` 内调用 `templateLine(templateDir)` 读 `template.json` | 删除 `templateLine(Path)`，直接由管线传入 `bizLine`（如 `DATA_BOARD`） |
| `SoundCue` + `SoundCueMixer` | `SoundCue.plan` 期望末尾为 `"closing-slot"` 的 `Window` 列表和旧 `script.scenes` | `SoundCueMixer` 零改动；`SoundCue.plan` 适配从 `EditDecisionList` + `Storyboard` 构造的时间窗与数字/转场标记 |

---

## C 类 · 改挂（换角色续用，不许原样当模板资产理解）

| 资产 | 旧角色 | 新角色 |
| --- | --- | --- |
| `data/templates/_shared/tokens/*.css`（5 份） | 模板令牌 | **VisualDesignProfile 真源**（ADR §五），迁 `data/profiles/<id>/tokens.css` 并去模板专属选择器 |
| `data/templates/*/fonts/NotoSansSC-Regular.otf` + `gsap.min.js` | 随模板副本分发 | 统一迁至 `data/profiles/_shared/fonts/` 与 `data/profiles/_shared/vendor/`，供单镜 HTML 引用 |
| `data/templates/_shared/styles.json` | 模板风格注册 | 叙事词汇与风格归属并入 `creative-knowledge/` |
| `data/templates/_gen/behaviors/`（chart/race/compare/globe/focus/headline/mood 等 + skeleton.js） | 模板场景动效实现 | 镜头创作**参照与弹药**：已过闸的动效手法，生成代码可适配复用（须适配 Profile 令牌） |
| `data/reference-cards/`（video-shotcraft / huashu-art-motion + ATTRIBUTION.md） | 参考配方库 | `creative-knowledge/` 的素材源（Apache-2.0 / MIT），并按计划补充 `diagram-design`（MIT）反模板信息图规则 |
| `data/reference-cards/hyperframes-registry/`（31 件） | 组件等价物 | 镜头创作组件弹药库（优先 `components/`），逐件过闸后采纳 |
| `DatasetStoryContract.NARRATIVES` | 模板叙事词汇 | 叙事词汇并入 `NarrativeTreatment` / `Storyboard` 契约的语义层；`sceneType` 退役 |

---

## 施工顺序（每步可独立提交）

1. **S0 · 资产抢救与去耦合先行**：提取 `NotoSansSC-Regular.otf` / `gsap.min.js` 到 `data/profiles/_shared/`；完成 `EditorialLineEditor`、`NewsCover`、`SubtitleBurn`、`BgmCatalog`、`SoundCue` 的去模板依赖改造（全部保留在 B 类）。
2. **S1a · A2 → A1**：先删构建链（`build-templates.mjs` 及 4 个模板动效测试，**保留 `model-stub.mjs`**），再删 `data/templates/` 下 9 套模板及旧 `layouts`/`_tests`。
3. **S1b · A3 + `heron-ui`**：删除 Java 模板耦合类与旧 `StoryboardContract`，同步清理 `heron-ui` 模板管理页/API，同时落位新六契约骨架以保持编译绿灯。
4. **S1c · A4**：新增 Flyway 迁移（`drop` 模板表 + `news_work` 字段调整）。
5. **S2 · C 类迁移与改挂**：`tokens/*.css` → `data/profiles/<id>/tokens.css`、`_gen/behaviors` → 弹药库、`creative-knowledge/` 落位。
6. 每步跑 `mvn -pl ruoyi-admin -am package` 与既有单测，绿灯才进下一步。
