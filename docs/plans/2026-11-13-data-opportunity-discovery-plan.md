# 数据机会发现层实施方案（Phase 1'）

**日期：** 2026-11-13
**前置：** `docs/reports/heron-phase0-data-driven-baseline.md`
**数据基础（已确认）：** 外部公开数据（现有 119 个注册源）
**状态：** 方案待确认，尚未实施

---

## 一、目标

让系统能从数据里**自己找出值得做的选题**，而不是按轮换把某个数据源做成视频。

验收要看的是这一条：**给定一批数据源和今天的数据，系统能否说出「哪一项变了、变了多少、为什么值得讲」，并且这个判断能被审计。**

不是「能不能自动出片」——那个已经能了。

---

## 二、现状约束（Phase 0 实测）

| 约束 | 说明 |
|---|---|
| 无分析层 | `DatasetFacts` 只算单源 first/last/min/max/change |
| 选源无题材 | `EditorialLineEditor.decisionContext()` 硬编码 `title="数据看板"` |
| 轮换代替选择 | 模型回 `none` → `DatasetRouter.digest()` 按 20 期栏目周期轮换 |
| 数据被新闻卡死 | `candidatePool()` 新闻为空即返回空池，不查数据源 |
| 数据线只能自动 | 手动认领不写 selection → `kind` 永远是 `article` |
| 总编辑看不到数值 | `DatasetRef.label` = `名称（cadence，shapes）` |
| 取数窗口随 cadence 漂 | `change` 是「首末之差」，daily=30 天、annual=30 年 |

两个可以直接利用的现成条件：

- 适配器多数支持最多 **2000 个点**（`SinaFuturesDatasetAdapter` / `EastmoneyIndexAdapter`），当前生产只用 30。
- 每次取数拿的是**完整序列的尾部窗口**，所以「最近 7 天 vs 之前 30 天」这种对比，**单次取数就能算，不需要先建历史快照库**。

---

## 三、方案：四层

### L0 取数加宽（分析专用）

分析取数走独立口径（建议 120 点），不复用生产的 `limit=30`。

- 生产取数保留现有 30 点（图表只需要这么多，且 `DatasetContract` 有行数约束）。
- 分析取数新增 `DatasetFetchService.fetchForAnalysis(run, sourceKey, points=120)`：复用同一批 adapter 与 `DatasetContract` 校验，只是 `points` 不同。
- **不改** 现有 `fetch()` 签名，避免影响生产与既有测试。

### L1 确定性分析（新类 `DatasetSignal`）

纯函数，输入 dataset 快照，输出信号。**所有数字由程序算，模型不参与。**

窗口按「点」定义，同时带上 cadence，展示时再翻译成「天/月/年」——避免再次出现「30 个点」含义漂移。

| 信号 | 含义 | 适用 shape |
|---|---|---|
| `change_pct_1` | 最新一点相对前一点的变化率 | wide |
| `change_pct_w` | 最近 w 点累计变化率（w=7） | wide |
| `zscore` | 最新值相对前 N 点均值/标准差的偏离 | wide |
| `new_extreme` | 是否创窗口内新高/新低 | wide |
| `rank_delta` | 名次变化（当前 vs 前一期） | rows |
| `entity_spread_delta` | 实体间差距的变化 | long |
| `coverage` | 实际点数 / 请求点数（数据完整性） | 全部 |

阈值先做成常量，Phase 后续再配置化。

### L2 机会候选（新表 + 接入候选池）

**新表 `heron_data_opportunity`：**

| 字段 | 说明 |
|---|---|
| `opportunity_id` | 雪花 ID |
| `source_key` / `entity` / `metric` | 定位到具体指标 |
| `shape` / `window_points` / `cadence` | 分析口径 |
| `score` | 确定性评分（见下） |
| `signals` | JSON：上面 L1 的全部信号 |
| `observed_at` / `period_start` / `period_end` | 数据时间 |
| `insight` / `title` | 模型基于 signals 写的解读（可为空） |
| `status` | `new` / `used` / `rejected` / `expired` |
| `reject_reason` | 淘汰原因（可空） |
| `used_version_id` | 被哪条视频用了 |
| `dedup_key` | `source_key + entity + metric + 信号类型`，防同一天重复出新 |

**评分（确定性、权重可配置）：**

```
score = w_z*min(|zscore|,6) + w_c*min(|change_pct_w|,20) + w_r*|rank_delta|
        + w_e*(new_extreme?1:0) + w_col*column_weight - w_dedup*recent_penalty
```

`column_weight` 复用现有 `Column`（TECH 30 / MARKET 20 / BUSINESS 20 / TREND 15 / ATLAS 15）。

**模型只做一件事：** 把 `score` 靠前的信号的 `signals` 改写成 `title` + `insight`（为什么值得看）。模型**不得**覆盖任何数字，也不得新增 signals 里没有的事实。

### L3 决策与生产接入

| 改动 | 内容 |
|---|---|
| `EditorialCandidatePool` | 增加第 6 个列表 `opportunities`（不可变，进 `PoolDigest`） |
| `EditorialDecisionService.Pools` | 增加 `opportunities`，label **带数值摘要**（如 `伦敦金 XAU：7 日 +4.2%，偏离 2.8σ`） |
| `prompt/editorial/daily-editor.md` | 重写「权重」一节：从「默认走数据看板」改为「有机会才走数据看板，机会 < 阈值就不发」 |
| `EditorialLineEditor.editDataBoard` | 增加重载：直接锁定机会的 `source_key`，**不再调 `DatasetRouter`**；`editDataBoard(intent, datasets)` 保留给 manual/legacy |
| `NewsAutoService.candidatePool` | 新闻候选为空**不再整体短路**；改为「机会为空且新闻为空才算空池」 |
| 槽位跳过原因 | `no_candidate` 拆成 `no_news_candidate` / `no_data_opportunity`，否则排查会指错方向 |
| `DatasetFetchService` | 生产取数仍 30 点；数据线生产时把机会的 `window_points` 带进快照元信息 |

**新增定时任务：** 每日分析一次（早于第一个产出槽位），写入机会表。

---

## 四、文件清单

### 新增

| 文件 | 用途 |
|---|---|
| `service/impl/DatasetSignal.java` | L1 信号计算，纯函数 |
| `service/impl/DataOpportunityService.java` | 分析→评分→落库→选优 |
| `service/impl/DataOpportunityRunner.java` | 每日定时入口（复用 `ScheduledExecutorService` 模式） |
| `domain/HeronDataOpportunity.java` + mapper | 机会实体 |
| `domain/vo/DataOpportunityVo.java` + `bo/DataOpportunityQuery.java` | 列表/查询 |
| `controller/DataOpportunityController.java` | 机会查看（只读 + 人工淘汰） |
| `script/sql/heron/030-data-opportunity.sql` | 建表（含回滚说明） |
| `prompt/data-board/opportunity.md` | 把 signals 写成 title/insight 的契约 |
| 测试 | `DatasetSignalTest`、`DataOpportunityServiceTest`、`DataOpportunityMysqlTest`、`DataOpportunityPipelineTest` |

### 修改

| 文件 | 改动 |
|---|---|
| `DatasetFetchService` | 加 `fetchForAnalysis(points)`，不动 `fetch()` |
| `EditorialCandidatePool` / `PoolDigest` | 增加 `opportunities` 列表并纳入 hash |
| `EditorialDecisionService` | `Pools` 增加 opportunities，prompt 增加数值节 |
| `EditorialLineEditor` | 新增按机会锁源的重载 |
| `NewsAutoService` | 拆掉新闻短路；先跑分析再建池；槽位原因细分 |
| `daily-editor.md` | 权重与判断依据重写 |
| `NewsProductionDomain` | 结果里带机会 ID，供追溯 |

---

## 五、兼容性

| 面 | 影响 |
|---|---|
| 数据库 | **纯新增表**，不改现有列，无数据迁移。回滚 = `DROP TABLE` |
| API | 纯新增（机会列表只读）；现有接口不变 |
| 生产链路 | `NewsProductionPipeline` 的渲染/闸/artifact/租约**完全不动** |
| 历史任务 | 不受影响；没有机会记录的旧任务照常可查 |
| 提示词 | `daily-editor.md` 重写；`routing.md` 保留但不再是自动主路径 |

---

## 六、测试计划

| # | 用例 | 期望 |
|---|---|---|
| 1 | 单点数据 | 不出信号，不报错 |
| 2 | 恒定序列 | zscore 为 0，不入选 |
| 3 | 明显跳变 | `change_pct_1` + `zscore` 超阈值，入选 |
| 4 | 创新高 | `new_extreme=true` |
| 5 | `rows` 名次互换 | `rank_delta` 正确 |
| 6 | 数据点数不足 | `coverage` 体现，按不足处理 |
| 7 | 同一机会重复分析 | `dedup_key` 去重，不产生重复行 |
| 8 | 评分排序 | 高分机会排在前面，栏目权重生效 |
| 9 | 机会 → 池 → 总编辑 | 总编辑输入里能看到数值 |
| 10 | 机会 → 线内编辑 | `selection.sourceKey` 等于机会的源，`digest=false` |
| 11 | 新闻候选为空 + 有机会 | **仍能产出数据看板** |
| 12 | 机会为空 + 有新闻 | 走解说线，行为不变 |
| 13 | 机会为空 + 新闻为空 | 空池，原因 `no_opportunity_no_candidate` |
| 14 | 模型改写 insight | 数字与 signals 一致，未被篡改 |

命令：

```
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
./mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test -Dheron.test.excludedGroups=exclude,mysql
```

（`mysql` 组仍需 `heron_it_*` 隔离库；新表测试会落在这一组。）

---

## 七、建议分三步走，每步独立验收

### 步骤 1：L1 + 机会表（不接入生产）

交付：`DatasetSignal` + 机会表 + `DataOpportunityService` + 每日分析任务 + 只读接口。
验收：跑一天真实数据，人工看机会列表是否**真的像可做的选题**，而不是噪声。
**这一步不改变任何生产行为，风险最低，且能最早回答「发现能力到底成不成立」。**

### 步骤 2：接入候选池与总编辑

交付：池/摘要/总编辑带数值；`daily-editor.md` 重写。
验收：同一批数据下，总编辑能说出「为什么今天做这条」，理由里有具体数字。

### 步骤 3：接入线内编辑与生产，拆掉新闻短路

交付：按机会锁源、槽位原因细分、`candidatePool` 解耦。
验收：连续跑若干槽位，出现「只因为数据机会而产出」的片子，且链路可追溯。

---

## 八、风险

| 风险 | 说明 |
|---|---|
| 信号噪声 | 阈值拍脑袋会让每天都有"异常"。步骤 1 必须先看真实数据再定阈值 |
| 窗口含义再次漂移 | 必须把 `window_points` + `cadence` 一起落库，展示时翻译成时间 |
| 模型改写越界 | insight 只允许基于 signals 表述，需测试 14 兜底 |
| 取数成本 | 每日对 119 源各取 120 点，出网量上升；需按 cadence 分级（annual 源不必日更） |
| 栏目轮换被完全取代 | 保留 `digest()` 作为兜底，避免分析层故障时零产出 |

---

## 九、明确不做

- 不做抖音自有账号数据采集（已确认外部公开数据优先）。
- 不做历史快照持久化——先用单次取数的窗口，等需要「异常是否持续」再补。
- 不做跨源自动关联（如「金价 vs 美元指数」）——留到信号层稳定之后。
- 不做机会的人工打分干预（先只支持淘汰）。
- 不改渲染、时长（90 秒）、素材冻结——与本目标无关。
