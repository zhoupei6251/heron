# 调查结束判断

**日期：** 2026-11-16
**Phase：** 3（第二步）

---

## 一、七条条件里，五条早就有，缺的是最后那条

plan.md §5.3 列了七条结束条件：

| 条件 | 现状 |
|---|---|
| Token 预算耗尽 | 已有（预算闸） |
| 调查时间超限 | 已有（5 分钟闸） |
| 达到最大推理轮数 | 已有（maxIters） |
| 已找到足够证据支持结论 | **缺** |
| 继续调查的边际价值较低 | **缺** |
| 关键数据不可获取 | **缺** |
| 数据存在严重冲突 | **缺** |

前三条是闸，**闸只能打停**。后四条是模型的判断，需要模型自己说「够了，停」。

没有这个能力时，模型的行为是：一直查到 `maxIters`，然后被框架强制转去总结。看起来也能出结论，
但那不是「知道什么时候该停」——它只是被关掉了。差别在于：**主动收尾省下的轮数是实打实的 token**。

---

## 二、做法：一个会短路 ReAct 循环的收尾工具

新增 `submit_findings`：

```java
@Tool(name = "submit_findings", readOnly = true, returnDirect = true)
public String submitFindings(String decision, String reason, String angle, double confidence)
```

关键在 `returnDirect = true`——框架文档写明：这次结果**直接成为本轮的最终回答，跳过下一次模型推理**。
于是「模型判断证据够了」和「省下一轮 token」是同一件事，不需要另一套调度。

### 三个刻意的设计

**1. 收尾不过预算闸。** 收尾不查数据，不该占工具名额。否则预算刚好用尽时，模型连结论都交不出来，
`partial` 会退化成「停了但什么都没有」——这个坑在工具预算那一轮已经踩过一次。

**2. `decision` 大写归一化后再校验。** 模型常说 `reject` 而不是 `REJECT`，直接拒绝太脆。
但**不猜**：非法值仍然抛 400，让它自己改。

**3. `confidence` 的 0 当「没传」。** `double` 参数拿不到 null，而「零把握」不是一个有意义的表达。
诚实地说「没给」比编一个 0.0 好。

### 结论进库，不再只存在于正文

以前 `decisionType` / `confidence` 两列永远是空的——模型把判断写在正文里，没人解析。
现在模型自己交结构化结论，`ResearchFindings` 容器接着，跑完写进 `heron_research_task`。

前端页面不用改：决策列早就有了，只是以前一直显示 `-`。

### 状态判定修正

轮数跑满**但模型主动收过尾**，不再标 `partial`：

```java
} else if (outcome.budgetExceeded() || (outcome.maxItersReached() && !outcome.stoppedEarly())) {
```

把「跑满 8 轮后主动收尾」标成部分完成，会让人误以为还得再看一遍。预算到点仍然算 partial——
那是真的没查完。

---

## 三、验证

### 真跑：模型在 5 轮停手，不是跑满 8 轮

用临时探针（验完已删）跑真模型：

```
rounds=5  toolUsed=8  submitted=true  decision=OBSERVE  confidence=0.72

1  query_opportunity_detail   1  query_opportunities
2  query_trend_series         2  query_dataset_snapshot   2  query_source_health
3  compare_series             3  query_data_sources
4  query_recent_productions
5  submit_findings   ← 自己收尾
```

理由值得抄一遍：**「该源总计只有 8 个点，z-score -1.8 是在这 8 点内部自比得出、无独立基线；
`compare_series` 明确返回 unsupported（点数不足），可对照源为 0，故『极端』无法验证」**。

这不是被闸打停的产物——它用了 `compare_series` 的 `unsupported` 和 `query_data_sources` 的空结果
作为「关键数据不可获取」的证据，主动判 OBSERVE。这正是 §5.3 里那三条要靠模型判断的条件。

### 单测（新增 5 项，总 337 项全绿）

| 用例 | 断言 |
|---|---|
| 收尾写入 | `reject` 归一化成 `REJECT`；空白 angle 存 null；confidence 记录 |
| 收尾不占名额 | 调完 `toolUsed()` 仍是 0 |
| 预算用尽仍能收尾 | 超额状态下 `submit_findings` 照常工作 |
| 参数校验 | 非法 decision、空 reason、confidence 1.5 都抛 400 |
| 状态判定 | 跑满 8 轮但主动收尾 → `succeeded`；预算到点 → `partial` |
| 决策落库 | `decision_type` 与 `confidence` 真的进了更新参数 |

---

## 四、还没做

- **证据收集**：`evidence` 字段从提示词里去掉了——模型引用工具结果时本来就带着数值，再要求它摘一遍
  只会多花 token 且容易和正文不一致。真正需要的是从工具账本反查（哪个结论对应哪次调用），
  那是下一步的事。
- **结构化研究报告**：现在是 `decision/reason/angle`，还不是完整报告。
- **冲突识别**：模型已经在做（探针里它自己发现了周末点与工作日本币口径冲突），但没有专门的机制保障。
