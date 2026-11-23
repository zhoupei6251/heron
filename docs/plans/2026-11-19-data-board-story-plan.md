# 数据看板：内容策划契约（story/1）设计

**日期**：2026-11-19
**范围**：`heron` 后端数据看板线（`DATA_BOARD` / `kind=data_board`）。本文只做设计，不含代码改动、不改脚本契约、不改模板。
**前置**：`2026-11-10-dataset-contract.md`（dataset/1）、`2026-11-13-data-opportunity-discovery-plan.md`（机会层）、`2026-11-12-editorial-decision-chain-design.md`（决策链）

---

## 0. 一句话

现在数据看板是「取一份数据 → 想出五个标题 → 写 3–6 段旁白」，中间没有一步回答**「这条片子讲的是什么事」**。本设计把这一步做成一个结构化、可校验、随版本冻结的产物：`story`。

它插在**线内编辑选中数据源之后、写稿之前**，落在**现有冻结快照的新增节点**里（不建新表），数字一律用 `{{facts.*}}` 占位符，所以可以在取到数据之前就写完，取到数据之后再被机器逐条复核。

三条不可让的边界：

1. **没故事就不做**：数据看板线的 `EditorialSelection` 没有 story 就不能进入生产（新链路），而不是写出一版"有稿无题"的片子。
2. **模型不写数字**：story 里出现的所有数值都是占位符，`facts` 是唯一数值来源（与 dataset/1 同一条铁律）。
3. **脚本 JSON 一个字段都不加**：策划结论通过提示词与 Java 复核两条通道传递给写稿，`DatasetScriptContract.FIELDS` 保持 7 个。

---

## 1. 现状取证（2026-11-19 的 `dev`）

| 断言 | 位置 | 事实 |
|---|---|---|
| 写稿只知道"选源意图" | `EditorialLineEditor.java:138` | 数据看板线把总编辑的 `editorialIntent` 直接当 `storyAngle` 存进选择结果；两者语义不同 |
| 冻结快照没有故事层 | `NewsProductionDomain.java:109-131` | 快照只有 `editorial` / `selection` 两个编辑节点，`kind` 由 `selection.dataBoard()` 决定 |
| 写稿不吃编辑字段 | `DatasetScriptAuthor.java:86-98` | 提示词只给数据快照 + 标题候选 + digest 标志，`editorialIntent / storyAngle` **一次都没进 prompt**（解说线已修，见 `NewsModelScriptAuthor.java:132-146`） |
| 标题的五类钩子被丢掉 | `DatasetTitleAuthor.java:39-59` | `parse()` 只留标题文本，`kind` 校验完即弃，写稿无法知道"这个标题属于哪类钩子" |
| 脚本字段集是硬闭集 | `DatasetScriptContract.java:16-18` | 7 个顶层字段 + 段落 3 个字段，多一个未知字段直接退回 |
| 旁白数字规则已就位 | `DatasetContract.java:125-140` | 占位符可解析、正文禁裸数字——story 直接复用这条规则 |
| 信号层已有机器证据 | `DatasetSignal.java:86-136` | `changePct / zscore / extreme / coverage / stale` 全部是纯函数算出来的，可作为"哪些是事实"的判据 |
| 机会表只存 headline | `HeronDataOpportunity.java:41` | 没有 `insight` / 叙事字段（发现层方案里的 `insight` 未落地），加列不如复用快照 |
| 快照 schema 版本是硬闸 | `FrozenContext.java:24` + `FrozenContextService.java:85` | `schemaVersion` 不等于 `"1.0"` 直接拒绝执行——**不能靠升这个版本号来承载新字段** |

第 3、4 条是本设计的直接动因：**决策链已经把"为什么做"写进了快照，但没有任何一环把它变成"讲什么故事"，也没有任何一环强制它被执行。**

---

## 2. 设计原则

| 原则 | 落地方式 |
|---|---|
| 故事先于稿件 | story 在 `reserve()` 之前产出并冻结，写稿只能消费它，不能改写它 |
| 数字只有一个来源 | story 文本里禁裸数字，一切数值走 `{{facts.<路径>}}`；写稿引用路径必须落在 story 声明的信封内 |
| 事实与假设必须分栏 | 每条主张带 `kind: fact / assumption`；`fact` 必须至少一条可解析的 `evidenceRefs`，`assumption` 必须一条都没有并单独列出 |
| 结构化胜过自然语言 | story 是 JSON 对象（枚举 + 定长文本 + 路径数组），不是一段自由文本的"创作方向" |
| 拒绝要有理由码 | 每个"不做"都给机器码 + 一段中文原因，能回答"哪条闸 + 哪段输入 + 为什么拒" |
| 复用冻结机制 | story 进入 `FrozenContext.hash` 覆盖的规范化快照，随版本一起被 SHA-256 钉住，不新增存储 |

---

## 3. 数据流与落点

```
EditorialDecisionService       总编辑定线 + editorialIntent
        │
        ▼
EditorialLineEditor.editDataBoard
        ├─ 选机会（有 focusOpportunityId 用锁定的，否则按分数取）
        ├─ DatasetStoryPlanner.plan(run, source, opportunity)   ← 新增，1 次模型调用
        │        · 有机器证据：机会的 signals / 规划取数的 DatasetSignal
        │        · 产出 story（数字全是占位符）
        ├─ DatasetStoryContract.violations(story, 规划快照)      ← 新增，纯函数硬校验
        │        不过 → 换下一条机会（最多 3 条）→ 仍不过 → 回到现有回退逻辑
        └─ EditorialSelection(line=DATA_BOARD, …, story)        ← EditorialSelection 新增可选组件
        │
        ▼
NewsProductionDomain.reserve        快照新增 "story" 节点（additive）
        │                            schemaVersion 仍是 "1.0"
        ▼
NewsProductionPipeline.execute
        ├─ datasets.fetch()         拿到**真实**生产快照（可能与规划快照不同）
        ├─ storyEvidenceGate        按真实快照重算 story 的每条 evidenceRef
        │                            不过 → news_story_evidence_mismatch（不静默降级）
        ├─ DatasetTitleAuthor       标题候选带 kind，按 story.narrative 收窄可用钩子
        ├─ DatasetScriptAuthor      提示词里带 story；脚本仍只有 7 个字段
        ├─ EditorialReviewer        依据里带 story，审「有没有背离结论 / 把假设说成事实」
        └─ validateChecks           story 闸只在快照有 story 时要求
```

**一个关键取舍**：故事必须在取数之前写完（因为冻结发生在 `reserve()`，而数据快照是生产期才抓的），所以 story 只能基于两种输入——

- 机会路径：`heron_data_opportunity.signals` + `snapshot_json`（已有，`DataOpportunityService.java:266-291`）；
- 选源/速览路径：`DatasetFetchService.snapshot(sourceKey, WINDOW_POINTS)` 做一次**规划取数**（与机会分析同一口径，不写 `last_updated`、不占 7 天去重窗口）。

规划快照的 `data_sha256` 写进 `story.basis.planningDataSha256`，生产快照的哈希写进脚本的 `dataset_sha256`，两者都留痕：**任何时候都能回答"策划时看的是哪份数据、生产时实际用的是哪份"**。

这正是「数字只能占位符」这条规则的第二个用途：它让"在取数前写故事"从不可能变成可能，且不会把策划时看到的数字带进成片。

---

## 4. 内容策划 JSON：字段设计

### 4.1 存放位置

| 位置 | 说明 |
|---|---|
| `heron_production_context.content_json` → `story` | 冻结输入的规范形式，随 `FrozenContext.hash` 被钉住 |
| `heron_video_version.input_snapshot` → `story` | 同一份字节，版本页可直接展示与回溯 |
| `heron_data_opportunity` | **不加列**。机会已有的 `signals` / `snapshot_json` 由 `story.basis` 引用 `opportunityId` 指回去 |

不新增表、不新增产物种类（`DATA_KINDS` 保持 7 类）、不新增脚本字段。旧版本快照没有 `story` 节点，读取方按"legacy 无故事"处理（见 §8）。

### 4.2 字段表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `schemaVersion` | string | 是 | 固定 `"story/1"`。**独立于**快照的 `schemaVersion`，两者不联动 |
| `narrative` | enum | 是 | 叙事类型：`counter_intuitive` 反常识 / `numeric_contrast` 数字反差 / `trend_reversal` 趋势反转 / `rank_competition` 排名竞争 / `time_change` 时间变化 / `problem_evidence_reveal` 问题—证据—揭示 |
| `oneLine` | string ≤ 40 字 | 是 | 一句话故事。必须是**只由事实构成**的一句话，不得含假设 |
| `protagonist` | object | 是 | 主角：`{kind: metric\|entity\|group, label ≤ 20 字, entityRef}`。`entityRef` 指向 `facts.leader` / `facts.series.0` / `facts.items.2` 一类的实体节点，`kind=metric` 时可为空串 |
| `tension` | object | 是 | 张力：`{kind: reversal\|gap\|rank_swap\|trend_break\|outlier\|question, statement ≤ 60 字, evidenceRefs[]}`。`evidenceRefs` 至少 1 条，全部为 `facts.*` 标量路径 |
| `claims` | array 3–6 | 是 | 主张列表，按叙事顺序排列，对应中段每一段旁白 |
| `claims[].id` | string | 是 | `c1`…`c6`，唯一；阶段闸用它定位问题 |
| `claims[].text` | string ≤ 60 字 | 是 | 主张陈述，数值一律占位符 |
| `claims[].kind` | enum | 是 | `fact` 机器证据 / `assumption` 模型推断 |
| `claims[].evidenceRefs` | string[] | 是 | `fact` 必填至少 1 条；`assumption` 必须为空数组 |
| `payoff` | object | 是 | 落点：`{text ≤ 60 字, evidenceRefs[]}`，至少 1 条路径。结尾口播讲的就是它 |
| `stakes` | object | 是 | 相关人：`{audience ≤ 40 字, impact ≤ 60 字}`，讲"这件事跟谁有关"，禁止点名具体投资收益 |
| `assumptions` | string[] ≤ 3 | 是 | 模型假设的显式清单，与 `claims[kind=assumption]` **一一对应、同文**。可以为空数组 |
| `basis` | object | 是 | 机器证据：`{signalType, changePct, zscore, extreme, coverage, points, periodStart, periodEnd, planningDataSha256, sourceKind, opportunityId}`；`sourceKind` ∈ `opportunity\|router\|digest` |

### 4.3 示例

```json
{
  "schemaVersion": "story/1",
  "narrative": "counter_intuitive",
  "oneLine": "金价创下窗口新高，可整段行情只涨了 {{facts.change_pct}} %",
  "protagonist": {
    "kind": "entity",
    "label": "伦敦金",
    "entityRef": "facts.last"
  },
  "tension": {
    "kind": "reversal",
    "statement": "价格在创新高，区间累计涨幅却接近持平",
    "evidenceRefs": ["facts.max.value", "facts.change_pct"]
  },
  "claims": [
    {
      "id": "c1",
      "text": "最新收盘 {{facts.last.value}} 美元，是这段窗口的最高点",
      "kind": "fact",
      "evidenceRefs": ["facts.last.value", "facts.max.value"]
    },
    {
      "id": "c2",
      "text": "但从窗口起点算起，累计只动 {{facts.change}} 美元、{{facts.change_pct}} %",
      "kind": "fact",
      "evidenceRefs": ["facts.change", "facts.change_pct"]
    },
    {
      "id": "c3",
      "text": "区间振幅 {{facts.max.value}} 到 {{facts.min.value}} 美元，重心一直在抬",
      "kind": "fact",
      "evidenceRefs": ["facts.max.value", "facts.min.value"]
    },
    {
      "id": "c4",
      "text": "涨得慢但没有回撤，通常意味着买盘在慢慢接",
      "kind": "assumption",
      "evidenceRefs": []
    }
  ],
  "payoff": {
    "text": "接下来真正的看点是能不能守住 {{facts.last.value}} 美元这个位置",
    "evidenceRefs": ["facts.last.value"]
  },
  "stakes": {
    "audience": "持有或准备配置黄金的人",
    "impact": "新高点的位置决定追高成本，涨幅小意味着容错空间也小"
  },
  "assumptions": ["涨得慢但没有回撤，通常意味着买盘在慢慢接"],
  "basis": {
    "signalType": "zscore",
    "changePct": 0.53,
    "zscore": 1.2,
    "extreme": true,
    "coverage": 1.0,
    "points": 30,
    "periodStart": "2026-10-01",
    "periodEnd": "2026-11-10",
    "planningDataSha256": "<64 hex>",
    "sourceKind": "opportunity",
    "opportunityId": 1761400000000000001
  }
}
```

注意 `c4`：它明确是 `assumption`、`evidenceRefs` 为空、原文进 `assumptions`。这就是"事实与假设可区分"的落点——不是靠措辞，是靠结构。

---

## 5. 每个字段的校验规则

分三层。**代码硬校验**不过就退回（`story_contract` 码 + 条目列表）；**提示词**只负责判断质量，不过不报错，只影响生成。

### 5.1 代码硬校验（`DatasetStoryContract.violations(story, snapshot)`）

| 字段 | 规则 |
|---|---|
| `schemaVersion` | 必须等于 `story/1` |
| `narrative` | 必须是 6 个枚举之一 |
| `oneLine` | 非空、≤ 40 码点、**无裸数字**、占位符全部可解析 |
| `protagonist.label` | 非空、≤ 20 码点、无裸数字 |
| `protagonist.entityRef` | 为空或能在快照里解析到对象节点；`kind≠metric` 时不得为空 |
| `tension.kind` | 必须是 6 个枚举之一，且**与 `narrative` 相容**（映射见 §6） |
| `tension.statement` | 非空、≤ 60 码点、无裸数字 |
| `tension.evidenceRefs` | 1–4 条；每条形如 `facts.*`，在快照里解析到标量 |
| `claims` | 3–6 条；`id` 唯一且形如 `c[1-6]`；顺序即叙事顺序 |
| `claims[].text` | 非空、≤ 60 码点、无裸数字、占位符可解析 |
| `claims[].kind` | ∈ `fact / assumption` |
| `claims[].evidenceRefs` | `fact`：1–4 条且全部可解析；`assumption`：必须为空数组 |
| `payoff.text` | 非空、≤ 60 码点、无裸数字 |
| `payoff.evidenceRefs` | 1–3 条且全部可解析 |
| `stakes.audience` / `impact` | 非空、≤ 40 / ≤ 60 码点、无裸数字；不得出现投资建议禁用词（复用 `DatasetTitleAuthor.BANNED`） |
| `assumptions` | ≤ 3 条；与 `claims[kind=assumption].text` **集合完全相等**（多一条少一条都拒） |
| `basis.sourceKind` | ∈ `opportunity / router / digest`；`opportunity` 时 `opportunityId` 非空 |
| `basis.planningDataSha256` | 64 位小写 hex |
| 统计约束 | `fact` 主张 ≥ 2 条（否则整条故事只有假设）；`evidenceRefs` 去重后总量 ≤ 8（**信封**：写稿只能用这 8 条路径里的数） |
| 方向一致 | `tension` / `claims` / `payoff` 里出现涨跌词时，必须与 `basis.changePct` 符号一致（复用 `DatasetTitleAuthor.valid` 的方向判定口径） |
| 时间口径 | 出现"较前一日 / 昨日 / 单日涨跌"等说法时，`shape` 必须是单期形态；`wide/long` 一律拒（与 `DatasetScriptContract.periodMeaning` 同一口径） |
| 绝对词 | 出现"第一 / 最高 / 最低 / 唯一 / 全部 / 全球"时，必须 `shape ∈ {rows, geo}` 且 `basis.coverage == 1.0`，否则拒 |

### 5.2 生产期复核（`storyEvidenceGate`，按真实快照重算）

| 检查 | 说明 |
|---|---|
| 引用仍可解析 | 每条 `evidenceRefs` 在**生产快照**里可解析；解析不到给出具体路径 |
| 方向未翻转 | `basis.changePct` 的符号与生产 `facts.change_pct` 符号一致；正负相反直接拒（策划时讲涨，生产时数据变跌，是假新闻） |
| 覆盖未塌陷 | 生产 `rows.size()` 不少于策划时点数的 50%，否则拒 |
| 极值未失效 | `basis.extreme=true` 时，生产快照的极值判定须仍为真 |
| 哈希留痕 | 记录 `planningDataSha256` 与生产 `data_sha256`，不一致本身不拒（跨日取数会变），但必须落进 checks 的 `measured` |

失败码固定 `news_story_evidence_mismatch`，消息格式：`story 第 N 条 evidenceRef facts.change_pct 在生产快照里解析不到`。

### 5.3 只进提示词（不报错，影响质量）

- 这个张力**是否真的反直觉**（而不是"涨了所以反常识"这类自我循环）；
- `oneLine` 是否在 20 字内就能听懂、有没有信息增量；
- 主张之间的推进顺序是否有认知增量（而不是把同一句换三种说法）；
- `stakes` 是否真的落在观众身上；
- 叙事类型是否是这个数据**最**适合的那一种（6 类都成立时选哪类的判断）。

### 5.4 复用而不是新写

| 能力 | 复用 |
|---|---|
| 裸数字 / 占位符可解析 | `DatasetContract.narrationViolations(snapshot, text)`，把 story 的文本字段拼起来喂进去 |
| 涨跌方向判定 | `DatasetTitleAuthor.valid()` 里的 up/down 词表 |
| 禁用词 | `DatasetTitleAuthor.BANNED` + 栏目包 `columns/*.md` 的禁语 |
| 数据新鲜度 | `DatasetSignal.stale(periodEnd, cadence, today)` |
| 信号门槛 | `DatasetSignal.Score` / `THRESHOLD` / `MAX_PLAUSIBLE_CHANGE` |

---

## 6. 六种叙事类型与它们的机器前提

叙事类型不是模型自由选的：**先看这份数据满足哪几条前提，再在满足的里选**。前提由 `DatasetSignal` 算，不由模型声称。

| `narrative` | 讲什么 | 机器前提（代码可判） | 可用 `tension.kind` | 适用 `shape` |
|---|---|---|---|---|
| `counter_intuitive` 反常识 | 结论与直觉相反，但数据支持 | `extreme=true` 或 `\|zscore\| ≥ 2`，且方向与 `changePct` 一致 | `reversal` / `outlier` | wide / long / rows |
| `numeric_contrast` 数字反差 | 两个数放在一起才有意思 | 至少两个 `facts` 标量，且相对差 ≥ 该类 `bigMove`；`rows/geo` 用 `facts.spread` 与首末差 | `gap` | 全部 |
| `trend_reversal` 趋势反转 | 最近的方向与更长的方向相反 | 近 `SHORT_WINDOW` 期方向 ≠ 基线方向，且两侧幅度都 ≥ `bigMove` | `reversal` / `trend_break` | wide / long |
| `rank_competition` 排名竞争 | 名次在动、头部贴得很近 | `rows` 有第三列 `change` 使排序活跃；或 `long` 两个实体末值差距 < 10% `spread` | `rank_swap` / `gap` | rows / long |
| `time_change` 时间变化 | 把跨度讲出来 | `period.start_year ≠ end_year` 或点数跨越 ≥ 一个 `cadence` 周期；`last ≠ first` | `trend_break` / `gap` | wide / long |
| `problem_evidence_reveal` 问题—证据—揭示 | 提一个问题，用 ≥2 条独立事实回答 | `claims` 里 ≥ 2 条 `fact` 且引用**不同**的 `facts` 分支 | `question` / `outlier` | 全部 |

`basis` 里没有满足任何一行的信号组合 → 直接拒（`story_signal_weak`），不强行套一个类型。

`geo` 没有时间轴、没有 `change`：只能走 `numeric_contrast` 或 `problem_evidence_reveal`，且 `time_change / trend_reversal` 一律拒。

---

## 7. 一句话判定：这条数据值不值得做成视频

> **把图表关掉，只用快照里能解析的事实，能不能在 20 字内说出「发生了什么 + 和什么预期相反（或和谁比才有意思）」，并且这句话至少被 2 条不同的 `facts` 路径支撑——说不出，就不做。**

这句话的操作化：写 `oneLine` 时如果只能用「XX 变了」，填不出第二半句，就是不该做。模型必须让这句话落地，`DatasetStoryContract` 只验"能不能解析"，文本能不能立住由 `oneLine` 长度上限（40 字）和审稿把关——**长度本身就是一道压力**：一句话说不清的故事，就是还没想清楚的故事。

### 7.1 拒绝条件（按顺序，命中即停）

| # | 条件 | 码 | 判据来源 |
|---|---|---|---|
| 1 | 数据不新鲜 | `story_data_stale` | `DatasetSignal.stale` 为真 |
| 2 | 点数太少 / 覆盖太低 | `story_data_thin` | `points < MIN_BASELINE_POINTS` 或 `coverage < 0.5` |
| 3 | 幅度不可信 | `story_implausible_change` | `\|change_pct\| > MAX_PLAUSIBLE_CHANGE`（2.0） |
| 4 | 许可不允许商用 | `story_license_blocked` | `license.commercial_ok == "false"` |
| 5 | 没有任何强信号 | `story_signal_weak` | 无 z-score、无新极值、变化 < 类 `bigMove`，三者同时成立 |
| 6 | 7 天内出过同源同方向 | `story_duplicate` | `heron_dataset_usage` 现有去重口径 |
| 7 | 只能靠假设才成立 | `story_all_assumption` | `fact` 主张 < 2 条 |
| 8 | 必须跨源比较才成立 | `story_needs_multi_source` | story 里出现两个不同 `source_key` 的事实 |
| 9 | 类别不参与选题 | `story_category_ineligible` | `DatasetSignal.CategoryPolicy.eligible=false`（天气等） |
| 10 | 说不出"谁受影响" | `story_no_stakes` | `stakes` 为空或只是"大家" |
| 11 | 硬校验不过 | `story_contract` | §5.1 任一规则 |

### 7.2 拒绝之后怎么办

**拒绝是候选级，不是槽位级。** 线内编辑按分数取前 3 条机会依次尝试，全部被拒才回到现有回退逻辑（`data_board_empty` → 解说线）。理由：一条数据讲不出故事，不代表今天数据线全废；但也不能因为"挑到第三条才成"就把前两条当成成功——每次拒绝都写进槽位的 `decision_reason`，可审计。

---

## 8. 标题与开场口播的配合规则

### 8.1 标题必须携带故事的那一半

现状 `DatasetTitleAuthor` 出五类钩子（数字反差 / 疑问悬念 / 利益相关 / 时间紧迫 / 反常识），但 `kind` 被丢弃。改法：候选内部保留 `kind`（`List<TitleCandidate(title, kind)>`，纯 Java 内部结构，不进脚本），并加两条硬规则：

1. **选中标题的 `kind` 必须在 `narrative` 允许的钩子集合内**：

| `narrative` | 允许的标题钩子 |
|---|---|
| `counter_intuitive` | 反常识、疑问悬念 |
| `numeric_contrast` | 数字反差、利益相关 |
| `trend_reversal` | 疑问悬念、反常识 |
| `rank_competition` | 数字反差、利益相关 |
| `time_change` | 时间紧迫、数字反差 |
| `problem_evidence_reveal` | 疑问悬念、数字反差 |

2. **标题的数字与 story 同一信封**：标题里的 `{{facts.*}}` 路径必须落在 `story` 声明的 `evidenceRefs` 并集内（现在只校验占位符可解析，不校验来源）。

### 8.2 开场的三条规则

开场 = 第 1 段的 `lead + body`（`DatasetScriptContract.scenes` 的第一个元素）。

1. **开场讲张力，不讲图表**：内容必须复述 `tension.statement`，不得复述标题（标题重复已由 `DatasetScriptContract.scenes` 硬拦）。
2. **开场引用的数据只能来自 `tension.evidenceRefs`**：第 1 段出现的 `{{facts.*}}` 路径集合 ⊆ `tension.evidenceRefs`。这是硬校验——它挡住"开场顺手念了另一个数"。
3. **开场陈述只能是 `fact`**：第 1 段如果对应 `claims` 里某条 `assumption`，拒。假设只能出现在中段，且必须带不确定表述。

### 8.3 禁止项

| 禁止 | 定义 | 拦法 |
|---|---|---|
| **虚假冲突** | 制造一个数据里不存在的对立面（"专家说涨，数据说跌"——数据里没有专家） | `tension` 只能引用 `facts` 路径（§5.1）；出现"专家 / 机构 / 分析人士"且无出处 → `DatasetStoryContract` 拒 |
| **虚假紧迫感** | 标题用"时间紧迫"钩子，但数据是月频/年频，或 `periodEnd` 早已过期 | `time_change` 钩子要求 `cadence ∈ {realtime,daily,weekly}` 且通过 `stale` 判定 |
| **夸大结论** | "第一 / 最高 / 全球 / 全部"出现在覆盖不全的数据上 | 绝对词表 + `coverage==1.0` + `shape ∈ {rows,geo}` 三重条件 |
| **因果断言** | `fact` 主张里出现"因为 / 导致 / 由于 / 说明" | 这四类词在 `fact` 文本里一律拒；模型要讲因果，必须降级为 `assumption` |
| **投资建议 / 预测** | 必涨、抄底、目标价、未来点位 | 复用 `DatasetTitleAuthor.BANNED`，story 与稿件共用同一份词表 |
| **复述画面** | "如图所示 / 请看图表 / 这个数字" | 只在提示词里禁（`script-contract.md` 已有同类条款） |

### 8.4 三层一致性

```
标题（≤15 字，钩子 kind 受 narrative 约束）
  └ 开场（第 1 段，复述 tension，只引 tension.evidenceRefs）
      └ 中段（claims 顺序展开，每条对应一段）
          └ 结尾（closing 讲 payoff）
```

任何一层脱离了 story，都属于审稿的 MAJOR（"文案背离策划结论"），改稿阶段回来，不重跑策划。

---

## 9. 与 `DatasetScriptContract` 的衔接（不新增字段）

脚本 JSON 保持：

```json
{"dataset_sha256":"…","source_key":"…","title":"…","scenes":[{"lead":"…","body":"…","duration_s":12}],"closing":"…","source_name":"…","chart":"line"}
```

story 通过**三条不进 JSON 的通道**影响稿件：

### 9.1 通道一：提示词（唯一的内容入口）

`DatasetScriptAuthor.prompt()` 在现有"数据快照 + 标题候选"之后追加一节：

```
已定故事（不可改写，只负责把它讲成 3–6 段旁白）：
  一句话：<oneLine>
  张力：<tension.statement>（依据 <tension.evidenceRefs>）
  主张：
    c1 [事实] <text>（依据 …）
    c4 [假设] <text>（正文必须写成推测口吻，如"可能""看起来"）
  落点：<payoff.text>
  可用数字只有这些路径：<evidenceRefs 并集>
段落顺序：第 1 段复述张力，中间每段讲一条主张，结尾讲落点。
```

`repair` 复用同一份提示词，只在问题清单里加"偏离故事"的条目。

### 9.2 通道二：位置映射（唯一的硬结构）

脚本的段落结构不动，`scenes[i]` 与 story 的映射靠**位置约定 + 硬校验**：

| 脚本位置 | 对应 story | 硬校验 |
|---|---|---|
| `scenes[0]` | `tension` | 引用的 facts 路径 ⊆ `tension.evidenceRefs` |
| `scenes[1..n-2]` | `claims[0..]` 顺序 | 段落数 ≥ `claims` 中 `fact` 条数（假设可以合并进事实段，事实不能被吞掉） |
| `closing` | `payoff` | 至少引用 1 条 `payoff.evidenceRefs` |
| 全文 | 信封 | 全文 `{{facts.*}}` 路径集合 ⊆ `evidenceRefs` 并集 ∪ 选中标题用到的路径 |

这四条都只用已有的 `scenes[].lead/body` 与 `closing` 计算，**不需要任何新字段**。实现上放进 `DatasetScriptContract` 的一个新方法（形状仍归这里判），或独立的 `StoryScriptAlignment`——建议后者，避免脚本契约本身变复杂。

### 9.3 通道三：审稿依据

`EditorialReviewer.prompt()` 现在只给 `facts + script`。改为多给一段 story，并在提示词里加三类问题：

- 文案是否**背离**了 `tension` / `payoff`（讲着讲着换了论点）；
- `assumption` 是否被写成了断言（"涨得慢说明买盘在接"直接陈述）；
- 标题承诺的钩子是否在开场就兑现（对应 `editorial-review.md` 已有的"标题党"条目）。
- 额外给审稿一个 `segmentId` 映射：`scene1 = 张力`、`sceneN = c(N-1)`、`closing = 落点`，便于定位。

### 9.4 为什么不是加一个 `story` 字段

- `DatasetScriptContract.FIELDS` 是闭集，加字段要同步改闸、模板变量、历史脚本兼容、`DatasetPlaceholders`、`DatasetVisualFocus` 五处，收益是"把一段提示词存进脚本"——不值。
- 策划结论是**输入**，不是**产物**。产物要可变、可重渲；输入要不可变、可审计。混在一起就再也没有"这条片子是按哪个策划做的"的干净答案。
- 历史脚本没有 story 也能从 `input_snapshot` 找回当时的选择，回放不受影响。

### 9.5 闸清单的增量

`NewsProductionDomain.validateChecks(checks, dataBoard)` 的必填闸是常量集合 `DATA_GATES`。story 闸不能直接塞进去——那样所有历史快照重跑（没有 story 的）都会因缺闸失败。改法：`validateChecks` 增加一个"是否要求 story 闸"的入参，由冻结输入里有没有 `story` 节点决定。要求时新增一个 gate `story`，`measured` 放 `{refsResolved: true, refsTotal: 7, directionStable: true}`。

---

## 10. schemaVersion 演进与历史兼容

| 面 | 决定 |
|---|---|
| 冻结快照 `schemaVersion` | **保持 `"1.0"`**。`FrozenContextService.verify` 对任何非 `1.0` 直接拒绝执行（`:85-88`），升版本等于让全部历史任务无法重跑 |
| story 自身版本 | `story.schemaVersion = "story/1"`，独立演进。读方不认识就当作无 story |
| 新增节点的写法 | 沿用 `selection.opportunityId` 先例（`NewsProductionDomain.java:86-89`）：**有值才写，没有就不写**，保证不相关的历史快照字节不变 |
| 无 story 的旧快照 | 生产照旧：不跑 story 闸、不要求 story gate、写稿提示词不加那一段。**消费端宽容，生产端强硬** |
| 新链路缺 story | 线内编辑不返回选择（回退另一条线），不进入生产。**不允许"没故事也硬写"** |
| 未来加字段 | 在 `story/2` 里加，旧 `story/1` 继续可读；`basis` 是唯一允许自由扩展的容器（审计字段，不参与故事语义） |

这条分界是本次设计里最容易做错的地方：**把"新版本必须更强"和"老数据必须还能跑"拆成两个开关**，而不是靠一个版本号同时表达。

---

## 11. 改动清单（后续实施用，本次不动代码）

### 新增

| 文件 | 用途 |
|---|---|
| `domain/EditorialStory.java` | story 的值对象（typed record），含 `schemaVersion` 常量 |
| `service/impl/DatasetStoryPlanner.java` | 规划调用（stage `dataset-story`），模型只写文本与路径，数字不落笔 |
| `service/impl/DatasetStoryContract.java` | §5.1 的纯函数硬校验 + §5.2 的复核，可在单测里直接喂 JSON |
| `service/impl/StoryScriptAlignment.java` | §9.2 的四条对位校验 |
| `src/main/resources/prompt/data-board/story-contract.md` | 6 类叙事、字段契约、拒绝标准、禁语 |
| 测试 | `DatasetStoryContractTest`、`DatasetStoryPlannerTest`、`StoryScriptAlignmentTest`、`StoryDecisionTest`（拒绝码路径） |

### 修改

| 文件 | 改动 |
|---|---|
| `EditorialSelection.java` | 增加可选组件 `story`；`DATA_BOARD` 且 story 为空时由**调用方**（线内编辑）拦截，构造器只校验"有 story 时其内部一致"——保证手工/历史路径不被误伤 |
| `EditorialLineEditor.java:116-159` | `editDataBoard` 依次尝试前 3 条机会 → 规划 → 校验 → 命中即返回；全拒则回到现有 `Optional.empty()` 回退 |
| `NewsProductionDomain.java:78-91` | `selectionNode` 之外新增 `storyNode`，有 story 才写 |
| `NewsProductionDomain.java:314-338` | `validateChecks` 增加"是否要求 story 闸"入参 |
| `DatasetScriptAuthor.java:32-98` | `author/repair` 增加 story 入参；`prompt()` 追加故事节；`violations()` 增加 `StoryScriptAlignment` 条目 |
| `DatasetTitleAuthor.java:39-59` | 候选保留 `kind`；按 `narrative` 收窄可用钩子；标题占位符路径落在信封内 |
| `NewsProductionPipeline.java:131-138` | fetch 之后、写稿之前加 `storyEvidenceGate`；审稿传入 story 与段落映射 |
| `EditorialReviewer.java:181-191` | `prompt()` 增加 story 节与三点问题 |
| `prompt/data-board/script-contract.md` | 增一节"故事对位规则"（不新增字段，只讲约束） |
| `prompt/data-board/title-contract.md` | 增一节"钩子与叙事的相容表" |

### 明确不改

- `DatasetScriptContract.FIELDS` / `SCENE_FIELDS`（7 + 3 个字段，闭集不动）；
- 模板、`template.json`、`LOCK.json`、渲染链、`DATA_KINDS`（仍 7 类产物）；
- `heron_data_opportunity` 表结构（不加 `insight`/`narrative` 列）；
- `FrozenContext.SCHEMA`（仍 `1.0`）。

---

## 12. 测试计划

| # | 用例 | 期望 |
|---|---|---|
| 1 | story 文本含裸数字 | `story_contract` 拒，指出字段 |
| 2 | `fact` 主张 `evidenceRefs` 为空 | 拒 |
| 3 | `assumption` 主张带 `evidenceRefs` | 拒 |
| 4 | `assumptions` 与 `claims[assumption]` 不一致 | 拒 |
| 5 | `narrative` 与 `tension.kind` 不相容 | 拒 |
| 6 | `fact` 里写"因为…" | 拒（因果断言） |
| 7 | 全篇只有 1 条事实主张 | `story_all_assumption` |
| 8 | 生产快照 `change_pct` 由正变负 | `news_story_evidence_mismatch`，消息带路径 |
| 9 | 生产快照少了 60% 的点 | `news_story_evidence_mismatch`（覆盖塌陷） |
| 10 | 前两条机会被拒、第三条成立 | 选中第三条，槽位 reason 记 2 次拒绝 |
| 11 | 无 story 的历史快照重跑 | 不要求 story 闸，脚本契约照常通过 |
| 12 | 有 story 的快照漏报 story 闸 | `checks_incomplete` |
| 13 | 第 1 段引用了 `tension` 之外的路径 | 退回写稿，条目指出越界路径 |
| 14 | 结尾不引用 `payoff` 路径 | 退回写稿 |
| 15 | 标题 `kind` 不在 narrative 允许集 | 标题候选整体重出（或该候选被排除，有效候选 <3 则 `dataset_title_contract`） |
| 16 | 同一 opportunityId 重放两次 | story 字节一致（冻结 hash 可比），生产结果一致 |

---

## 13. 风险与取舍

| 风险 | 说明 | 处置 |
|---|---|---|
| 信封过窄导致写稿反复被退 | 写稿只能用 story 声明的 8 条路径，可能压住表达 | 规划侧鼓励声明 4–8 条（含 `min/max`、`change`、`change_pct`、`leader` 等常用项）；修复提示词先扩信封而不是重写故事 |
| 多一次模型调用 + 一次规划取数 | 每槽位 +1 次调用（最多 3 次）+ 1 次 HTTP | 只对数据看板线生效；机会路径复用已存快照，省掉取数；上限 3 条机会硬封顶 |
| 规划快照与生产快照不同 | 跨日、跨小时数据会变 | 只校验方向/极值/覆盖这三个粗粒度不变量，不做逐点相等；两个哈希都留痕 |
| 叙事类型被套模板化 | 模型倾向于选最容易满足前提的 `numeric_contrast` | 前提表按"信息量"排序（`trend_reversal` > `counter_intuitive` > 其余），提示词要求优先选更高信息量且成立的那类；评价回写后再调权重 |
| 故事写得好、片子不好看 | 本设计只解决"讲什么"，不解决"怎么讲好看" | 视觉侧见 `2026-11-17-video-visual-upgrade-design.md`；本文不碰渲染 |

---

## 14. 明确不做

- 不做多源合并叙事（dataset/1 一份快照只来自一个 `source_key`，跨源比较一律拒）。
- 不做故事的历史表现回填（"这类故事上次完播率多少"）——需要播放数据回流，另立项。
- 不做人工编辑故事（先只做"自动生成 + 自动拒绝"；人工干预留在淘汰机会这一层）。
- 不新增数据库表、产物类型、脚本字段、模板变量。
- 不改 `FrozenContext.SCHEMA`。

---

## 15. 落地状态（已实现）

本设计已落地为代码，实施细节与验证结果见 **[docs/reports/heron-data-board-story-plan-impl.md](../reports/heron-data-board-story-plan-impl.md)**。

- 新增 `domain/EditorialStory.java`、`service/impl/{DatasetStoryContract,DatasetStoryPlanner,StoryScriptAlignment}.java`、`resources/prompt/data-board/story-contract.md`。
- 接入 `EditorialSelection`（story 组件）、`EditorialChoice`（storyRejects）、`EditorialLineEditor`（看板线最多试 3 条机会、全拒回退）、`NewsProductionDomain`（快照 story 节点 + `story` 闸）、`NewsProductionPipeline`（生产期 `storyGate`）、`DatasetTitleAuthor`（钩子按叙事收窄）、`DatasetScriptAuthor`（提示词加故事节 + 对位复核）、`EditorialReviewer`（审稿依据加故事）、`NewsAutoService`（拒绝码进槽位原因与决策轨迹）。
- 11 条拒绝码、6 类叙事、6 类张力相容、6 类标题钩子相容表全部实现；`DatasetSignal` 拆出 `raw(...)` 以便给出「过期 / 幅度不可信」的具体拒绝码。
- 验证：本次涉及的 9 个测试类 63 个测试全通过（新增 39 条）；heron 模块全量 847 个测试与 HEAD 基线的 184 个环境性失败逐条一致，**无回归**。
- 尚未做的：生产链路真跑（需模型密钥与真实数据源）、故事的表现回填、人工编辑故事。
- 风格映射表已另案落地：叙事类型登记在模板 `style.narratives` 上，`StoryStyleMap` 校验六类叙事各有承接且不重复，`NewsTemplatePicker` 按叙事收窄到一套画面（数据看板线不再让模型在几套画面间现挑）。
