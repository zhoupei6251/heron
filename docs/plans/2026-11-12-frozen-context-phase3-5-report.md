# 冻结上下文 · Phase 3-5 实施报告

> 基线 `dev` @ `0882c7a93`。本轮把「剩下全部」按可交付粒度拆成三块：正文冻结（Phase 3 的文本部分）、
> 写稿消费编辑意图（Phase 4 的 P0 部分）、claim/submit 补偿（Phase 5 的一项）。
> 明确没做的两件事写在 §5，原因不是没时间，是现在做会做出假货。

## 1. Phase 3（文本 Artifact）：正文冻结

**表** `heron_production_artifact`（`029-production-artifact.sql`）：`task_id` + `kind` 唯一，
存规范化正文 + 内容摘要 + 抓取时间。只插入不更新。

**服务** `FrozenArticleService`
- `of(taskId, input)`：先查已冻结；没有就按输入解析出处 → 抓一次 → 冻结（并发撞唯一键则回读已存的那份）。
- `article(taskId)`：读回来先校验 `sha256(content)` 与记录一致，不一致抛 `news_artifact_mismatch`。
- `articleUrl(input)`：**自动路径必须命中线内编辑选中的证据**，命中不了直接 `news_script_source` 失败；
  没有 `selection` 节点的老快照（手工生产）才按 legacy 取第一条带链接的证据。

**接入** `NewsModelScriptAuthor` 不再是「自己出去抓」的角色：正文由冻结层给，写稿阶段不出网。
`author` / `repair` 都走同一条冻结正文，重试读到的就是首次那份。

## 2. Phase 4（P0 部分）：写稿真吃编辑决策

`prompt()` 现在把 `editorial.editorialIntent` / `reason` / `storyAngle` 写进模型输入：
- 有意图没角度时只写意图，不编造角度；
- 老快照没有 `editorial` 节点时整行省略，不塞空串。

配了 `NewsModelScriptAuthorTest` 三条钉住这个行为——「字段躺在快照里没人读」正是决策失效的根因。

## 3. Phase 5（一项）：claim 成功、submit 失败的补偿

`NewsWorkServiceImpl.abandonAuto(workId)`：
- 只回收「没产出过版本」的作品（`current_version_id is null`），已出片的不动；
- 清 `work.active_task_id` 与候选的 `claimed_by/claimed_at/active_task_id`；
- 只在候选仍属于该 owner 且未发布时生效。

`NewsAutoService` 的失败分支调用它，并把补偿自身的异常吞成 warn——补偿失败不该掩盖原始错误。
不做的后果是：候选带着 `claimed_by` 被建池查询（`claimed_by is null`）永久排除，失败一次等于静默拉黑。

## 4. 验证

```powershell
.\mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
→ Tests run: 251, Failures: 0, Errors: 0, Skipped: 5 — BUILD SUCCESS
```

新增单测：`FrozenArticleServiceTest`(8)、`NewsModelScriptAuthorTest`(3)。

**mysql 集成组（本地隔离库 `heron_it_local`，2026-11-12 已执行）**：

```powershell
$env:HERON_TEST_MYSQL_URL="jdbc:mysql://localhost:3307/heron_it_local?..."
.\mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test "-Dheron.test.excludedGroups=exclude,render"
```

- 修复 `NewsMysqlTestConfiguration`：`@Import` 补 `FrozenContextService` / `FrozenArticleService`
  （Phase 2/3 给 `NewsProductionDomain` / `NewsModelScriptAuthor` 构造器加的依赖没跟上测试装配，
  之前整组 ApplicationContext 加载失败、同上下文测试类集体 ERROR）。
- 修复 `ProductionContextTest.verificationCatchesTamperedSnapshot`：手动生成路径的快照
  `kind` 恒为 `article`，原用例篡改的 `data_board` 是空操作；改为替换必然存在的值并断言篡改生效。
- 执行结果：`ProductionContextTest` 3/3、`AutoClaimReleaseTest` 2/2、`NewsWorkTransactionTest` 9/9
  等在无既有毒化前置时全绿。

### 4.1 已知既有问题：mysql 组的顺序依赖毒化（非本轮引入）

整组按字母序全量跑时，从 `NewsPublishAccessTest` 起所有共享 `NewsMysqlTestConfiguration`
上下文的类全部以 `Lock wait timeout` 失败（每个用例 50s 超时）。归因结论：

- **基线复现**：在 `9a1fac7e5`（Phase 1 之前、无任何冻结上下文改动的代码）上，
  最小组合 `NewsDeduplicationTest → NewsDouyinCliPublishTest → NewsPublishAccessTest`
  同样失败——**既有测试基建问题**，此前只是因为整组从未真正跑起来而没有暴露。
- **最小触发条件**：`NewsDeduplicationTest`（共享上下文，14 用例）先跑 →
  `NewsDouyinCliPublishTest`（独立上下文 + `@Primary` 发布替身）后跑 → 之后任何共享上下文的类中招；
  任一环节缺失则全绿（`{Dedup, MetricXml, Publish}`、`{Douyin, MetricXml, Publish}` 等组合均通过）。
- **现场特征**（MySQL `performance_schema` 取证）：受害 claim 事务执行到
  `INSERT INTO heron_news_work` 后停在事务中间（连接 idle、持 lane + 候选行锁、永不提交）；
  同一 claim 调用的后续 `UPDATE heron_news_candidate` 却落在一条 **autocommit=1 的裸连接**上，
  等在自己事务的 `SELECT ... FOR UPDATE` 锁上直至 50s 超时。即同一 `@Transactional` 方法内
  mapper 会话中途与事务绑定脱钩，形似「前序类遗留的挂起事务状态 + 事务同步被 suspend 后未 resume」。
- **临时规避**：避免 `NewsDeduplicationTest` 与 `NewsDouyinCliPublishTest` 相邻执行
  （如 `-Dtest` 显式排除或调整顺序）；根治需要排查 `NewsDeduplicationTest` 收尾时
  main 线程上遗留的事务同步状态，属独立工作项。

另：`HeronMenuSeedTest.seedAppliesIdempotentlyOnRealSysMenu` 断言「权限标识重复」失败，
与毒化无关，同属既有问题（种子数据里存在重复权限标识），待单独处理。

## 5. 明确没做的部分

| 项 | 为什么不做 |
| --- | --- |
| dataset 响应冻结 | `DatasetFetchService.Result` 里带 `ProductionArtifactVo`，其 `storageKey` 是「本次尝试」的路径（`a{attempt}-{runToken}`）。跨 attempt 复用同一份 Result 会得到一个指向已失效路径的产物引用，等于造一条坏数据。要做必须先设计「按 task 而非按 attempt 存放的产物根」，这是独立设计题。 |
| 图片冻结 | `heron_production_artifact.content` 是 `longtext`，不是二进制容器。图片要落对象存储（MinIO 已接 `OssFactory`），并要定清理策略；塞进 DB 会让单行膨胀到 MB 级。 |
| 阶段幂等键（Phase 5 第 4 项） | 任务级幂等键（`uk_task_request`）与租约恢复（`ProductionRecoveryService` + `lease_until`）已经存在。按阶段拆键是新的产品口径（哪些阶段可重入、哪些必须一次过），拍脑袋做会造出「重试跳过某阶段」的洞。 |
| Phase 6 端到端与灰度 | 需要真实新闻样本、真实渲染环境与隔离库；本机没有。 |

## 6. 上线顺序

1. `028-production-context.sql`
2. `029-production-artifact.sql`
3. 发应用

两张表缺任何一张，生产提交或写稿阶段会直接报表不存在——不是降级，是失败。

## 7. 行为变更

- 解说线写稿不再实时抓正文：同一 task 的首次抓取结果被冻结，重试复用。
- 自动路径证据定位失败会直接失败（`news_script_source`），不再退回第一条；手工路径保持旧行为。
- 写稿提示词多了三行编辑决策（意图/理由/视角）。
- 自动产出提交失败时，作品与候选会被释放（可被下一次建池重新选中）。
