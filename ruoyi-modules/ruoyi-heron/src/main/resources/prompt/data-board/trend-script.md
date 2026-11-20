# 趋势洞察稿附加契约（数据看板线的趋势变体）

数据看板稿讲「发生了什么」，趋势稿讲「变化意味着什么」。你仍按 `script-contract.md` 的输出字段与旁白规则写稿，
但结论必须由机器判定驱动，不能自己给方向。

## 你拿到的两份输入

1. `dataset/1`：单次抓取的时间序列快照（字段同数据看板稿）。
2. `trend/1`：机器按固定阈值算出的判定，键说明：

| 键 | 含义 |
| --- | --- |
| `verdict` | `sustained_up` 持续上行 / `sustained_down` 持续下行 / `range_fluctuation` 区间波动 / `insufficient` 样本不足 |
| `label` | 上面四类的中文说法，正文直接用它 |
| `period` | `{from, to}` 区间首末日期 |
| `first` / `last` | 区间首末点 `{date, value}` |
| `net_change_pct` | 区间首末净变化百分比 |
| `median_shift_pct` | 分段中位数相对首段的迁移幅度 |
| `sign_agreement` | 分段方向一致度（0–1，越大越一致；不要念小数） |
| `volatility_pct` | 波动幅度（相对中位数） |
| `segments` | 分三段，各段 `{index, from, to, points, median, direction}` |
| `plan.question` | 开场要提的问题 |
| `plan.beats` | 六段各讲什么 |
| `plan.must_not` | 本次必须避免的说法 |
| `speculation_boundary` | 本期证据能支撑到哪里 |
| `corroboration` | `single_source` 表示只有一个来源，**不得**说「多方数据印证」 |

## 判定口径（机器说了算，不许改写）

`verdict` 是唯一对外结论。写法可换词，方向不能换：

- `sustained_up`：只能说上行；禁止出现「下跌 / 回落 / 走低 / 降温 / 收窄」一类反向词。
- `sustained_down`：只能说下行；禁止出现「上涨 / 走高 / 攀升 / 升温 / 扩大」一类反向词。
- `range_fluctuation`：只能说「在区间内来回」；禁止出现任何方向词（趋势、上涨、下跌、走高、走低、攀升、回落、反弹）。
- `insufficient`：只能说「样本不够，看不出方向」；同样禁止任何方向词。

不确定就照抄 `label`，不要自己造判定名。

## 六段叙事（scenes 5 屏 + 结尾 = 6 段）

1. 开头：用 `plan.question` 提问题或抛反常识，不许讲「我们来分析一下」。
2. 现象：`{{facts.first.value}}` 到 `{{facts.last.value}}`，口径写清是区间首末而不是单日。
3. 证据：引用 `segments` 里至少一段的中位数与方向，说明它支持哪个判定。
4. 解释：把「证据支持的部分」和「只能是推测的部分」分开说，边界照 `speculation_boundary`。
5. 洞察：回答 `plan.question`，落在变化意味着什么。
6. 结尾：总结 + 说明还缺什么证据、还需要观察什么。

每段还要写分镜三件套（`scene_type` / `beat` / `on_screen_text`，口径见 `script-contract.md`）。
`beat` 直接照 `plan.beats` 的顺序落：问题→`HOOK`，现象→`CONTEXT`，证据→`EVIDENCE`，
解释→`EXPLANATION`，洞察→`INSIGHT`，待观察→`CONCLUSION`。
`scene_type` 按每段实际在讲什么选：问题屏用 `headline`，现象与证据屏用 `chart`，解释屏用 `text`，洞察屏用 `number` 或 `chart`。

## 红线（写错直接退稿）

1. 不得把短期波动说成长期趋势：`range_fluctuation` 里出现方向词一律退稿。
2. 不得把推测写成确定事实：全文禁止 预计 / 将会 / 有望 / 必然 / 必将会 / 一定会 / 肯定会 / 毫无疑问 / 必将 / 大概率 / 不出意外 / 板上钉钉。
3. 关键观点必须可追溯：至少两屏正文必须用占位符引用实测值。
4. 正文禁止任何阿拉伯数字（沿用数据看板稿规则），数字一律走占位符。
5. `corroboration` 是 `single_source` 时不得暗示多来源印证，`limits` 里写明的限制要在结尾体现。

## 占位符（解析不到直接退稿）

- 快照事实：`{{facts.first.date}}` `{{facts.first.value}}` `{{facts.last.date}}` `{{facts.last.value}}` `{{facts.change}}` `{{facts.change_pct}}` `{{facts.period.start_year}}` `{{facts.period.end_year}}`
- 趋势判定：`{{trend.label}}` `{{trend.net_change_pct}}` `{{trend.median_shift_pct}}` `{{trend.volatility_pct}}` `{{trend.period.from}}` `{{trend.period.to}}` `{{trend.speculation_boundary}}` `{{trend.segments[0].from}}` `{{trend.segments[0].to}}` `{{trend.segments[0].median}}`
- `sign_agreement` 不要念小数：要用就写「多数分段同向」，不要引用它的数值。

## 输出

字段与 `script-contract.md` 完全一致：`title`、`scenes[]`（每段 `{lead, body, duration_s, scene_type, beat, on_screen_text}`）、`closing`。
系统会补 `dataset_sha256` / `source_key` / `chart` / `source_name`。合计 30–75 秒，3–6 屏。
