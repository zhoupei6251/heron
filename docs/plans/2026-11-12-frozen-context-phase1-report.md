# 冻结上下文 · Phase 1 实施报告

> 基线 `dev` @ `9a1fac7e5`，实施后 `dev` @ `52e48e725`（3 个提交）。
> 范围与任务书一致：未新增数据库表、未改列、未触碰 Render / FFmpeg / HyperFrames / MinIO。

## 1. 提交

| commit | 内容 |
| --- | --- |
| `6be92bf1b` `feat(editorial)：候选池带身份，选源不再二次发现` | 候选池五类候选 + poolId/poolHash、Pools 带 ID、DatasetRouter 拆分、EditorialChoice、markDecision 延后 |
| `82eccca75` `fix(editorial)：自动发布门禁收紧到白名单，旧记录不再默认放行` | severity 白名单 + schemaVersion 必填 + `review_legacy` |
| `52e48e725` `docs(plans)：冻结上下文 Phase 0 基线与 Phase 1 任务书` | 设计文档 |

## 2. 实际改动

**主代码**
- `domain/PoolDigest.java`（新增）：五类候选的 canonical 投影 + sha256。只纳入 title/summary/heatScore/bestRank/lastSeenAt/证据引用集合/dataset 元数据/source host/recent 标题；审计字段不入摘要。
- `domain/EditorialCandidatePool.java`：`(poolId, poolHash, hot, articles, datasets, sources, recent)`，`of(...)` 建池即算摘要，`empty()` 也有合法 hash。
- `domain/EditorialChoice.java`（新增）：`(selection, originalLine, fallbackApplied, fallbackReason)`，构造器拒绝自相矛盾组合；`EditorialSelection` 一字未改。
- `service/impl/EditorialDecisionService.java`：`CandidateRef` / `DatasetRef` / 新 `Pools` / `Decision.focusCandidateId`；prompt 输出 `[id] 标题` 与 `key | 名称（cadence，shapes）`；未知 focus ID 判 `editorial_decision_invalid`。
- `service/impl/DatasetRouter.java`：`eligible(now)` 只查库，`pick(run, input, eligible)` 只调模型，`route()` 退化为两者组合（仅供老快照回退）。
- `service/impl/EditorialLineEditor.java`：`select(...)` 返回 `Optional<EditorialChoice>`；`editDataBoard(intent, datasets)` 不再查库；解说线优先用 `focusCandidateId`；去掉 `HeronDataSourceMapper` 依赖与 `JsonUtils`（改本地 mapper，纯单测可跑）。
- `service/impl/NewsAutoService.java`：建池一次算全（含 datasets / sources / recent），`pools(pool)` 变纯投影；`markDecision` 挪到选择之后，槽位写最终线、`pool_json.decision` 存原判线与回退原因；去掉 `dataSourceMapper` / `usageMapper` 依赖。
- `service/impl/NewsWorkServiceImpl.java`：`checkEditorialPass` 增加 severity 白名单与 schemaVersion 校验，新增错误码 `review_legacy`。
- `resources/prompt/editorial/daily-editor.md`：说明候选编号与可选的 `focusCandidateId`。

**测试**
- 新增：`PoolDigestTest`(6)、`EditorialChoiceTest`(3)、`EditorialLineEditorFrozenPoolTest`(3)、`NewsAutoDecisionTrailTest`(3)；`EditorialCandidatePoolTest` +2、`EditorialDecisionTest` +3、`EditorialPublishGateTest` +3 并拆分 1 条。
- 改写构造方式：`EditorialCandidatePoolTest`、`EditorialDecisionTest`、`NewsAutoTickTest`（构造器参数）。

## 3. 验证

```powershell
cd D:\zhoupei\heron\heron
.\mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
```

**Tests run: 230, Failures: 0, Errors: 0, Skipped: 5 — BUILD SUCCESS**（基线 208，新增/改写后净增 22）。

**未运行**：22 个 `@Tag("mysql")` 类（需 `HERON_TEST_MYSQL_URL` 指向 `heron_it_*` 且账号有 DROP/CREATE 权限）、3 个 `@Tag("render")` 类（需 hyperframes + node≥22 + edge_tts + ffmpeg）。因此 `NewsAutoTickTest` 对 tick 调用顺序的覆盖**尚未执行**，只能算「编译通过 + 逻辑单测覆盖」。

## 4. 与任务书的两处偏差（均为实现时收窄，不是偷工）

1. **回退原因只保留两个码**：`data_board_empty` / `explainer_empty`。任务书里写的 `focus_candidate_unusable` 没有实现——锁定候选不可用时（例如选了没有正文的候选走解说线）直接交给筛稿器，不构成跨线回退，硬造一个码反而误导看板。任务书已同步更正。
2. **没有为 tick 建 Mockito 全链测试**：`slotMapper.lambda().set(...).eq(...).update()` 这类链式调用在纯单测里只能测到 mock 自身。改为把 `pools(pool)` / `executedLine(...)` / `decisionTrail(...)` 三个纯逻辑点保持包级可见由 `NewsAutoDecisionTrailTest` 直接验，调用顺序交给 mysql 组。这是覆盖上的真实缺口，不是等价替代。

## 5. 行为变更（需写进 PR 描述）

- `heron_auto_slot.selected_line` 现在记**实际执行线**；原判线在 `pool_json.decision.originalLine`。按 `selectedLine` 筛选的编辑策划页从此按执行线筛。
- 缺 `schemaVersion` 的历史审稿记录不再自动发布（`review_legacy`），需重跑审稿或人工发布。
- 总编辑提示词多了候选编号，模型可能输出 `focusCandidateId`；不输出时行为与改造前一致。
- 数据源候选优先级从「按名称排序取前 20」改为「按 sourceKey 排序取前 20」，集合构成可能变化。

## 6. 未解决 / 留给 Phase 2

- 快照里仍只有最终选择，没有原判线与 fallback（`schemaVersion` 保持 `1.0`）；完整决策链要等 Frozen Context 表。
- `NewsModelScriptAuthor` 仍不消费 `editorialIntent` / `storyAngle`，正文仍现抓（P0 第 3、4 条属于 Phase 2）。
- claim 成功、submit 失败仍无释放补偿。
- `heron_auto_slot` 没有 fallback 列，`pool_json` 是 TEXT（64KB 上限），决策链变复杂后需要独立列或表。
