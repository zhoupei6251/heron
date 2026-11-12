# 冻结上下文 · Phase 1 实施任务书（候选池与编辑决策一致性）

> 基线：`heron` 仓 `dev` @ `9a1fac7e5`。分支从 `dev` 切 `feat/frozen-context-p1`。
> 范围：**只改内存里的候选池与决策链**。不新增数据库表、不改列类型、不碰 Render / FFmpeg / HyperFrames / MinIO。
> 前置：Phase 0 基线见 `docs/plans/2026-11-12-frozen-context-phase0-baseline.md`。
> 执行纪律：每个任务先写/改测试（红）→ 最小实现（绿）→ 提交。未跑 mysql/render 组的结论不得写成「已验证」。

## 0. 一句话目标

让「总编辑看到的候选」和「线内编辑挑的候选」是**同一份可校验身份的快照**，并让「原始决策线 / 最终执行线」都能追溯。

## 1. 现状差量（只列要动的地方）

| 位置 | 现状 | Phase 1 之后 |
| --- | --- | --- |
| `domain/EditorialCandidatePool.java:15` | `(hot, articles)` 两个 list，无身份 | `(poolId, poolHash, hot, articles, datasets, sources, recent)`，构造时算 hash |
| `NewsAutoService.pools():233` | 投影成 4 个**标题字符串**，且现查 dataSource/recent | 从 pool 派生带 ID 的视图，**不查库** |
| `NewsAutoService.dataSourceOptions():245` | 线内阶段才查数据源 | 前移到建池 |
| `EditorialLineEditor.editDataBoard():85` | 调 `datasetRouter.route()` → 重查库 + 重过滤 + 模型 | 只从 `pool.datasets()` 里用模型挑一条，不查库 |
| `EditorialLineEditor.select():45` | 返回 `Optional<EditorialSelection>`，fallback 不留痕 | 返回 `Optional<EditorialChoice>`，带 originalLine/fallback |
| `NewsAutoService.markDecision():320` | 在 select 之前写 `selected_line` | 在 select 之后写**最终线**，原判线进 `pool_json` |
| `NewsWorkServiceImpl.checkEditorialPass():476` | 未知 severity 放行、不看 schemaVersion | 白名单 severity + 必填 schemaVersion，旧记录拒绝自动发布 |

## 2. 设计决策（实现时不得自行改口径）

**D1 · poolId 与 poolHash 分工**
`poolId` = `UUID.randomUUID().toString()`，标识一次快照；`poolHash` = 内容摘要。两者都只进 `pool_json` 和（Phase 2 的）context 表，本阶段不落列。

**D2 · poolHash 覆盖内容，不只覆盖 ID**
对 `{hot, articles, datasets, sources, recent}` 按稳定键排序后做 canonical JSON（键名升序、UTF-8、无空白）再 `sha256`。纳入的字段只允许「两阶段实际会读的」：

```text
hot/articles : candidateId, title, summary, heatScore, bestRank, lastSeenAt(epoch ms), evidence(仅 items[].sourceItemId/sourceUrl 的排序结果)
datasets     : sourceKey, name, cadence, shapes, lastUpdated(epoch ms)
sources      : sourceId, name, host(url 的主机名)
recent       : 标题字符串，按查询顺序
```

排除：`createTime/updateTime/createBy`、`claimedBy/claimedAt/activeTaskId`、`publishedAt`、任何对象引用/`hashCode`。同一逻辑内容必须得到同一 hash；改标题、改摘要、改 heatScore、增删候选都必须变。

**D3 · 唯一 canonical 实现**
新建 `domain/PoolDigest.java`：`TreeMap` 递归排序 + `JsonMapper` 序列化 + `DigestUtil.sha256Hex`。不要复制 `NewsProductionDomain.canonical()`（那是快照专用，别让它变成两处实现）。

**D4 · 发现与选择分离**
- 发现（查库 + 白名单 + 7 天去重 + probe=ok）：`DatasetRouter.eligible(LocalDateTime now)`，**纯查询**。
- 选择（模型从候选里挑一条）：`DatasetRouter.pick(ProductionRunVo run, JsonNode input, List<HeronDataSource> eligible)`，**不查库**。
- 旧 `route()` 保留原实现，仅供 `NewsProductionPipeline.frozenSelection` 缺 selection 的老快照回退使用；新链路不再调用它。

**D5 · fallback 用包装类型，不改 `EditorialSelection`**
`EditorialSelection` 的紧凑构造器承载两条线的必填校验，不要加可空字段破坏它。新增：

```java
// domain/EditorialChoice.java
public record EditorialChoice(EditorialSelection selection, String originalLine,
                              boolean fallbackApplied, String fallbackReason) {
    public String finalLine() { return selection.line(); }
}
```

**D6 · 落库：`selected_line` = 最终线，原判线进 `pool_json`**
`heron_auto_slot.pool_json` 由 `{"hotHeadlines","articleCandidates","dataSources","recentPublications"}` 扩为同结构 + 三个新键：

```json
{"hotHeadlines":[...],"articleCandidates":[...],"dataSources":[...],"recentPublications":[...],
 "poolId":"...","poolHash":"...",
 "decision":{"originalLine":"DATA_BOARD","finalLine":"NEWS_EXPLAINER",
             "fallbackApplied":true,"fallbackReason":"data_board_empty"}}
```

原 4 键名与语义**一个字都不能变**：`heron-ui/src/api/heron/editorial/types.ts:58` 与 `views/heron/editorial/index.vue:219-221` 直接 `JSON.parse` 后读这 4 个键，多余键被忽略，改名/改嵌套会白屏。

**D7 · 审稿门禁：新记录严、旧记录明确拒绝**
- 允许的 `schemaVersion`：`{"1.0"}`（`EditorialReviewer.toRecord()` 当前写的就是 `"1.0"`，`EditorialReviewer.java:51`）。
- 允许的 `severity`：`BLOCKER` / `MAJOR` / `MINOR`；缺失、空串、未知值一律拒绝。
- 旧记录（无 `schemaVersion`）返回新错误码 `review_legacy`，消息「审稿记录早于当前契约」。
- 手工发布路径 `confirm()` **不经过** `checkEditorialPass`（`NewsWorkServiceImpl.java:443-445` → `confirmAs(..., false)`），不要给手工路径加这道闸。

## 3. 任务分解

### T1 · `Pools` 改为带 ID 的摘要视图（红→绿）
- 文件：`service/impl/EditorialDecisionService.java`
- 新增 record（放在 `Pools` 上方）：
  ```java
  public record CandidateRef(long candidateId, String title) { }
  public record DatasetRef(String sourceKey, String name, String cadence, String shapes) { }
  public record Pools(String poolId, String poolHash,
                      List<CandidateRef> hot, List<CandidateRef> articles,
                      List<DatasetRef> datasets, List<String> recentPublications) { }
  ```
- `prompt()` 每行改为 `- [<id>] <title>`；`append()` 需要相应改成接受 `List<?>` 或重载。
- 验证：`EditorialDecisionTest` 的 `pools(...)` 辅助方法改成构造 `CandidateRef`/`DatasetRef`，6 条用例意图不变；`EditorialDecisionService.prompt(pools)` 输出含 `[101] 标题A` 形式。

### T2 · `PoolDigest`（红→绿）
- 文件：`domain/PoolDigest.java`（新建）
- 签名：`public static String of(EditorialCandidatePool pool)`，内部按 D2 组装投影对象。
- 红测试：`domain/PoolDigestTest.java`
  - 同一内容两次 `of()` 相等；
  - 改 `summary` → 变；
  - 改 `heatScore` → 变；
  - 增删一条 hot → 变；
  - 仅改 `updateTime` → **不变**（证明没把审计字段算进去）。
- 验证：`PoolDigestTest` 5 条通过。

### T3 · `EditorialCandidatePool` 扩为五类候选 + 身份
- 文件：`domain/EditorialCandidatePool.java`
- 新结构（保留现有 `isEmpty()`、不可变语义）：
  ```java
  public record EditorialCandidatePool(String poolId, String poolHash,
                                       List<HeronNewsCandidate> hot, List<HeronNewsCandidate> articles,
                                       List<HeronDataSource> datasets, List<HeronNewsSource> sources,
                                       List<HeronNewsWork> recent) { ... }
  ```
- 工厂方法：`of(hot, articles, datasets, sources, recent)`（算 poolId/poolHash）、`empty()`（poolId="empty"、poolHash 按空投影算，不要返回 null）。
- 保留 `sort(candidates, hasBody)` 作为 `of` 的分线步骤，或改为 `split(...)`；`EditorialCandidatePoolTest` 4 条用例语义不变（构造点同步更新）。
- 验证：`EditorialCandidatePoolTest` 通过，且新增断言 `pool.poolHash()` 长度 64。

### T4 · `DatasetRouter` 拆出 `eligible()` 与 `pick()`
- 文件：`service/impl/DatasetRouter.java`
- 新增：
  ```java
  public List<HeronDataSource> eligible(LocalDateTime now)   // 原 route() 前 3 行查询逻辑搬进来
  public Route pick(ProductionRunVo run, JsonNode input, List<HeronDataSource> eligible) // 原 route() 后 2 行
  public Route route(...)                                    // 保留，改为 eligible(now) + pick(...) 组合
  ```
- 注意：`eligible` 为空时仍抛 `dataset_slot_empty`（错误码不变），由调用方 `NewsAutoService` 捕获后照旧「数据看板候选为空」处理。
- 验证：`DatasetRouterTest` 现有用例全绿；新增 `pick()` 不触库的用例（mock mapper 不设 stub，调用不抛 NPE）。

### T5 · 建池时一次算全（`NewsAutoService`）
- 文件：`service/impl/NewsAutoService.java`
- `candidatePool(now)` 追加：`datasets = datasetRouter.eligible(now)`（异常吞掉并记 `List.of()`，不得让整个 tick 失败）、`sources = autoSources()`（把 `autoHosts()` 改造成返回 `List<HeronNewsSource>`，host 集合由它派生）、`recent = recentWorks()`（原 `pools()` 里那段查询搬出来）。
- `pools(pool)` 改为纯投影，**方法体内不得出现任何 mapper 调用**。
- 验证：新增单测 `NewsAutoPoolProjectionTest`（纯单测，mock mapper）：给定 pool 后 `pools(pool)` 的 `hot`/`articles`/`datasets` 条数与 pool 一致，且第 2 次调用不再触发 mapper（`verifyNoMoreInteractions`）。

### T6 · 线内编辑不再二次发现
- 文件：`service/impl/EditorialLineEditor.java`
- `editDataBoard(String intent)` → `editDataBoard(String intent, List<HeronDataSource> datasets)`；内部 `datasetRouter.pick(decisionRun(), decisionContext(), datasets)`，`dataSourceMapper.selectByKey` 删除，名称从 `datasets` 里取。
- `select(...)` 透传 `pool.datasets()`。
- 验证：新增单测 `EditorialLineEditorFrozenPoolTest`：传入 3 个 dataset 的 pool，断言 `datasetRouter.eligible` **零调用**、`dataSourceMapper` **零调用**（用 Mockito `verify(..., never())`）。

### T7 · 总编辑可锁定候选（`focusCandidateId`）
- 文件：`service/impl/EditorialDecisionService.java`
- `Decision` 增加第 5 个字段 `Long focusCandidateId`；`plan()` 读取 `focusCandidateId`（可缺省）：
  - 缺省/null → 保持现行为；
  - 有值但不在 `hot ∪ articles`（`DATA_BOARD` 时校验 `datasets`）→ 抛 `editorial_decision_invalid`。
- 提示词 `resources/prompt/editorial/daily-editor.md` 增加一行说明：「若锁定某条候选，回 `focusCandidateId`；不确定就不要回」。
- 验证：`EditorialDecisionTest` 新增 2 条：合法 focus 保留、未知 focus 拒绝。

### T8 · `EditorialChoice` 包装 fallback
- 文件：`domain/EditorialChoice.java`（新建）、`service/impl/EditorialLineEditor.java`
- `select()` 返回 `Optional<EditorialChoice>`；主选成立时 `fallbackApplied=false`、`fallbackReason=null`；回退成立时 `true` + 原因串：`data_board_empty` / `explainer_empty`（按「哪条线先失败」命名）。
- 验证：新增 `EditorialChoiceTest`（纯单测）：注入一个必然空的 data board（datasets 为空）→ 得到 explainer + `fallbackApplied=true` + 原因 `data_board_empty`。

### T9 · 最终决策延后落库
- 文件：`service/impl/NewsAutoService.java`
- 顺序改为：`decide` → 若 `!publish`：`markDecision(slot, decision, pools, null)` 后 `finish`；否则 `select` → `markDecision(slot, decision, pools, choice)` → 记 `candidateId` → claim → submit → `finish(detail = choice.finalLine() + "：" + intent)`。
- `markDecision` 签名改为 `(slot, decision, pools, EditorialChoice choice)`：
  - `selected_line` = `choice == null ? NO_PUBLISH : choice.finalLine()`；
  - `pool_json` 按 D6 写；
  - `editorial_intent` / `decision_reason` 仍是总编辑原文，`clip` 上限不变。
- `select` 返回空时：`selected_line` 保持总编辑原判（`decision.selectedLine()`），`detail` 记 `no_fit: <originalLine>`，行为与现在一致。
- 验证：`NewsAutoService` 的 tick 依赖真实库，纯单测里 mock 整条链只会测到 mock。因此把三个纯逻辑点
  （`pools(pool)`、`executedLine(...)`、`decisionTrail(...)`）保持包级可见，由 `NewsAutoDecisionTrailTest`
  直接验形状与语义；调用顺序由 `NewsAutoTickTest`（mysql 组）覆盖。

### T10 · 审稿门禁收紧（红→绿）
- 文件：`service/impl/NewsWorkServiceImpl.java`（只动 `checkEditorialPass`）
- 判定顺序（错误码固定）：
  1. `raw` 空 → `review_required`
  2. 非 JSON / 非对象 → `review_invalid`
  3. 缺 `schemaVersion` 或不在 `{"1.0"}` → **`review_legacy`**
  4. `result != "PASS"` → `review_not_passed`
  5. `issues` 非数组 → `review_invalid`
  6. 逐条：`severity` 不在白名单 → `review_invalid`；是 `BLOCKER/MAJOR` → `review_not_passed`
- 验证：`EditorialPublishGateTest` 修改与新增见 §4。

### T11 · 全量验证
```powershell
cd D:\zhoupei\heron\heron
.\mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
```
- 期望：**Failures 0 / Errors 0**，条数 ≥ 208 + 新增（预计 208 + 12~15）。
- mysql 组（22 类）如已配好 `HERON_TEST_MYSQL_URL`，额外跑一次 `-Dheron.test.excludedGroups=exclude,render`；跑不了就在报告里写「未运行」，不得写「通过」。

## 4. 测试清单

**必须保持通过（语义不变，只改构造方式）**
- `EditorialCandidatePoolTest` 4 条
- `EditorialDecisionTest` 6 条
- `EditorialSelectionTest` 5 条
- `FrozenSelectionTest` 3 条
- `EditorialReviewTest` / `EditorialReviewContractTest` / `EditorialSelectionViewTest`

**必须修改的既有用例**
- `EditorialPublishGateTest.passWithOnlyMinorIsAllowed()`：现在用「无 schemaVersion + MINOR」证明放行。**拆成两条**：
  - `MINOR + schemaVersion=1.0` → 放行（保留原本意图）
  - `MINOR + 无 schemaVersion` → `review_legacy`（新增意图）

**必须新增**
| 测试类 | 用例 | 断言 |
| --- | --- | --- |
| `PoolDigestTest` | 稳定性 / 内容敏感 / 审计字段不敏感 / 增删敏感 | 5 条 |
| `EditorialCandidatePoolTest` | `poolHash` 64 位、`empty()` 非 null | 2 条 |
| `EditorialDecisionTest` | 合法 focusCandidateId / 未知 focus 拒绝 / prompt 含 `[id]` | 3 条 |
| `EditorialLineEditorFrozenPoolTest` | 数据看板选源不触库、只读 pool | 2 条 |
| `EditorialChoiceTest` | 主选 fallbackApplied=false / 回退=true+原因 | 2 条 |
| `NewsAutoDecisionTrailTest` | fallback 后 selected_line=最终线、pool_json 含原判线 | 2 条 |
| `EditorialPublishGateTest` | 未知 severity 拒绝 / severity 缺失拒绝 / 无 schemaVersion→review_legacy | 3 条 |
| `NewsAutoPoolProjectionTest` | 投影不触库 | 1 条 |

## 5. 绝对不能改变的行为

1. 手工发布链路不受影响：`confirm()` 不调 `checkEditorialPass`，人工审核规则原样。
2. `NO_PUBLISH` 仍写 `selected_line=NO_PUBLISH` + `decision_reason`，空槽照样留痕。
3. `pool_json` 原 4 个键名/语义不变（前端 `types.ts:58`、`index.vue:219` 依赖）。
4. 快照 `schemaVersion` 仍为 `"1.0"`，`input_snapshot` 字段集合不变（本阶段不加 fallback 到快照，Phase 2 随 Frozen Context 一起做）。
5. 建池过滤条件不变：`published_at is null`、`claimed_by is null`、`active_task_id is null`、24h 内、`POOL_LIMIT`、来源白名单。
6. 模板选择规则不变（`NewsTemplatePicker.pick` 的三个入参与前缀逻辑）。
7. 数据源 7 天去重与 20 期栏目轮换规则不变（`DEDUP_DAYS`、`cycle()`、`cadenceRank`）。
8. 数据看板线的占位候选逻辑不变（`NewsWorkServiceImpl.placeholderCandidate`，`candidateId=null` → 建占位）。
9. `detail` 文本仍以 `<line>：<intent>` 开头（前端 `index.vue:48` 直接展示）。
10. 错误码 `review_required` / `review_invalid` / `review_not_passed` / `editorial_decision_invalid` / `editorial_pool_empty` / `dataset_slot_empty` 的既有语义不变（只新增 `review_legacy`）。
11. 不新增表、不改列、不改 `heron_auto_slot` 结构；迁移脚本本阶段一个都不加。
12. 不触碰 `NewsProductionPipeline` / `DatasetFetchService` / `NewsModelScriptAuthor` 的取数与写稿逻辑（写稿消费编辑意图属于 Phase 2，本阶段不做）。

## 6. 明确允许的行为变更（需要在 PR 描述里写清）

- `heron_auto_slot.selected_line` 的含义从「总编辑原始决策线」变为「实际执行线」；原判线移到 `pool_json.decision.originalLine`。按 `selectedLine` 筛选的编辑策划页从此按执行线筛。
- 缺 `schemaVersion` 的历史审稿记录不再能自动发布（错误码 `review_legacy`），需要重跑审稿或人工发布。
- 总编辑提示词多了候选 ID 前缀，模型输出可能带 `focusCandidateId`；不带时行为与现在完全一致。

## 7. 交付物

1. 分支 `feat/frozen-context-p1`，一个 commit 一件事（T1-T10 至少 4 个 commit：池与摘要 / 选源分离 / fallback 追踪 / 门禁收紧）。
2. 实施完毕后补 `docs/plans/2026-11-12-frozen-context-phase1-report.md`：改了哪些文件、新增哪些用例、跑过什么命令、哪些组未运行。
3. 不 push、不合并，除非另行确认。

## 8. 风险与回滚

- 风险最高的两处：`pools()` 从「查库」改成「纯投影」，漏搬查询会让数据看板候选恒为空；`markDecision` 挪位，漏掉某条失败分支会让槽位丢痕。T5/T9 的单测就是防这两条的。
- 回滚：本阶段无 DDL，`git revert` 单个 commit 即可；`pool_json` 多出的键对旧代码是未知键，旧代码读它不受影响。
