# heron `service/impl` 目录结构设计

**日期**：2026-11-27
**状态**：设计已确认，等待执行指令
**范围**：仅 `ruoyi-modules/ruoyi-heron/src/main/java/org/dromara/heron/service/impl`

---

## 0. 一句话

`service/impl` 平铺 161 个类，Service 实现、领域算法、数据源适配器、契约、调度器混在一起。
本设计把它切成「5 条业务线各自一包 + 7 个跨线能力包」，**不改类名、不改方法签名、不新增分层**。

---

## 1. 已核实的事实

| 事实 | 依据 |
| --- | --- |
| `ProductionBizType` 有 5 个值 | `domain/ProductionBizType.java` |
| `music_video` 只有枚举占位，无 pipeline / domain / 权限 | `pipelineBean()` 返回 null、`domainBean()` 返回 null |
| 实际可跑 4 条线，且共用 `NewsProductionPipeline`，靠快照 `kind` 分支 | `ProductionBizType.kind()`、`VideoLanePipelines` |
| 原型工坊整线已删除 | `V20261125051922644__drop_prototype_split_video_lanes.sql` |
| 热点采集与爆款学习服务全线，不是趋势线专属 | `TrendVideoController` 调 `TrendCollectService` / `TrendLearnService`，学习结果进创作 Profile |
| `service/impl` 现有 161 个类 | `ls service/impl/*.java \| wc -l` |
| 大量类和成员是包私有（无 `public`） | `VideoLanePipelines`、`DirectorPipeline`、`ColumnPacks`、`NewsNowCatalog` 等 |

最后一条决定迁移方式：跨包后这些类型必须提升为 `public`，否则调用点编不过。

---

## 2. 目标结构

```
org.dromara.heron.service.impl/
├── lane/                          每条线只放"只属于这条线"的代码
│   ├── news/                      news_explainer
│   ├── databoard/                  data_board
│   ├── trend/                     trend_insight
│   ├── knowledge/                 knowledge_explainer
│   └── music/                     music_video（当前空包，保持 5 条线可见）
└── shared/                        跨线共享能力
    ├── production/                流水线、状态机、调度、导演/创作阶段
    ├── publish/                   发布出口与库存
    ├── research/                  热点、选题、编辑、研究、爆款学习、归因
    ├── media/                     素材、音频、字幕、成片资产
    ├── quality/                   质检、修复、画面信号
    ├── source/                    数据源适配器、传输、地址策略
    └── support/                   模型客户端、计费、权限、通用工具
```

`service/` 下的 20 个接口、`domain/`、`domain/bo`、`domain/vo`、`controller/`、`mapper/`、`agentscope/` 本次**不动**。

---

## 3. 类归属映射（161 个）

### lane/news（4）

`ArticleImages`、`NewsArticleBody`、`NewsModelScriptAuthor`、`NewsScriptContract`

### lane/databoard（20）

`Column`、`ColumnPacks`、`DataSourceCatalogService`、
`DatasetAdapter`、`DatasetContract`、`DatasetFacts`、`DatasetFetchService`、`DatasetHttp`、
`DatasetPlaceholders`、`DatasetRouter`、`DatasetScriptAuthor`、`DatasetScriptContract`、
`DatasetSignal`、`DatasetSnapshot`、`DatasetStoryAngle`、`DatasetStoryContract`、
`DatasetStoryPlanner`、`DatasetTitleAuthor`、`DatasetVisualFocus`、`DatasetWindowContract`

### lane/trend（2）

`TrendAnalysis`、`TrendContract`

### lane/knowledge（3）

`KnowledgeCandidateCollector`、`KnowledgePack`、`KnowledgeScriptAuthor`

### lane/music（0）

空目录，仅用于让枚举里的第 5 条线在目录上可见。

### shared/production（28）

`NewsProductionPipeline`、`NewsProductionDomain`、`VideoLanePipelines`、
`NewsAutoRunner`、`NewsAutoService`、`NewsCleanupRunner`、`NewsNarrationTiming`、`NewsWorkServiceImpl`、
`FrozenArticleService`、`FrozenContextService`、
`ProductionArtifactCleanupRunner`、`ProductionArtifactService`、`ProductionCapabilityService`、
`ProductionCleanupService`、`ProductionExecutionServiceImpl`、`ProductionNotificationService`、
`ProductionProcessService`、`ProductionRecoveryService`、`ProductionRunner`、
`ProductionRuntimeIdentityService`、`ProductionServiceImpl`、`ProductionUsageService`、
`DirectorPipeline`、`DirectorVisualRework`、`TreatmentDirector`、`ProfileRegistry`、
`StoryPlanContract`、`StoryRewriter`、`StoryScriptAlignment`、`StoryboardContract`、
`VisualMode`、`BeatAnalysis`

### shared/publish（9）

`DouyinCliPublishAdapter`、`YouTubePublishAdapter`、`SandboxPublishAdapter`、
`PublishCuratorService`、`PublishInventoryService`、`PublishInventorySync`、`PublishScheduler`、
`NewsPublishAttempts`、`PublishInventory*` 相关实现

### shared/research（37）

`NewsCandidateCollector`、`NewsCandidateScreener`、`NewsDedup`、
`NewsSourceFetcher`、`NewsSourceRunner`、`NewsSourceServiceImpl`、
`EditorialDecisionService`、`EditorialLineEditor`、`EditorialReviewer`、`EditorialRunService`、
`ContentDecisionServiceImpl`、`ContentProfileWriter`、
`DataOpportunityRunner`、`DataOpportunityService`、
`OpportunityFacts`、`OpportunityGate`、`OpportunityScore`、`OpportunityScorer`、
`StrategyHypothesisService`、`StrategyReferenceService`、
`ResearchCostService`、`ResearchLeaseKeeper`、`ResearchPromotionServiceImpl`、`ResearchScheduler`、
`ResearchTaskServiceImpl`、`JevShadowRecorder`、
`TrendCollectRunner`、`TrendCollectService`、`TrendDistillService`、
`TrendLearnDocuments`、`TrendLearnService`、`TrendLearnStorage`、`TrendLearningAgent`、
`TrendVideoDecomposer`、`NewsMetricServiceImpl`、`MetricInsightAnalyzer`

### shared/media（14）

`MediaAssetServiceImpl`、`MediaAssetStorage`、
`ReferenceAssetServiceImpl`、`ReferenceAssetStorage`、`ReferenceVideoDownloader`、
`ReferenceVideoTransport`、`ApacheReferenceVideoTransport`、
`SubtitleBurn`、`SoundCue`、`SoundCueMixer`、`BgmCatalog`、`AudioLoudness`、`NewsCover`

### shared/quality（7）

`QualityCriteria`、`QualityFrameService`、`QualityRepairPlanner`、`QualityRepairService`、
`QualityReportContract`、`QualityReviewer`、`VideoSignalCheck`

### shared/source（28）

数据源适配器：`CheapSharkAdapter`、`DevToAdapter`、`EastmoneyIndexAdapter`、
`EastmoneyMacroAdapter`、`EastmoneyMarketCapAdapter`、`FrankfurterAdapter`、
`GapminderFertilityAdapter`、`GithubTrendingAdapter`、`HackerNewsAdapter`、
`HuggingFaceAdapter`、`JikanTopAdapter`、`MetNoAdapter`、`OwidRaceAdapter`、
`OwidSeriesAdapter`、`SinaFuturesDatasetAdapter`、`StackExchangeAdapter`、
`UsgsEarthquakeAdapter`、`UsgsQuakeGeoAdapter`、`VegaMoviesAdapter`、
`WikimediaTopAdapter`、`WorldBankRankingAdapter`

传输与策略：`ApacheNewsTransport`、`NewsTransport`、`DatasetHttp`、
`NewsAddressPolicy`、`NewsUrlPolicy`、`NewsNowCatalog`、`NewsFeedItem`

### shared/support（10）

`OpenAiModelClient`、`ModelJson`、`ModelPrice`、`ModelPriceConfig`、`RunCostGuard`、
`CountryNames`、`HeronAccessService`、`HeronHealthServiceImpl`、`RedisAutoPauseStore`

---

## 4. 迁移规则（必须逐条遵守）

1. **一次只迁一个包**，一个包一笔提交；不混入重命名、格式化、逻辑修改。
2. **类名、方法签名、字段、公开行为全部不变**，只改 `package` 与 `import`。
3. 包私有类型迁出原包后**必须提升为 `public`**，这是必要的最小可见性变更；不顺手扩大其他成员可见性。
4. 每迁完一个包跑 `mvn -pl ruoyi-modules/ruoyi-heron -am -q compile`，失败立即回退该包。
5. `NewsProductionPipeline.java` 当前有未提交改动，**等它提交后再迁 `shared/production`**；其余包不依赖这笔改动，可先迁。
6. 迁移顺序按依赖从少到多：`source` → `quality` → `media` → `publish` → `support` → `research` → `lane/*` → `production`。
7. 不动 `service/` 接口位置；实现类 import 更新即可。
8. 不建 `module-info.java`，不引入新依赖，不改构建配置。
9. 每步完成后同步更新本文档的「执行记录」一节。

---

## 5. 明确不做

- 不拆类、不合并类、不抽接口——那是后续独立任务。
- 不动 `domain` / `controller` / `mapper` / `agentscope` 的目录。
- 不为 `music_video` 提前写任何代码。
- `shared/research` 有 37 个类，一级内不再细分；若执行后仍显拥挤，另开设计再切。
- 不自动提交、不自动推送；每笔提交前由用户确认。

---

## 6. 执行记录

| 日期 | 动作 | 提交 |
| --- | --- | --- |
| 2026-11-27 | 设计确认，等待执行指令 | — |
