# 冻结上下文改造 · Phase 0 基线

> 对照 [heron-backend-frozen-context-implementation-plan.md] 做的代码基线核实。
> 基线：`dev` @ `9a1fac7e5`（`chore(config): prod 数据源主机名改为 localhost_prod`），工作区干净。
> 本文只记录「现状是什么」与「方案哪里假设错了」，不含任何代码改动。

## 1. 测试基线

| 项 | 结果 |
| --- | --- |
| 命令 | `.\mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test` |
| 分组 | 默认 `heron.test.excludedGroups=exclude,mysql,render`（`ruoyi-modules/ruoyi-heron/pom.xml:16`） |
| 结果 | **BUILD SUCCESS / Tests run: 208, Failures: 0, Errors: 0, Skipped: 5**，32s |
| 未覆盖 | 22 个 `@Tag("mysql")` 类（需 `HERON_TEST_MYSQL_URL` 指向 `heron_it_*` 库 + DROP/CREATE 权限）、3 个 `@Tag("render")` 类（需 hyperframes + node≥22 + edge_tts + ffmpeg） |
| 注意 | `target/surefire-reports` 里旧报告（362 跑 1 failure / 87 error）是上一次含 mysql 组的残留，不是当前基线；引用它之前先重跑 |

纯单测 208 条是本次改造唯一可靠的回归护栏。mysql/render 两组在改动前必须先确认环境可用，否则 Phase 5 的渲染验收没有基线。

## 2. 实际调用链（与方案 §17 的差异）

`NewsAutoService.tick()`（`service/impl/NewsAutoService.java:125-198`）单方法串起全流程，**无事务**，每步独立写库：

```
slot 插入(status=running)                       :135
candidatePool(now)  一次查库 + 白名单 + 24h 过滤  :154 / :207-229
pools(pool, now)    投影成 4 个标题字符串列表     :159 / :233-241
editorial.decide(pools)                         :160
markDecision(slot, decision, pools)             :166   ← 早于线内选择
lineEditor.select(selectedLine, pool, intent)   :174   ← 可能 fallback 到另一条线
slot.candidateId = picked.candidateId()         :186
newsWork.claimForAuto(picked, actor)             :190   ← 事务 A
newsWork.generateForAuto(workId, requestKey…)    :191   ← 事务 B
```

关键结构：

- `EditorialCandidatePool` 是 `record(List<HeronNewsCandidate> hot, List<HeronNewsCandidate> articles)`（`domain/EditorialCandidatePool.java:15-16`），**只有这两类候选**，没有 evidence/dataset/template 候选列表，也没有 `poolId` / `poolHash`。
- 总编辑拿到的 `EditorialDecisionService.Pools` 是 `(hot, articles, sources, recent)` 四个**标题字符串**列表（`NewsAutoService.java:241`），连 `candidateId` 都没有。
- 线内编辑拿到的是同一个 `pool` 引用（`:174`），候选库不再重查，但数据源会重查（见 §4）。

## 3. Snapshot 现状

`NewsProductionDomain.reserve()`（`:104-122`）写 12 个顶层字段：
`schemaVersion("1.0" 硬编码) / workId / candidateId / versionNo / revision / title / draft / evidence / assets / kind / editorial / selection`。

- `kind` 是**字符串** `"data_board"|"article"`（`:117`），没有 `ProductionKind` 枚举。
- `editorial` 内含 `runKey/line/editorialIntent/reason/storyAngle/promptVersion`（`:65-73`）。
- `selection` 内含 `candidateId/dataSourceKey/digest/templateId/storyAngle/sourceEvidenceIds`（`:77-86`），**没有 `selection.line`**。
- 落库：`heron_video_version.input_snapshot json` + `heron_production_task.input_snapshot json`；`canonical()` 用 TreeMap 排键（`:522-530`）。
- 读取：`NewsProductionPipeline:105`（入口）、`:108`（kind）、`:337-357`（frozenRoute）。

## 4. 方案假设与代码现实的偏差（按影响排序）

| # | 方案假设 | 现实 | 影响 |
| --- | --- | --- | --- |
| 1 | 总编辑与线内编辑共享「同一批候选对象」 | 总编辑只拿到 **4 个标题字符串**，无 ID；对齐靠标题文本 | 「同一批数据」当前无法用 hash 证明，Phase 1 必须先补 pool 快照 |
| 2 | 存在 `poolId` / `poolHash` / `contextId` | 全模块 0 命中；池子唯一痕迹是 `heron_auto_slot.pool_json`（TEXT，标题摘要，`NewsAutoService.java:327`） | 验收标准 §15.3 的全部等式目前无从计算，先落 ID/hash 再谈校验 |
| 3 | fallback 后 `selectedLine` / `kind` 一致 | `markDecision` 在 `:166`，早于 `:174` 的 select；fallback 发生在 `EditorialLineEditor.select` `:53-55`。结果：`slot.selected_line=DATA_BOARD`、`picked.line()=NEWS_EXPLAINER`、`snapshot.kind=article`，**无 fallback flag/原因字段** | 真实不一致，可复现；P1 成立 |
| 4 | 线内编辑不做二次路由 | `editDataBoard` 调 `datasetRouter.route`（`EditorialLineEditor.java:88`）；fallback 到另一条线时最坏再调一次（`:53-55`） | P0 成立，且成本按 2 次模型调用估 |
| 5 | 写稿消费 `editorialIntent/reason/storyAngle` | `NewsModelScriptAuthor.prompt()`（`:136-145`）只用 title/draft/正文 HTML；三个编辑字段**一次都没进 prompt** | P0 成立 |
| 6 | 证据缺失即失败 | `sourceUrl()` 在选不中时回退第一条（`NewsModelScriptAuthor.java:111-117`，注释自认「保证手工认领路径可用」） | P0 成立；要拆 legacy 路径，不能直接删 |
| 7 | 正文已冻结 | 写稿时 `fetcher.fetchArticle()` **现抓**（`:39`/`:58`）；配图 `materializeImages` 现下载（`NewsProductionPipeline:793`）；数据集 `datasets.fetch` 现抓（`DatasetFetchService:66-69`） | P1 成立；这 4 处是全链路最大穿透点 |
| 8 | 审稿门禁严格 | `checkEditorialPass`（`NewsWorkServiceImpl.java:476-508`）只查 `result=="PASS"` + `issues` 是数组 + 逐条比 `BLOCKER/MAJOR` 字面量；**未知 severity（`SEVERE`/小写 `blocker`）静默放行，不看 `schemaVersion`/`promptVersion`/`reviewedAt`** | P1 成立，且是安全口径问题，不是体验问题 |
| 9 | claim/submit 有补偿 | 两个独立事务（`:190`/`:191`），catch 只写 `finish(slot,"failed")`（`:193-197`），候选 `claimed_by` 不回滚 | 候选被永久移出池子，P1 成立 |
| 10 | 生产任务状态是枚举 | DB/实体是 `String status`（`HeronProductionTask.java:40`），枚举 `ProductionStatus` 只在 6 个读写点使用；`heron_auto_slot.status` 是另一套 `running/skipped/failed/produced` 字符串 | 方案 §11.1 的 21 个状态值不要直接当 DB 枚举扩散 |

## 5. 现有表与方案 8 张建议表的映射

| 建议表 | 现状 | 差距 |
| --- | --- | --- |
| `news_candidate_pool` | 无表；内存 record + `heron_auto_slot.pool_json` | 无 poolId/poolHash/成员行 |
| `news_production_context` | `heron_production_task.input_snapshot` + `heron_video_version.input_snapshot` | 无独立主键、无版本链、无 parent/revision |
| `news_artifact` | `heron_video_version.artifact_manifest json` + `ProductionArtifactVo` | 无一行一产物、sha256 不可索引、无引用计数 |
| `news_script_version` | `heron_video_version.script json` | 一版本一稿，无多稿轨迹、无 prompt/model 记录 |
| `news_editorial_review` | `editorial_review TEXT` + `review_*` 四列 | 一版本一条，AI/人工混存 |
| `news_render_job` | `heron_production_task`（status/lease/attempt） | 无独立渲染作业、不能按阶段重试 |
| `news_stage_execution` | `heron_task_step`（`uk(task_id,attempt_no,stage)`） | **基本够用**，缺阶段租约与输出 locator 独立列 |
| `news_candidate_claim` | `heron_news_candidate.claimed_by/claimed_at/active_task_id` | 无租约、无过期、无释放审计 |

建表约定（不得违反）：雪花 bigint 主键、`BaseEntity` 五件套、`datetime(3)` UTC、`utf8mb4`、hash 列 `char(64) ascii_bin`、开关位 `char(1) '0'=开`、**不加 `tenant_id`、不加 `del_flag`**、只前滚不回滚（`script/sql/heron/001..027`）。

## 6. 时长现状

- 生效上界只有 75：`NewsScriptContract.java:38-39`（30–75），`DatasetScriptContract.java:21-22` 复制了一份同样的常量。
- 90 只活在模板元数据（9 套 `template.json` 的 `recommendedDuration.max`）和渲染测试断言（`NewsProductionPipelineRenderTest.java:86` 断言 30–90s）。
- 45 是第三套默认值（`NewsProductionPipeline.java:661`/`:859` 兜底、13 套模板 `data-duration`、生成器 `news-templates.mjs:71`）。
- 结论：三套数值并存，但只有 75 会拦人；P1 的「不一致」真实存在，且改一处不会联动。

## 7. Phase 1 建议切入点（最小第一刀）

不改 schema，先让「决策 → 选择」这条现存链可证明：

1. `EditorialCandidatePool` 加 `poolId` / `poolHash`（对 `hot+articles` 的 candidateId 排序后 sha256），并把 `pools()` 从「标题字符串」改为「带 id 的摘要」，总编辑与线内编辑都收同一 `poolHash`。
2. `select()` 去掉二次 `datasetRouter.route`：数据源候选在建池时一并算好放进 pool，线内编辑只挑不查。
3. fallback 显式化：`EditorialSelection` 增 `originalLine` / `fallbackApplied` / `fallbackReason`，`markDecision` 挪到 select 之后或改写为「决策草稿 + 最终决策」两段。
4. `checkEditorialPass` 收紧：未知 severity 拒绝、缺 `schemaVersion` 拒绝（先加 `EditorialPublishGateTest` 用例再改，红→绿）。

四条都可纯单测验证（现有 208 条是护栏），且不触碰 Render/MinIO/FFmpeg。Frozen Context 表与 Artifact 化留给 Phase 2/3。

## 8. 未验证项

- 22 个 mysql 组用例未跑（缺隔离库凭据），`ProductionTransactionTest` 等历史 error 未复核。
- 3 个 render 组用例未跑（hyperframes 需 node≥22，本机 PATH 上是 20.x）。
- 真实新闻样本的端到端时延未测；方案 §15.4 的成片验收本轮不涉及。
- 线上 `heron_auto_slot.pool_json` 的实际内容未抽样（可能已有历史脏数据）。
