# 证据收集

**日期：** 2026-11-16
**Phase：** 3（第三步）

---

## 一、不另存一份证据

plan.md §8 要求「关键结论必须能够追溯到具体证据」。最容易走偏的做法是让模型把查到的东西抄一遍存进
`evidence` 字段——那会得到两份数据，一份在账本、一份在结论里，而且**迟早对不上**。

账本 `heron_agent_tool_call` 里已经有每次调用的入出参摘要。所以这一步只做一件事：
**把结论和具体调用连起来**，存引用关系，不存本体。

```
heron_research_task.evidence_json =
  { version, researchId, attemptNo, toolCallCount, submitted, complete,
    calls: [ { callId, round, tool, status, request, response, cited? } ],
    unverifiedCitations: [ ... ] }
```

迁移 `036-research-evidence.sql`，一列，`json` 类型。

---

## 二、真正有价值的是 `unverifiedCitations`

模型可以在 `reason` 里写任何数字。让它在 `evidence` 里列出「我查过哪些源」之后，**账本是唯一能对质的地方**：

- 它说引用过 `frankfurter-audcny`，账本里有没有任何一次调用的入出参出现过这个 key？
- 有 → 这条证据被「认领」，`calls[i].cited` 标上是哪个来源。
- 没有 → 进 `unverifiedCitations`，并且 `complete` 置 false。

这就是「能追溯」这句话的可执行含义。补一个反向验证：所有引用都能对上，`complete` 才是 true。

### 三个刻意的取舍

**1. 不交结论时 `complete` 恒为 false。** 没交结论的证据只是「过程记录」，不能算「支撑某个判断的证据」。
让两种东西共用一个字段但不加区分，等于把「查过」冒充成「能证明」。

**2. 账本读不出来时返回 `null`，不给空壳。** 一个 `toolCallCount: 0, complete: true` 的空证据链
看起来像「这条结论不需要证据」，而事实是「我们不知道」。宁可没有。

**3. 出参摘要截到 400 字。** 证据是索引，不是本体——完整返回在账本里，这里只需要够认出是哪次调用。

---

## 三、顺带修掉的参数膨胀

`Outcome` 原本是 7 个位置参数，这轮要加 decision/confidence/stoppedEarly/evidence 就会变成 10 个。
改成嵌套的 `Findings` 分组：`Outcome(text, rounds, toolCalls, in, out, budgetExceeded, maxItersReached, findings)`。

不是为了好看——10 个位置参数里连错两个 `boolean` 编译器不会报错，测试也不会。

---

## 四、验证

### 真跑：模型给出 4 个来源，全部对上

```
decision=OBSERVE
cited=[900, frankfurter-audcny, frankfurter-audusd, frankfurter-usdcny]

1  query_opportunity_detail
2  query_trend_series · query_dataset_snapshot · query_data_sources
2  query_source_health · query_recent_productions
3  compare_series · query_trend_series · query_opportunities
4  submit_findings

evidence: toolCallCount=10  submitted=true  complete=true
          calls[0].cited = "900"   ← 对上了第 1 轮那次 query_opportunity_detail
```

它列的 4 个来源对应真实的调查动作：`audusd` / `usdcny` 正是它用 `compare_series`
做交叉验证时查的两个源，不是把机会 id 复制一遍凑数。

### 单测（新增 6 项，总 343 项全绿）

| 用例 | 断言 |
|---|---|
| 声明过的源在账本里出现 | `cited` 回填、无未核实、`complete=true` |
| **声明了没查过的源** | 进 `unverifiedCitations`、`complete=false` |
| 没交结论 | `complete=false`，但不产生假的未核实项 |
| 一次都没查就交结论 | 调用数为 0 但 `complete=true`（诚实：确实不需要查） |
| 账本读不出来 | 返回 `null` |
| 出参超长 | 截到 400 字并加省略号 |

---

## 五、还没做

- **引用只做子串匹配**。模型写 `AUD/CNY` 而账本里是 `frankfurter-audcny` 就匹配不上，
  会被误报为未核实。真跑没踩到（它写的就是 sourceKey），但这是已知的粗糙处。
- **不校验 reason 里的数字**。证据链证明「它查过这些源」，不证明「它引用的数字出自这些源」。
  要做后者得解析出参里的数值再比对，那是另一件事。
- **结构化研究报告**：目前是 decision/reason/angle + 证据链，还不是完整报告。
- **绑定 `FrozenContext`**：`snapshot_ref` 列已经有值（机会 id），但还没有接真正的冻结快照机制。
