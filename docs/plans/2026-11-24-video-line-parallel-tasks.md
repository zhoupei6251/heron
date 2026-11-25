# 视频线并行任务书（2026-11-24）

> 给「多个 agent 同时开工」用的派发单。每条任务都标了现状证据位置与**文件区白名单**，
> agent 只动自己那一格，不要跨格。接手的人不用先猜哪些已经做了。

## 先读这三条，否则并行会互相踩

1. **`NewsProductionPipeline.java`（约 1700 行）是唯一的大户**：V2 / V3 / V6 都想改它。本单一律只分配给**一个** agent 持有，其余任务遇到它只读不写。
2. **Flyway 使用 UTC 时间戳版本，不申请序号**。新迁移命名为 `VyyyyMMddHHmmssSSS__描述.sql`（17 位，精确到毫秒），各分支独立生成；已有的 `V46~V65` 不改名。
3. **`data/templates/**` 与 `data/tools/**` 归主会话**（模板/渲染层正在改）。其他 agent 只读，不改模板、不改 `build-templates.mjs`。

## 已被占用，不要重复立项

| 区域 | 归属 |
| --- | --- |
| 爆款学习闭环：`agentscope/TrendLearn*`、`TrendVideoDecomposer`、`TrendLearnDocuments`、`V63__trend_learn_state.sql` | 已有 agent 在跑 |
| 模板层：`data/templates/**`、`data/tools/**`（镜头层与转场刚落地） | 主会话 |

---

## 现状基线（已核实，别重跑调研）

- 编辑线其实已有 4 条常量：`DATA_BOARD / TREND_INSIGHT / KNOWLEDGE_EXPLAINER / NEWS_EXPLAINER`
  （`EditorialSelection.java:23-28`、`EditorialDecisionService.java:36`）。
- `ProductionBizType` 仍只有 `news_video` / `prototype`（`ProductionBizType.java:7-8`）——
  4 条线是 `news_video` 内部的策略差异，**不要新增 bizType**。
- 分镜三件套已接进契约与闸：`DatasetScriptContract.java:19-24`（`scene_type / beat / on_screen_text`）
  → `StoryboardContract.violations(...)`（`DatasetScriptAuthor.java:240`、`NewsProductionPipeline.java:436-452`）
  → 注入模板变量（`NewsProductionPipeline.java:1336-1337`）。
  **整篇没写 beat 的旧稿直接放行**（同文件 433 行注释），所以现在是灰度态，不是缺口。
- 句/屏级对齐已有：`NewsNarrationTiming.align(script, subtitles, audioMs)`（`NewsNarrationTiming.java:34`）。
- 音频响度已有：`AudioLoudness`（loudnorm 实测 → 每首 BGM 单独增益 → 包络 → 混音复测 → 失败重试 → 音频闸）。
- 联络表已有：`NewsProductionPipeline.java:255 / 1269`、`QualityFrameService.java:37`、`QualityReportContract.java:392`。
- 发布适配器抽象已有：`PublishRequest` / `PublishReceipt`；现役只有 `DouyinCliPublishAdapter`（`SOURCE = "douyin-cli"`），
  默认出口 `sandbox`（`HeronNewsProperties.java:42`）。
- 参考素材表已有：`heron_reference_asset`（`V59__reference_asset.sql`）。
  **迁移目录里没有任何 B-roll / 普通素材表**（grep `asset|material|media|image` 只命中 V59）。
- 模板层门禁状态：19 套里 **18 套** `hyperframes check` 通过；
  `news-rank-9x16` 卡在改动前就存在的 `#source-line` 对比度（`#7a8a8f` 压奶白面板 = 2.96:1，阈值 3:1，基线逐字相同）。

---

## 七条任务

### V1 · 故事计划（Story Plan）契约 v2

| | |
| --- | --- |
| 现状 | 脚本契约只有 `lead / body / closing` + 分镜三件套；**没有** hook 类型、每段叙事目的、证据引用、情绪节奏 |
| 证据 | `DatasetScriptContract.java:19-24`；`StoryboardContract.java:17` 起 |
| 文件区 | `service/impl/StoryPlanContract.java`（新）、`DatasetScriptAuthor.java`、`DatasetScriptContract.java`、`prompt/data-board/**` |
| 要做什么 | 在**正式写旁白之前**产出一份结构化故事计划并冻结（hook 类型、core thesis、每段目的、证据 ID、情绪/节奏、预期画面）；脚本作者必须真的消费它。走版本化：v1 旧稿照旧放行，v2 新稿才校验 |
| 验收 | 同一选题 v1/v2 各出一片；v2 的每段 `purpose` 与 `evidenceRefs` 非空且能回溯到数据集快照；旧稿回归通过 |
| 并行 | 可立即开工。与 T1 无交集 |

### V2 · 场景级返工（只重渲染改过的那一屏）

| | |
| --- | --- |
| 现状 | 联络表能看，但改一个场景要整片重跑；没有「按场景 ID 重渲染 + 拼回」的路径 |
| 证据 | `NewsProductionPipeline.java:255 / 1269`（联络表）、`ProductionArtifact` 产物键 |
| 文件区 | `NewsProductionPipeline.java`（**本任务独占**）、新增场景级服务类、一条 UTC 时间戳版本 Flyway |
| 要做什么 | 场景渲染改成按 `scene_id` 可寻址：参数化单屏渲染 → 校验新屏的帧/文字闸 → 按时间轴替换进成片 → 复检整片门禁 |
| 验收 | 改一屏后只重渲染该屏（日志可证），成片时长/音画偏移不变，AIGC 元数据与指纹重算正确 |
| 并行 | 可立即开工，**但它是 `NewsProductionPipeline` 的唯一写者**，V3/V6 遇此文件只读 |

### V3 · 声音层：音效与卡点

| | |
| --- | --- |
| 现状 | 响度与 BGM 增益已做；**没有音效**，也没有和节拍/切点对齐 |
| 证据 | `AudioLoudness.java:41`（`BGM_OFFSET_LUFS`）、`NewsProductionPipeline.bgmEnvelope` |
| 文件区 | `AudioLoudness.java`、新增 `SoundCue.java`、`data/audio/**`（音效素材，独立子目录） |
| 要做什么 | 三类音效（转场 whoosh / 数字落点 tick / 结论强调）+ 切点对齐（切点时间从场景窗口来，不从估算来）；音效只压 3~6 dB，不得盖过旁白；缺素材就跳过，不静默降级成「没音效但报告成功」 |
| 验收 | 成片能听出转场音效；旁白段落 `loudnorm` 复测仍在目标值；缺素材走显式跳过并在 checks 里留一条 |
| 并行 | 可立即开工。**不要改 `NewsProductionPipeline`**（V2 独占），需要它加钩子就直接找 V2 加 |

### V4 · 多平台发布出口

| | |
| --- | --- |
| 现状 | 只有抖音（`DouyinCliPublishAdapter`），默认 `sandbox`；适配器抽象已就位 |
| 证据 | `DouyinCliPublishAdapter.java:28`、`HeronNewsProperties.java:42`、`PublishReceipt.java:6` |
| 文件区 | 新增 `**PublishAdapter.java`、`HeronNewsProperties.java` |
| 要做什么 | 按现有适配器模式加平台出口；**每个平台必须能独立开关**，默认关。平台凭据缺失时该出口不可选，不能「一个全局开关全线直发」 |
| 验收 | 新增出口在 `publish_source` 里可选但默认关；缺凭据时明确拒绝并给出原因；不改变抖音出口行为 |
| 并行 | 代码可立即开工；**真跑要人给凭据**，先只做到「适配器 + 闸 + 单测」 |

### V5 · B-roll / 素材层

| | |
| --- | --- |
| 现状 | 模板有 `image` 能力（`.scene-figure`），但**没有素材来源**；迁移里没有任何普通素材表 |
| 证据 | `layouts/article/news-9x16.css:32-36`（`.scene-figure`）、迁移目录无素材表 |
| 文件区 | 新 UTC 时间戳版本 Flyway + `service/impl/MediaAsset*.java`（新）、`heron-ui` 素材页（独立仓库，可单独 agent） |
| 要做什么 | 素材入库（来源、许可、尺寸、hash、OSS 指针）→ 按场景需要挑素材 → 缺素材时该场景退回纯文字版式（不是留空框）。许可未知的素材不许进生产 |
| 验收 | 一条片子用上真素材且 `assets` 闸通过；删掉素材后同一条片子能退回纯文字版式并仍过闸 |
| 并行 | 可立即开工。只读模板，不改模板 |

### V6 · 文稿线启用（`article` 子线）

| | |
| --- | --- |
| 现状 | 文档与配置不一致（`CLAUDE.md` 记 `article-enabled: false`，`heron-production.yml` 记 `true`）；多数文字模板没开分镜 |
| 证据 | **需先复核**：`heron-production.yml:10` 与各模板 `template.config.json` 的 `storyboard` 字段 |
| 文件区 | `heron-production.yml`、`prompt/article/**`、飞书/文档口径 |
| 要做什么 | 先把文档与配置对齐（零代码）→ 再给文字模板补 beat + 上屏文字 → 一轮渲染验证确认开了分镜后正文与标题卡仍可见 → 一次只开一类模板 |
| 验收 | 配置与文档一致；开分镜后的文稿模板出片，正文不藏、标题卡不丢 |
| 并行 | 可立即开工。**不要改模板源文件**（`data/templates/_gen/**` 归主会话），只能改模板 `template.config.json` 的字段并请主会话重生成 |

### V7 · 3D 第二套

| | |
| --- | --- |
| 现状 | 只有 1 套 3D 模板；`globe.js` 只认 `shape === "geo"`（引自 T4，**需复核**） |
| 文件区 | `data/templates/_gen/behaviors/globe.js` + 新模板目录 → **与主会话冲突** |
| 要做什么 | **先等人拍板做哪种空间形式**（地形/剖面/柱阵）。定了之后抽 3D 行为让 shape 不再写死 |
| 并行 | **不能立即开工**：既需要人拍板，又落在主会话的文件区。等主会话收尾后单独排 |

---

## 建议的派发顺序

```
现在就能派： V1  V2  V3  V4  V5  V6
等人拍板：   V7（空间形式），V4 真跑（平台凭据）
排队：       V7（文件区与主会话冲突，等模板层收尾）
```

同一时刻最多 **1 个** agent 持有 `NewsProductionPipeline.java`（本单给 V2）。
V1 / V3 / V5 / V6 若确实需要在流水线里加钩子，**提给 V2 加**，不要自己改。

## 不要做（设计上的决定，不是欠账）

- 不新增 `ProductionBizType`（4 条编辑线是 `news_video` 内部的策略差异）。
- 不为每条内容线建独立渲染引擎或微服务；不引入 Remotion / Motion Canvas 作为第二套渲染。
- 不做「原片再创作」（复用别人的画面）。
- 不把「一个全局开关就全线直发」做成实现；发布仍默认人确认，`sandbox` 为默认出口。
- 不改 Flyway 已应用的迁移。
- 不把 `data/templates/**` 当公共草稿区。
