# 结构化研究报告与结果绑定

**日期：** 2026-11-16
**Phase：** 3（第四步，Phase 3 收尾）

---

## 一、报告不是把结论再抄一遍

`result_text` 是给人和下游看的一段自然语言。plan.md §7 的要求是「Agent 不应该只返回一段自然语言」——
下游（内容决策中心、生产接入）需要能**稳定取出** decision、要点和风险，而不是从散文里正则匹配。

所以 `report_json` 存的是结构，不是文本：

```json
{
  "version": "1.0",
  "submitted": true,
  "decision": "REJECT",
  "reason": "……指向具体数字",
  "angle": null,
  "confidence": "0.82",
  "keyInsights": [ "……每条一句，带数值" ],
  "riskWarnings": [ "只有 8 个点，任何涨跌幅判断都缺乏参照" ],
  "cited": [ "frankfurter-dkkcny" ],
  "snapshot": { "bound": true, "dataSha256": "…", "retrievedAt": "…", "sourcePoints": 30 },
  "evidenceAvailable": true,
  "meta": { "model": "…", "promptVersion": "…", "agentVersion": "…", "attemptNo": 1 }
}
```

两段文本都保留：报告给机器，`result_text` 给人。不能只有一边。

### `keyInsights` 与 `riskWarnings` 是让模型交的，不是系统生成的

理由很直接：系统从结论文本里抽要点，抽出来的还是文本。让模型在收尾时直接给结构化的两条列表，
代价是几十个 token，换来的是下游不用解析。

`risks` 尤其值得单独要——**有风险和没风险，模型本来就知道**，写进自然语言里容易被忽略，
单列成字段之后，「风险一条都没有」本身就成了一个可检查的信号。

---

## 二、结果绑定：绑快照标识，不是绑时间

plan.md §8 的原话：「对于数据变化较快的场景，不能只保存查询时间而不保存数据版本或快照标识」。

为什么较真：实测过同一条源在几十分钟内从 65 个点变成 0 个点。**只留时间戳等于说「那天取过」**，
证明不了「取的是哪一份」，这条结论此后再也无人能复核。

所以 `snapshot` 块里两个都写：

| 字段 | 作用 |
|---|---|
| `dataSha256` | 这一份数据的唯一标识，能证明「取的就是这一份」 |
| `retrievedAt` | 什么时候取的 |
| `sourcePoints` / `truncated` | 样本规模、是否截断——截断过的快照不能当完整证据用 |

绑不上时 `bound=false`，不假装绑定过。三种情况会这样：机会没有落库快照、
快照缺 `data_sha256`、或者 `opportunity_id` 为空（库里这列可空）。

### 为什么不是复用 `FrozenContext`

现有 `FrozenContext` 的键是 `workId / versionId / taskId`——研究阶段这三样都还不存在，
它属于**生产**阶段的冻结输入。硬接只会造出一堆 0。

研究要绑的是「它依据的那份原始数据快照」，而这份快照本来就落在
`heron_data_opportunity.snapshot_json` 里，带着 `data_sha256`。绑它是同一件事，且不用改生产链路。

> 阶段差异写在这里：等 Phase 6 研究结论进入生产时，`FrozenContext` 会冻结「这条结论」本身，
> 和这里的「冻结它依据的数据」是两层，到时候可以引用本报告的 `dataSha256` 作为来源。

---

## 三、顺带修掉的一个真问题

`opportunity_id` 在库里可空，代码里直接拆箱成 `long` 传给端口——空值会 NPE，整次研究被判 `failed`。
这是测试跑出来的：原有 7 个服务层用例同时变红。

现在空值传 0，报告里 `bound=false`，研究照跑。

---

## 四、验证

### 真跑

```
decision=REJECT  confidence=0.82
insights=7 条   risks=4 条
report.snapshot.bound=true  dataSha256=deadbeefcafe
```

模型给的风险里有一条是真发现：**「多个源 key 返回同一份数据」**——它用 `compare_series`
对 `audusd` / `eurcny` 取数，发现返回的点与 `AUD/CNY` 逐点相同、unit 都写着 CNY，
据此判定交叉验证失效。这条结论只靠自然语言很难要求它写出来，单列成 `risks` 之后它写了。

### 单测（新增 7 项，总 350 项全绿）

| 用例 | 断言 |
|---|---|
| 结构化 | decision/reason/angle/confidence/insights/risks/cited 各就各位 |
| 快照绑定 | `bound=true`，sha256 与 retrievedAt 同时存在 |
| 无快照 | `bound=false`，不假装 |
| 快照缺 sha256 | 同样 `bound=false`——没版本标识就复核不了 |
| 没交结论 | `decision` 是 null，不编默认值 |
| 元信息 | 模型名、提示词摘要、AgentScope 版本、尝试次数 |
| 证据嵌入 | 有则挂上，无则 `evidenceAvailable=false` |

---

## 五、Phase 3 到此为止

plan.md §16 的 Phase 3 验收标准逐条对照：

| 标准 | 状态 |
|---|---|
| 能从研究目标自主发起调查 | ✅ |
| 能按中间结果动态选择查询工具 | ✅ 真跑里 8 个工具全用过 |
| 能发现数据异常 | ✅ 发现「多源返回同一份数据」「周末也有报价」 |
| 能形成证据链 | ✅ 引用对质 + 未核实标记 |
| 能在证据不足时停下并说明原因 | ✅ 主动收尾 + risks 字段 |

**未做但不在本阶段验收内的**：把报告接到内容决策中心（Phase 5）、把结论冻结进
`FrozenContext`（Phase 6）。

### 已知的粗糙处

- 证据引用只做子串匹配，模型写 `AUD/CNY` 而账本里是 `frankfurter-audcny` 会误报未核实。
- 不校验 `reason` 里的数字是否出自被引用的那次调用。
- `report_json` 是 `json` 列但代码按字符串读写；MySQL 会重排键序，若要对其取 hash 得走
  `FrozenContext.canonicalJson` 那条路径。
