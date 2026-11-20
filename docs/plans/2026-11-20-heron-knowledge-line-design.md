# 知识解释线（knowledge_explainer）设计

> 2026-11-20。目标：把《高性价比人生指南》（HowToLiveBetter）及后续同类知识库做成抖音竖屏视频，作为新闻视频线内的一条新子线接入，选题、写稿、渲染全自动，发布走自动模式。

## 1. 背景

用户选定 HowToLiveBetter（34 章、772 条建议，每条带花费/收益/证据等级/来源，正文 CC BY 4.0 允许商用）作为第一批内容来源，并明确：后续还会有多批同类内容，都要接入 heron 生产线；选题与写稿由模型自动完成，不留人工干预；发布全自动。

内容形态定为**主题串联多条**：一条视频围绕一个主题（如「月薪 5000 最该先做的 5 件事」），从一本书跨章抽 3–5 条，开头钩子、结尾行动清单。竖屏大字排版为主，不做图表。

架构上与 `docs/reference/root/reference_github_4.md`（Heron 内容生产线架构 v1.0）的四线划分一致：该文档的 `NEWS_EXPLAINER` / `DATA_BOARD` / `TREND_INSIGHT` / `KNOWLEDGE_EXPLAINER` 对应本仓库已实现的 `article` / `data_board` / `trend` 三条子线，外加本设计新增的第四条。内部标识沿用该文档的 `KNOWLEDGE_EXPLAINER`（与既有 `NEWS_EXPLAINER`、`TREND_INSIGHT` 同族），冻结输入里的 `kind` 取小写 `knowledge_explainer`。落地只取该文档 P2 的切片，刻意偏离三处：不按 60/20/10/10 配比排产，按用户选择做**备用线**；不建 `content_line` / `content_series` / `editorial_brief` / `evidence` / `story_plan` / `performance_snapshot` 等新表，复用候选表与冻结输入；不引入脚本契约 v2（钩子 / beats / visualIntent 留待 P0 线稳定后再评估）。

## 2. 现状事实（决定架构的关键）

| 事实 | 位置 |
| - | - |
| 线由冻结输入里的 `kind` 决定，现有 `article`（6 类产物）/ `data_board`（7 类）/ `trend`（8 类，多 `trend.json` 与 `trend` 闸） | `NewsProductionDomain` |
| 选线在 `EditorialDecisionService`（`DATA_BOARD` / `NEWS_EXPLAINER` / `TREND_INSIGHT`），回退顺序与模板选择在 `EditorialLineEditor` | `service.impl` |
| 模板通过 `template.config.json` 的 `supportedLines` 声明归哪条线，不靠命名前缀 | `data/templates/*` |
| 候选表已有 `sourceKind` / `sourceKey`，天然容纳非热榜来源 | `heron_news_candidate` |
| 自动模式已有逐源开启、灰度准入、可下架、槽位幂等，出口 `publish_source = "sandbox"` | 业务规格 §3.10 |

另有一处文档漂移：`CLAUDE.md` 写 `article-enabled: false`，实际 `heron-production.yml` 与代码默认值都是 `true`。本次不改，只记录。

## 3. 决策

| 决策 | 选择 | 理由 |
| - | - | - |
| 放在哪 | `news_video` 内新增第四子线 `kind=knowledge_explainer` | 任务、版本、闸、审核、发布、渲染全部复用；新增 biz type 等于复制整套体系，YAGNI |
| 不复用 `article` 线 | 新开一条 | article 的闸为新闻时效/证据设计，没有引用与许可校验；选题池与指标归因会和热点新闻混在一起，后续多批内容会越来越分不清 |
| 知识源形态 | 仓库内置知识包（git），manifest 记 `originUrl` + `pinnedCommit` | 冻结版本可重放，引用能精确到书的某个版本；只记 URL 会在源更新后失效 |
| 加新源流程 | 半自动：URL → 抓取生成知识包 → 人核对许可与解析质量 → 入库 | 解析可由模型做，许可确认是法律边界，必须人看一次 |
| 候选存储 | 复用 `heron_news_candidate`（`sourceKind=knowledge`、`sourceKey=源`） | 零迁移，去重/锁定/活跃任务机制直接复用 |
| 选题 | 模型挖主题 + 模型/规则排序选 Top 1 | 与现有候选池机制同构，只是去掉人工 |
| 发布节奏 | **备用线**：数据看板线当天无素材时才出知识片 | 用户选定；想改成独立每日槽位只改回退顺序 |
| 模板 | 首期 2 套 `knowledge-*`，复制 `news-mono` / `news-poster` 改造 | 不新造渲染链路；先看数据再决定是否专门设计 |

## 4. 知识源与导入

一本书/一份知识库 = 一个知识源，`sourceKey` 标识（首期 `how-to-live-better`）。

```text
data/knowledge/<sourceKey>/
├── manifest.json        # 名称、作者、originUrl、pinnedCommit、license、attribution、章节列表、解析规则
├── chapters/*.md        # 原文章节（固定 commit 下的原文）
├── entries.json         # 结构化条目：entryId、原文、花费、收益、证据等级、来源链接
└── LICENSE              # 许可证原件
```

- `entryId` 在导入时生成并稳定（章节号 + 条目序号，如 `05-03`）；书本身没有稳定 ID。
- `attribution` 进片尾与简介，例：内容改编自《高性价比人生指南》，CC BY 4.0，附链接，注明有修改。
- 知识源首期用文件 + 配置注册，不入库；与 `HeronDataSource`（图表数据集，`dataset/1`）严格分开。

## 5. 生产线

1. **知识源导入（一次性，半自动）**：URL → 抓取到固定 commit → 解析成 `entries.json` → 人核对许可与解析质量 → 合并进 git → 注册 `sourceKey`。
2. **主题候选（全模型）**：知识编辑读 `entries.json`，产出主题候选（标题、钩子、选定的 3–5 个 `entryId`、吸引力理由），写入候选表；按 `normalizedTitle` 去重。
3. **自动选题**：`EditorialLineEditor` 新增 `KNOWLEDGE_EXPLAINER` 分支，模型自动选 Top 1，产出 selection（带 `entryId` 列表），无人干预。
4. **写稿**：钩子 → 3–5 条建议 → 行动清单 → 署名，每段挂 `entryId`。
5. **套版**：`knowledge-*` 模板声明 `supportedLines: ["KNOWLEDGE_EXPLAINER"]`，复用现有 HyperFrames 渲染（Node ≥ 22，1080×1920）。
6. **产物**：`mp4`、`contact_sheet`、`script`、`subtitles`、`checks`、`asset_manifest`、`citations.json`（7 类）。
7. **发布**：自动模式按知识源逐个开启，槽位幂等、可下架，凭据到位前出口只走 `sandbox`。

回退顺序：数据集线 → 趋势线 → 解说线 → **知识线**。

## 6. 代码落点

| 文件 | 改动 |
| - | - |
| `EditorialDecisionService` | 加 `KNOWLEDGE_EXPLAINER` 常量与线路集合；回退顺序末尾追加知识线 |
| `EditorialCandidatePool` | 加 `knowledge` 列表（`sourceKind=knowledge`），并入 `isEmpty` / `excluding` / poolHash |
| `EditorialLineEditor` | 加 `editKnowledge()`：模型从知识候选选 Top 1；模板按 `KNOWLEDGE_EXPLAINER` 选 |
| `EditorialSelection` | 允许新线路；`sourceEvidenceIds` 复用为 `entryId` 列表 |
| `NewsProductionDomain` | `kindOf` 返回 `knowledge_explainer`；新增 `KNOWLEDGE_KINDS`（7 类）与 `KNOWLEDGE_GATES`（12 道，含 `citation`） |
| `NewsProductionPipeline` | 写稿分支：钩子 → 3–5 条 → 行动清单 → 署名；产物多落 `citations.json` |
| 新增 `KnowledgeCandidateCollector` | 读 `entries.json` 产出主题候选；自动 tick 保证每个启用的知识源有候选 |
| `data/templates/knowledge-*` | 首期 2 套，声明 `supportedLines: ["KNOWLEDGE_EXPLAINER"]` |
| `heron-production.yml` | `knowledge-sources:` 列表（sourceKey、路径、自动开启） |

**数据库零迁移**：候选表字段已够；`citations.json` 只是产物 `kind` 字符串；闸集在代码里。

## 7. 闸与失败语义

新增 `citation` 闸，校验四件事：

1. 脚本每段引用的 `entryId` 存在于该源固定 commit 的 `entries.json`；
2. `citations.json` 条目集合与脚本引用集合一致；
3. 片尾/简介含 manifest 的署名文本；
4. 与原文连续相同字数超过阈值即拒（阈值可配）。

其余 11 道闸照旧：`schema`、`evidence`、`audio`、`audio_mix`、`subtitles`、`render`、`decode`、`visual`、`aigc_metadata`、`aigc_visible`、`assets`。

失败必须回答：哪条闸 + 哪段输入 + 为什么拒；`citation` 理由只允许安全定位符，沿用现有 checks 校验。

## 8. 首期范围与不做

- 只接 HowToLiveBetter 一个源；其余批次按同一格式后补。
- 2 套模板先复制改造；不专门新造视觉体系，看数据再定。
- 不做：知识源后台 CRUD、图文卡片（PPT 式）形态、多语言、Anki/PDF 产物、热点反选、新表。

## 9. 里程碑

1. 导入知识包与 `entries.json`（HowToLiveBetter）。
2. 串通 kind / 闸 / 候选，手动跑通一条完整成片。
3. 自动模式逐源开启，`sandbox` 发布闭环。
4. 模板上齐，开数据回流看效果，再决定是否独立槽位。

## 10. 风险与已知取舍

| 风险 | 说明 |
| - | - |
| 备用线可能饿死 | 数据看板天天有素材时知识线几乎不出片；要稳定更新需改为独立槽位（只改回退顺序） |
| 复制模板未必"抓人" | 首期先验证内容与钩子，视觉后面按数据再投入 |
| 书内容会更新 | 引用锁定 `pinnedCommit`；更新需重新导入并生成新知识包版本 |
| 许可义务 | CC BY 4.0 允许商用但要求署名、许可证链接、注明修改；`citation` 闸强制署名，照抄上限防平台原创度问题 |
| 模型选题质量 | 全自动选题需要回流数据校准；首期建议观察前 20 条的完播与互动再调提示词 |
