# 视频线并行任务书 · 三份大任务（2026-11-25）

> 派给其他平台 agent 用的施工单。每条任务都标了**现状证据位置**与**文件区白名单**，
> agent 只动自己那一格。三条任务的文件区**互不重叠**，可以同时开工。

## 先读这三条，否则并行会互相踩

1. **`NewsProductionPipeline.java`（2845 行）本单只归 A 单**。B 单、C 单若确实需要它加钩子，
   提给 A 单加，不要自己动这个文件。
2. **Flyway 使用 UTC 时间戳版本，不申请序号**。新迁移命名为 `VyyyyMMddHHmmssSSS__描述.sql`（17 位，精确到毫秒），各分支独立生成；已有的 `V46~V65` 不改名。
3. **`data/templates/**` 与 `data/tools/**` 本单只归 B 单**。A 单、C 单只读，不改模板、不改
   `build-templates.mjs`。

## 已被占用，不要重复立项

| 区域 | 归属 |
| --- | --- |
| `NewsProductionPipeline.java` | A 单独占 |
| `data/templates/**`、`data/tools/**` | B 单独占 |
| `publish_*` 适配器与 `IPublishAdapter` | 上一批已做完（V4），不要重做 |
| `MediaAsset*`（画面素材） | 上一批已做完（V5），不要重做 |
| `SceneRework*`（场景级返工） | 上一批已做完（V2），不要重做 |
| 配乐曲库 `data/audio/bgm/catalog.json` + `BgmCatalog.java` | 主会话已落地，A 单若要用只读 |

---

## 现状基线（已核实，别重跑调研）

上一批 V1–V6 已全部落地，本单**不重复**这六条：

| 已完成 | 落地物 |
| --- | --- |
| V1 故事计划契约 v2 | `StoryPlanContract.java` + `prompt/data-board/story-plan-contract.md` + `DatasetScriptAuthor.freezePlan` |
| V2 场景级返工 | `SceneReworkService.java` + `V64__scene_rework.sql`（`render_calls=1`、`tts_calls=0` 是库侧约束） |
| V3 声音层音效 | `SoundCue.java`（三类音效）+ `SoundCueMixer.java` |
| V4 多平台发布出口 | `SandboxPublishAdapter` / `DouyinCliPublishAdapter` / `YouTubePublishAdapter` |
| V5 画面素材层 | `MediaAsset*` 全套 + `V65__media_asset.sql` |
| V6 文稿线启用 | `article-enabled: true` |

本单三条任务针对的是**剩下的真实缺口**，逐条有实证：

| # | 缺口 | 证据 |
| --- | --- | --- |
| A | 每期都生成 `subs.vtt`，但**从来没有烧进画面**——`final.mp4` 里没有一帧字幕 | `NewsProductionPipeline.java:270-273` 只解析、只用它对齐时间轴；`artifacts.finalizeFile(run, "subtitles", ...)` 只把 JSON 存成产物，混流命令里没有字幕流 |
| A | 只有联络表（`contact.png` 3×2 缩略图），**没有封面图** | `contactSheet()` 是唯一产图路径；`HeronTrendVideo.coverUrl` 存在但新闻线从不写它 |
| B | 20 套模板**全是 1080×1920**，一套横版都没有，B站/YouTube/公众号全部用不了 | 逐个读 `data/templates/*/template.config.json`，`width/height` 一致为 1080×1920 |
| C | `QualityRepairPlanner.plan()` **有规划无执行**：两个 note 码定义了但没人产生 | `repair_rolled_back` / `repair_not_rechecked` 在 `QualityReportContract.java:35` 定义；`QualityReviewer.java:105` 只写 `emptyRepair()`；全仓无 `plan(` 调用方 |
| C | 表现采样只出展示页，**不回灌到选线/选模板** | `MetricInsightAnalyzer.analyze(dimension, samples)` 只产 `NewsPerformanceInsightVo` 给 `NewsMetricController`；`EditorialLineEditor` 与 `NewsWorkServiceImpl.claimAs` 都不读它 |

---

## A 单 · 成片交付层：字幕轨 + 封面图

| | |
| --- | --- |
| 目标 | 让每条片子发出去就能直接播：画面里带字幕，信息流里有封面 |
| 文件区 | `NewsProductionPipeline.java`（**本单独占**）、新增 `NewsCover.java`、新增 `SubtitleBurn.java`、`HeronNewsProperties.java`（新增两个开关，默认关）、`QualityFrameService.java`（若封面要进审稿） |
| 不能动 | `data/templates/**`、`data/tools/**`（B 单）；`BgmCatalog.java`（主会话）；任何 `publish_*` |

### A1 · 字幕烧进画面

现状：`subs.vtt` 由 `hyperframes --write-subtitles` 产出（`NewsProductionPipeline.java:1647`），
解析成 JSON 后只用于 `NewsNarrationTiming.align` 对齐场景窗，最终成片**没有字幕像素**。

铁证：`mixTo()`（`NewsProductionPipeline.java:1903-1926`）命令里是 `-map 0:v:0 ... -c:v copy`——
视频流是**流拷贝**，任何滤镜都不可能生效。这不只是「没做」，是当前架构下做不到。

要做：

1. 把 `subs.vtt` 转成 `ass`，用既有 `mixTo` 那次 ffmpeg 调用**一次编完**。
   ⚠️ **必须把 `-c:v copy` 改成重新编码**（`libx264` + 与原始渲染一致的 crf/preset）。
   这是一次真实的质量与耗时代价：A1 开关关掉时必须走回 `copy` 那条分支，逐字节不变。
2. 字幕样式走令牌，不要写死在 Java 里：读出模板目录 `template.config.json` 的 `width/height`，
   竖版按 1080 宽取字号，横版按 1920 宽取字号。字幕区必须落在下三分之一**之上**——
   `#bottom-bar` 的 `z-index: 1`（`data/templates/_shared/base.css:52`）已经在占那条带，字幕压上去就是两层字叠一起。
3. 开关 `heron.news.subtitles.burn`，**默认关**。开了才烧；关着与现在逐字节相同（回归要靠这个）。
4. 新闸 `subtitles_burn`：烧完之后用 `ffprobe` 确认字幕流存在 / 或抽一帧确认底部有非背景像素。
   闸要能答「哪条闸 + 哪段输入 + 为什么拒」。

验收：

- 开关关：同一条片子的 `final.mp4` 哈希与改动前一致。
- 开关开：成片底部能看到字幕；字幕不与 `#bottom-bar` 的内容重叠（抽 3 帧人工看 + 一条像素带断言）。
- 烧字幕前后 `AudioLoudness.deliveryOk()` 仍为真，成片时长偏差 < 20ms。

### A2 · 封面图

现状：只有 `contact.png`（3×2 联络表）。信息流里没有封面等于放弃点击率。

要做：

1. 从成片抽**钩子段最有信息量的一帧**做大图：不要固定第 1 秒——第 1 秒常常还在入场。
   做法：取 `timeline.get(0)` 中点，再用既有 `QualityCriteria.picture_motion` 那套运动量
   在 ±0.6s 内挑变化最大的一帧（复用已有能力，不新造指标）。
2. 输出两张：`cover.png`（模板原始比例，1080×1920）+ `cover-thumb.png`（平台的方图裁切位，1080×1080 居中裁）。
3. 写进产物：`artifacts.finalizeFile(run, "cover", ...)`。**封面地址要落库，但 `heron_video_version` 没有封面列**（全仓只有 `heron_trend_video.cover_url`，`V56`）；加列要一条 UTC 时间戳版本 Flyway，各分支独立命名。
4. 新闸 `cover`：图存在、尺寸对、不是纯色帧（复用 `contactSheet` 之后已有的非空判定思路）。

验收：

- 一条片子产出 `cover.png` + `cover-thumb.png`，尺寸与比例正确，不是纯色/纯黑。
- 封面帧在抽帧时刻确实属于首屏（用 `timeline` 的 `startMs/endMs` 断言）。
- 关掉开关时产物清单与现在一致。

### A3 · 两个开关的默认值

两个新开关**默认关**，上线顺序：先开字幕（A1 验收过）→ 观察几期 → 再开封面。
配置项写进 `HeronNewsProperties`，并在 `docs/` 里记一行「为什么默认关」。

---

## B 单 · 模板层：横版 16:9 模板族

| | |
| --- | --- |
| 目标 | 让同一条稿子能出横版，B站 / YouTube / 公众号可直接发 |
| 文件区 | `data/templates/**`、`data/tools/build-templates.mjs`（**本单独占**） |
| 不能动 | `NewsProductionPipeline.java`（A 单）、任何 Java 业务代码 |
| 交付形态 | **只出模板 + 闸通过**；接入生产是 A 单之后的事，本单不碰 Java |

### B1 · 先定版式选择，再动手

横版不是把竖版拉宽。竖版是「上标题 / 中画面 / 下正文」的纵向阅读，横版是「左信息右画面」
或「上标题中画面下结论」的横向阅读。同一套 CSS 换比例，出来的必然是两边都不像。

先选出**真正值得横版化的 4 套**，按「横版下这套的结构是否还成立」判：

| 竖版模板 | 横版化理由 | 建议 |
| --- | --- | --- |
| `news-9x16` | 文字排版型，横版可做左文右图 | ✅ |
| `news-data-9x16` | 图表型，横版反而更宽裕 | ✅ |
| `news-magazine-9x16` | 杂志感，横版是原生形态 | ✅ |
| `news-rank-9x16` | 榜单型，横版能放更多行 | ✅ |
| `news-data-3d-9x16` | 3D 球体在横版更自然，但 `globe.js` 写死 `1080×520` 画布 | ⚠️ 需先看 `globe.js` 能否参数化，不能就排除 |
| 其余 | 纵向节奏强，横版会散 | ❌ |

### B2 · 生成方式

**不要复制 20 套目录再改。** 在 `build-templates.mjs` 里加一个 `ratio` 维度：

1. `template.config.json` 已有 `width`/`height`，加一个可选 `layouts` 字段声明该模板支持哪几种比例
   （`"9x16"` / `"16x9"`），不写就只支持当前那一套 —— 已有 20 套**零改动**继续工作。
2. 比例差异全落在 `layouts/<layout>-16x9.css`，与现有 `layouts/article/news-9x16.css` 同级；
   `_gen/` 下的 `skeleton.html` / `motion.js` / `base.css` **不改**。
3. `--check` 必须仍然通过；`--verify` 对新增的横版模板跑 `hyperframes check`。

### B3 · 硬约束（这几条踩了就白干）

1. **字号不能等比缩放。** `build-templates.mjs:271` 有一条硬规则「低于 24px 的字在 1080×1920 上读不出来」。
   1920×1080 上这条线要重新标定——横版屏幕观看距离更远，24px 是下限，正文建议 ≥ 32px。
2. **镜头层与转场要跟着换令牌。** `data/templates/_shared/tokens/*.css` 里有 `--cam-push` / `--slide-shift`，
   竖版的 130px 位移在横版上读不出方向，横版要给更大的位移或换轴向。
3. **`#bottom-bar` 与进度条的位置。** 竖版底部安全区与横版完全不同，字幕烧上去（A 单）后横版不能压字。
4. **每套新增模板都要过 `hyperframes check`**。当前基线：19 套里 18 套通过，
   `news-rank-9x16` 卡在改动前就存在的 `#source-line` 对比度 2.96:1（阈值 3:1）。新增模板不许引入新的 fail。

### B4 · 验收

- 新增横版模板 `hyperframes check` 全绿（除已知的 rank 对比度问题，若该模板被横版化则必须顺手修掉）。
- `node data/tools/build-templates.mjs --check` 零漂移。
- 同一份稿子用竖版与横版各渲一次，两版**文字完整、不溢出不重叠**（抽帧人工看 + `--verify` 报告）。
- 已有 20 套竖版模板的产物**逐字节不变**。

---

## C 单 · 决策闭环：审稿修复执行 + 表现回流 

| | |
| --- | --- |
| 目标 | 差片子能被自动救回来；跑得好的选择能被下一期用上 |
| 文件区 | `QualityRepairPlanner.java`、新增 `QualityRepairService.java`、`QualityReviewer.java`、`MetricInsightAnalyzer.java`、`EditorialLineEditor.java`、`NewsWorkServiceImpl.java`（仅选模板那一段） |
| 不能动 | `NewsProductionPipeline.java`（A 单）、`data/templates/**`（B 单） |
| 性质 | 纯 Java 决策层，不涉及渲染，可完全独立开工 |

### C1 · 审稿修复执行

现状：`QualityRepairPlanner.plan(report, mode)`（`QualityRepairPlanner.java:71`）能算出一份修复计划，
`repair_rolled_back` / `repair_not_rechecked` 两个 note 码在 `QualityReportContract.java:35` 定义好了，
但**没有任何调用方**——规划写完就搁着，两个 note 码是死代码。

要做：

1. 新增 `QualityRepairService`：拿报告 → `plan()` → 用 `prompt()` 让脚本作者改稿 →
   **重跑整条流水线**（走既有 `ProductionRunner`，不要另造一条渲染路径）→ 复检。
2. 三条必须留痕的路径：
   - 修复后过闸 → 记 `repaired`；
   - 修复后不过闸 → **回滚到修复前版本**，记 `repair_rolled_back`（回滚不是「删掉新版」，是让上一版重新成为可发布版本）；
   - 修完没复检 → 记 `repair_not_rechecked`，**不许当成通过**。
3. `MAX_ATTEMPTS = 1`（`QualityRepairPlanner.java:33` 已有这条）——只修一次，不做收敛循环。
   修复是兜底，不是无限重试。
4. 不自动发布：修复后的版本仍然停在草稿，等人确认。**这条不能破**。

验收：

- 造一条必然不过 `early_payoff` 的稿子，跑修复：要么修好且报告记 `repaired`，要么回滚且记 `repair_rolled_back`。
  两种结果都必须在 `checks` 里能查到。
- 修复过程中**不出现第二次 TTS**（修改的是文字稿，不该重新配音；若确实要重配音，必须在报告里写明）。
- 修复失败时上一版仍然可发布，产物清单不被破坏。

### C2 · 表现回流

现状：`MetricInsightAnalyzer.analyze()` 产出的 `NewsPerformanceInsightVo` 只进 `NewsMetricController` 给人看。
**跑得好的那条线、那套模板，下一期选的时候完全不知道。**

要做：

1. 把 `analyze()` 的结论按维度（`line` / `template` / `chart`）落成一份**可被选线读到的偏好**。
   不要新建推荐系统：只做一件事——**样本足够时，把表现显著好的分组权重抬高**。
2. 证据等级必须尊重：`MetricInsightAnalyzer` 已经把「能不能拿来做策略」判好了——
   `strategyEligible = (tier == "L4" && stable)`（`MetricInsightAnalyzer.java:201`），`L2` 起才给聚合统计量。
   **不到 `L4` 或样本不稳时不许影响选择**，照现有判定读，不另立一套阈值。
   这条是这个功能最容易做坏的地方：三个样本就调权重，等于把噪声当规律。
3. `EditorialLineEditor` 与 `NewsWorkServiceImpl.claimAs` 读这份偏好时，**只做加权，不做一票否决**：
   某套模板历史表现差可以降权，但不能永久拉黑 —— 数据会变，选题会变。

验收：

- 样本不足（如 < 8 条同组样本）时，选择结果与不带偏好**完全一致**。
- 样本充足且某模板显著更好时，该模板被选中的概率确实上升（用固定种子跑 100 次统计）。
- 偏好只影响概率，不影响契约：线名、`kind`、模板 `supportedLines` 的校验一律照旧。

### C3 · 不要做的事

- 不改 `QualityCriteria.IDS` 的判据集合（判据是产品决定，不是实现细节）。
- 不把审稿从 L3（不阻塞）改成闸（阻塞）。
- 不引入第二套指标口径：表现数字只来自人工回填的采样行。

---

## 三条任务的总验收

派发后，由协调人（主会话）在三条都落地后跑一次**联合验收**：

1. `mvn -pl ruoyi-admin -am package` 通过。
2. 同一条选题分别出：竖版（现况）+ 横版（B 单）+ 开字幕封面（A 单）三支片子，逐支能播。
3. 一条差片子走完 C 单的修复闭环，报告里能查到结论。
4. 所有闸的报告都能回答「哪条闸 + 哪段输入 + 为什么拒」。

## 不要做（设计上的决定，不是欠账）

- 不新增 `ProductionBizType`（4 条编辑线是 `news_video` 内部的策略差异）。
- 不为每条内容线建独立渲染引擎或微服务；不引入 Remotion / Motion Canvas 作为第二套渲染。
- 不把「一个全局开关就全线直发」做成实现；发布仍默认人确认，`sandbox` 为默认出口。
- 不改 Flyway 已应用的迁移。
- 不把 `data/templates/**` 当公共草稿区。
- 不把受限许可的素材或提示词打包进产品，只借鉴理念。
