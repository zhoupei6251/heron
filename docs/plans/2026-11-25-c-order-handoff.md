# C 单交付说明与 A 单钩子请求

日期：2026-11-25　对应施工单：`2026-11-25-video-line-three-tasks.md`

## 一、交付内容

### C1 审稿修复执行环

| 文件 | 变化 |
|---|---|
| `QualityRepairPlanner.java` | 新增 `planForReproduction(report)`（final 报告的重生产计划）、`notes/criterionIds/allowedFields`、`narrow(原稿, 修复稿, 计划)`；原 `plan(report, mode)` 行为不变 |
| `QualityRepairService.java`（新增） | `attempt(versionId)` 计划→改稿→抄回白名单字段→契约复核→冻结进新版本输入→提交重生产；`recheck(versionId)` 比稿子指纹、判定点名判据、过闸写 `repair` 闸、不过闸回滚指针并留痕 |
| `QualityReviewer.java` | 未改：每份报告已经写 `repair{attempted:0,max:1,...}`（`QualityRepairPlanner.emptyRepair()`），修复结论由服务层在同一份报告上补写 |

要点：

- 修复只动白名单五条判据对应的字段（`scenes[0].lead`、`closing`、`scenes[].body`、`scenes[].duration_s`）。
  `narrow()` 按原稿深拷贝、只把允许改的字段从修复稿抄回来：模型顺手改的标题、别的段落、段落数量都在这里被丢掉。
- 事实类输入（数据集/趋势）读上一版**冻结产物**，不重新取数，保证「只变文字不变事实」。
- 一次修复=一个新版本，由既有 `ProductionRunner` 跑同一条流水线（不另造渲染路径）；最多一次，不叠加。
- 复检：先比稿子指纹（整稿 hash，或点名改动的字段逐字相等），再要求点名判据全部 `verdict=pass`。
  - 通过：报告 `repair.rechecked=true, passed=true`，并向 `checks` 追加一条 `gate=repair, passed=true` 的闸（含 `inputLocator=version:<上一版>` 与 `measured.attempted/previousVersionId/criteria`）。
  - 不过：`current_version_id` 指回修复前版本，新版本 `review_status=rejected` 并在报告 `notes` 记 `repair_rolled_back`。
  - 修复稿没进片子：只记 `repair_not_rechecked`，不移动指针、不当成修好。
- 扫描开关 `heron.news.quality-repair-enabled`，**默认关**。关闭时只有显式调用 `attempt()` 才会动作。

### C2 表现回流

| 文件 | 变化 |
|---|---|
| `MetricInsightAnalyzer.java` | 新增 `Preference` 与 `preference(dimension, samples)` / `preferenceOf(insight)`：只有 `strategyEligible`（L4 且稳定）、同组样本 ≥8、偏离基准 ≥15% 的分组才进权重；权重夹在 0.5~2.0，只加权、不拉黑；`未记录` 与样本不足的分组一律中性 |
| `NewsTemplatePicker.java` | 选版式处读 `performanceInsight("template")` 的偏好：有信号就带权抽样，没信号走原来的模型路径（逐字不变）；`weightedPick` 暴露为包内静态，单测用固定种子跑分布 |

- 偏好由采样行实时算出，不另存一份状态：口径始终与 `performanceInsight` 一致。
- 读表现失败只降级为中性并留一条 warn，不阻塞选版式。

## 二、A 单需要补的三处钩子（C1 执行的前提）

流水线目前不读 `editorial.qualityRepair`，所以修复稿进不去；钩子就是「写稿阶段优先用冻结的修复稿」。
一次声明、三处使用，插在 `NewsProductionPipeline.execute()` 写稿分支前（约 175~183 行附近）：

```java
JsonNode frozenRepair = input.path("editorial").path("qualityRepair").path("script");
```

数据集线（现 205~209 行）：

```java
DatasetScriptAuthor.Result draft = frozenRepair.isObject()
    ? new DatasetScriptAuthor.Result(frozenRepair.toString(), null)
    : trend ? datasetAuthor.authorTrend(run, fetched.json(), JSON.writeValueAsString(trendDoc), route.digest())
            : datasetAuthor.author(run, fetched.json(), route.digest(), story);
```

知识线（现 213 行）：`scriptJson = frozenRepair.isObject() ? frozenRepair.toString() : knowledgeAuthor.author(run);`

解说线（现 222 行）：`scriptJson = frozenRepair.isObject() ? frozenRepair.toString() : author.author(run);`

钩子生效后，契约闸、审稿、配音、渲染、字幕、quality 报告全部照常跑——修复稿不是绕过任何闸，
只是跳过一次重新写稿。钩子到位后再打开 `heron.news.quality-repair-enabled`。

## 三、验收对照

- C1「造一条必然不过 early_payoff 的稿子」：`attempt` 返回 `submitted` 并冻结修复稿；复检三分支分别记
  `repaired` / `repair_rolled_back` / `repair_not_rechecked`。成功分支可在 `checks` 里查到 `gate=repair`。
- C1「不出现第二次 TTS」：修复必然重配一次音——旁白变了，音轨必须重做。这一点写进报告的 `repair` 块（改动字段清单），
  不假装省掉了 TTS。
- C2「样本不足时结果与不带偏好一致」：`hasSignal()` 为假时 `preferredPick` 返回 null，走原模型路径。
- C2「固定种子 100 次概率上升」：`MetricInsightPreferenceTest#weightedPickFavoursTheWinner` 用种子 20261125 跑 100 次。

## 四、边界与取舍（需知会）

1. `NewsTemplatePicker.java` 不在 C 单文件区。它是两条入口（线内编辑、人工认领）选版式的唯一汇聚点，
   把加权放在这里一处，比在 `EditorialLineEditor` 与 `NewsWorkServiceImpl` 各写一份更不容易分叉。该文件未与 A/B 冲突。
2. `checks` 只放通过的闸（`NewsProductionDomain.validateChecks` 要求每条 `passed=true`），
   所以**失败**的修复结论记在质量报告的 `repair` 块与 `notes`，不进 `checks`。
3. 回滚不删版本：新版留在历史里并标 `rejected`，上一版重新成为 `current_version_id`。
4. 修复版的 `hookType` 取不到（钩子按 `null` 写），该版本的标题钩子类型会记「未记录」，归因维度按「未记录」统计。

## 五、验证情况

- `mvn -o -pl ruoyi-modules/ruoyi-heron test`：994 个用例全通过（含新增 `QualityRepairDecisionTest` 6 个、`MetricInsightPreferenceTest` 7 个）。
- 未验证：端到端修复（需 MySQL、模型、渲染链与 A 单钩子）；Spring 上下文启动（本机未起服务）。
  开启扫描前请先确认钩子已合入，否则每次修复都会白跑一条流水线并被复检判「修复稿未被采用」。
