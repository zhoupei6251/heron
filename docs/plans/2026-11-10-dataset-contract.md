# Heron dataset 契约 v1

**日期**：2026-11-10
**适用**：新闻线「数据看板」模板族
**对应**：注册表 `script/sql/heron/008-data-source.sql`；产物类型 `dataset`；形态列对齐 Plotbeat `requiredColumns`

---

## 0. 一句话

**模型不写数字。** 快照由适配器产出、哈希固定、`data` 闸逐项核对；模型只写带占位符的旁白，占位符只能引用快照里的 `facts`。

---

## 1. 快照结构

一条视频一份，落 `data/videos/<work-id>/v<version>/dataset.json`。

```json
{
  "contract": "dataset/1",
  "source_key": "sina-gold-xau",
  "source_name": "新浪财经",
  "source_url": "https://hq.sinajs.cn/list=hf_XAU",
  "retrieved_at": "2026-11-10T03:22:00Z",
  "http_status": 200,
  "adapter_version": "1",
  "license": {
    "name": "unknown",
    "commercial_ok": "unknown",
    "terms_url": null,
    "attribution": "来源：新浪财经"
  },
  "metric": {
    "key": "close",
    "label": "伦敦金收盘价",
    "unit": "USD/oz",
    "precision": 2
  },
  "cadence": "daily",
  "shape": "wide",
  "columns": ["date", "value"],
  "rows": [
    ["2026-11-05", 4091.55],
    ["2026-11-06", 4105.50],
    ["2026-11-09", 4110.04],
    ["2026-11-10", 4113.34]
  ],
  "time_range": {
    "start": "2026-11-05",
    "end": "2026-11-10",
    "points": 4
  },
  "facts": {
    "first": {"date": "2026-11-05", "value": 4091.55},
    "last": {"date": "2026-11-10", "value": 4113.34},
    "min": {"date": "2026-11-05", "value": 4091.55},
    "max": {"date": "2026-11-10", "value": 4113.34},
    "change": 21.79,
    "change_pct": 0.53,
    "period": {"start_year": 2026, "end_year": 2026}
  },
  "data_sha256": "<64 hex>"
}
```

---

## 2. 字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `contract` | 是 | 固定 `dataset/1`，不兼容变更时进位 |
| `source_key` | 是 | 必须存在于注册表，且 `enabled='0'`（0 启用，与 `heron_news_source` 一致） |
| `source_name` | 是 | 画面「来源：xxx」用，不得为空 |
| `source_url` | 是 | 实际请求 URL；**不得含 key/token 查询参数**，适配器负责剥离 |
| `retrieved_at` | 是 | UTC ISO-8601，秒级 |
| `http_status` | 是 | 非 200 一律不产出快照 |
| `adapter_version` | 是 | 适配器版本，改解析逻辑必须进位 |
| `license` | 是 | `commercial_ok` 取注册表值：`true/false/unknown` |
| `metric` | 是 | `key/label/unit/precision`，`unit` 不得为空 |
| `cadence` | 是 | `realtime/daily/monthly/annual/mixed` |
| `shape` | 是 | `wide/long/rows/geo` |
| `columns` | 是 | 与 `shape` 对应的固定列，顺序即 `rows` 顺序 |
| `rows` | 是 | 数组的数组，禁止对象，禁止 `null` |
| `time_range` | 时间形态必填 | 与行的首尾日期一致；`points` 等于行数 |
| `facts` | 是 | 唯一可供旁白引用的数值层，见 §4 |
| `data_sha256` | 是 | 规范数据哈希，见 §7 |

---

## 3. 四种 shape

| shape | `columns` | 用途 | Plotbeat 模板 |
|---|---|---|---|
| `wide` | `date,value` | 单指标时间序列 | single-line-story |
| `long` | `date,series,value` | 多实体时间序列 | bar-race / multi-line-rank / stacked-area |
| `rows` | `label,value,change?` | 单期排行或对比 | kpi-dashboard / 自建排行 |
| `geo` | `location,latitude,longitude,value` | 地理分布 | animated-map |

- `rows` 的 `change` 可缺省；缺省时不得出现在 `columns` 里。
- `geo` 的经纬度必须是数值，不允许字符串。
- `long` 的 `series` 去重后数量决定图表条目数，超过 12 条由适配器截断，不交给模板。

---

## 4. facts

适配器从 `rows` 确定性计算，**不允许模型或模板自行推导**。

| shape | facts 结构 |
|---|---|
| `wide` | `first/last/min/max`（各含 `date,value`）、`change`、`change_pct`、`period.start_year/end_year` |
| `long` | `series[]`（每项 `name,first,last,min,max,change,change_pct`）、`leader.name/leader.value`、`period` |
| `rows` | `items[]`（`label,value,change?`）、`leader.label/leader.value`、`count`、`spread` |
| `geo` | `items[]`（`location,value`）、`leader.location/leader.value`、`count` |

精度规则：

- `value` 类字段按 `metric.precision` 四舍五入；
- `change_pct` 固定两位，单位是百分数（`0.53` 表示 0.53%）；
- `spread = max(value) - min(value)`，同样按 `precision`；
- 分母为 0 时 `change_pct` 取 `null`，旁白不得引用。

口径补充（实现与闸共用 `DatasetFacts`，以下为唯一解释）：

- `wide` 的 `first/last/min/max` 是 `{date,value}` 对象；`long` 的 `first/last/min/max` 是数值。
- `leader` 一律取**最大值**：`long` 比 `last`，`rows`/`geo` 比 `value`；并列取先出现者。
- `min/max` 按数值比较，并列取先出现者。
- `long` 的 `series` 按首次出现顺序输出，不重新排序。
- 时间形态至少两行；`rows`/`geo` 至少一行。

---

## 5. 占位符

写稿模型只输出带占位符的旁白：

```
金价收于 {{facts.last.value}} 美元，较前一日 {{facts.change}} 美元。
```

规则：

1. 语法只有 `{{facts.<path>}}`，不接受算术、函数、默认值。
2. 路径必须能在 `facts` 中解析到标量（数字或字符串）。
3. **旁白正文禁止出现阿拉伯数字**（`[0-9]`）。年份、计数、百分比一律走 `facts`。
4. 占位符解析失败 = `data` 闸失败，退回写稿，不自动改稿。
5. 占位符不得引用 `rows`、`columns`、`license` 等非 `facts` 路径。

> 这条规则把「模型编数字」变成**结构上不可能**，而不是靠事后核对。

---

## 6. `data` 闸检查项

按顺序，任一失败即拒绝入库：

1. `contract` 等于 `dataset/1`。
2. 必填字段齐全、类型正确、无未知顶层字段。
3. `source_key` 已登记且 `enabled='0'`（0 启用）。
4. `source_url` 不含 `key|token|secret` 查询参数。
5. `rows` 每行长度等于 `columns` 长度，数值列均为有限数。
6. `columns` 与 `shape` 规定的列一致（`rows` 的可选 `change` 除外）。
7. 时间形态下 `time_range` 与行首尾一致，日期递增且无重复。
8. `facts` 由闸按 §4 重算，与快照值在 `precision` 内相等。
9. 旁白中每个占位符都解析成功；正文无裸数字。
10. `data_sha256` 与按 §7 重算的值一致。

---

## 7. 哈希与复现

- `data_sha256` 覆盖：`shape`、`columns`、`rows`、`metric.key`、`metric.unit`、`metric.precision`。
- **不覆盖** `retrieved_at`、`source_url`、`license`——同一份数据在不同时间抓取应得到同一哈希，便于去重与重渲比对。
- 规范化：UTF-8、键排序、无空白；数值按 `precision` 固定小数位输出为十进制字符串后参与哈希（避免浮点表示差异）。
- 重渲必须使用同一份快照；快照缺失则任务失败，不允许重新抓取顶替。
- `dataset` 与 `script` 一起计入版本指纹。

---

## 8. 边界（明确不做）

- 不做多源合并：一份快照只来自一个 `source_key`。
- 不做单位换算：`unit` 原样透传，换算规则属于适配器，不进契约。
- 不做缺失值填补：缺测行直接剔除；剔除后不足最小点数则不出片。
- 不建历史库：`history_days` 是源的属性，不是系统的存储。
- 不做派生指标：`facts` 只含 §4 列出的字段。

---

## 9. 版本

- 当前 `dataset/1`。
- 变更 `shape`、`columns`、`facts` 结构或哈希口径即为不兼容变更，`contract` 进位并同步 `data` 闸与模板。
- 只改文案、不改结构，不进位。
