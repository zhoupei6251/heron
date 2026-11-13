# 预算闸与工具调用账本

**日期：** 2026-11-13
**Phase：** 2（第三步）

---

## 一、账本

表 `heron_agent_tool_call`（迁移 `034`）：`research_id` / `attempt_no` / `round_no` / `tool_name` /
入参摘要 / 出参摘要 / 状态 / 耗时 / 错误码。只存摘要（各 2000 字符），不存原始返回。

用官方 `Hook` 采集，不往工具里埋点——工具是业务代码，记账是横切关注点。
记账失败只记日志，不打断研究：账错了不能把已经跑出来的结论一起扔掉。

---

## 二、预算闸：位置换过一次，这是关键

### 第一版：在 hook 里 `PostActingEvent.stopAgent()`

实测**不成立**：预算设 2，实际执行了 5 次工具调用。原因是同一轮推理会一次发出多个工具调用，
`stopAgent()` 只能影响「下一次推理」，拦不住已经发出去的那一批。

而且 `stopAgent()` 是硬掐，**没有收尾**——结论文本长度 0，`partial` 变成了「停了但没有东西」。

### 第二版（现行）：在工具入口拦

```java
ResearchBudget budget = new ResearchBudget(maxToolCalls, maxTokens);   // 每次研究一份
// 工具方法第一行
String rejection = budget.tryAcquireTool();
if (rejection != null) throw new HeronBusinessException(429, rejection, "...");
```

超限不是终止研究，而是**让这一次工具调用失败**。模型看到「次数已用尽」会转去总结已有信息，
这正是 plan.md §5.2 要的「保存已获取证据、输出部分结果」，而不是伪造一个完整结论。

`maxIters` 仍是硬上限（到点走框架的 summarizing），预算闸管的是工作量。

---

## 三、验证（真模型、真库，预算设为 2）

```
任务行 : status=partial  rounds=3  tool_calls=2  result_text=1718 字符
账本行 :
  round 1  query_opportunity_detail  ok      71ms
  round 2  query_trend_series        ok       9ms
  round 2  query_trend_series        failed  tool_budget_exceeded  16ms
```

三件事同时成立：

1. **工作量被关住**：ok 的调用正好 2 次，第 3 次被拒；
2. **被拒的那次也留痕**：状态 `failed` + 错误码，事后能看出「不是没调，是被拦了」；
3. **结论不为空**：1718 字，模型基于已查到的东西给了判断——`partial` 终于是「不完整但可用」，不再是空的。

306 项测试全绿。

---

## 四、顺带修掉的一个真问题

第一次跑这条链路时，模型一次工具都没调，直接反问我「请给我 opportunityId」。

原因不在模型：`createFor(opportunityId, goal)` 把 `goal` 原样存了，而调用方写的是
「判断这条数据机会值不值得做」——里面没有 id，模型手里只有按 id 查的工具。

已改成服务侧自动补前缀：`"机会 " + opportunityId + " 在候选里。" + goal`。
**机会引用不该指望调用方写全**，它是任务的身份，不是文案。

---

## 五、token 预算（本节补充）

### 用量从哪来

旧 `Hook` 事件表里**没有**模型调用事件：`HookEventType` 只有 PRE/POST_CALL、PRE/POST_REASONING、
PRE/POST_ACTING、chunk、summary、ERROR。带 `ChatUsage` 的 `ModelCallEndEvent` 在新 `event` 包里，
只走 `MiddlewareBase`，旧 hook 收不到。

不用引中间件：框架会把流式分片累加好的 `ChatUsage` 合并进最终的推理消息
（`ReasoningContext` → `mergeCollectedMetadata`），所以 `POST_REASONING` 的
`getReasoningMessage().getChatUsage()` 就是本轮用量。零新 API 面。

### 闸还开在工具入口

token 只能事后算——这一轮花了多少，要等它结束才知道。所以它在**下一次**工具入口生效：
花费已经发生，能拦住的是之后的继续烧钱。这与工具次数的口径一致，也复用了同一个已验证的闸。

`ResearchBudget` 把两维合到一个对象里。**token 判定放在工具计数之前**：被 token 拒掉的调用
不该再吃掉一个工具名额，否则 `tool_used` 会虚高。

### 账本判定的修正

原来靠 `callNo > limit` 猜「这一行是不是被预算拒的」，是近似值。现在看框架给的
`ToolResultBlock.getState() == ERROR` 判失败，只有失败的调用才取走预算的挂起拒绝原因——
成功的调用不会吞掉别人的错误码。附带修掉一个真错：**非预算原因导致的工具失败以前被记成 ok**。

### 验证（真模型，假数据，两个会话）

```
A 正常预算（token 上限 300000）
  rounds=4  toolUsed=3  in=7927  out=3288  exceeded=false  textLen=1803
  账本： round1 query_opportunity_detail ok
        round2 query_trend_series       ok
        round3 query_trend_series       ok

B token 上限设 1
  rounds=2  toolUsed=0  in=2245  out=671   exceeded=true   textLen=918
  账本： round1 query_opportunity_detail failed  token_budget_exceeded
```

B 的行为值得说明：第一次模型调用结束后用量已是 2245，于是**第一个工具就被拒**，
`toolUsed=0`。结论仍有 918 字——`partial` 依然是「不完整但可用」。

落库那两列另有单测：真实 `LambdaCrudChainWrapper` 断言更新参数里出现 `7927` / `3288`，
不是 mock 出来的假通过。

312 项测试全绿。

### 为什么没有「金额」

金额 = token × 单价。当前端点走的是订阅式 coding plan，**没有按 token 计价**，
凭空给一个单价算出来的钱数是假的。要按金额拦，需要先有真实单价（每百万 token 输入/输出多少），
那是配置项，不是代码能推出来的。

2026-11-13 决定：**暂不做金额**。不是被单价卡住，是当前订阅式计费下这个问题没有意义；
真要按钱管，得先换按 token 计费的端点。token 用量已经落库，将来要折算随时能算。

---

## 六、还没做

| 项 | 说明 |
|---|---|
| 金额预算 | 需要真实单价配置才能算；见上 |
| 心跳与取消 | 字段有，但没有心跳线程；租约过期后没有回收逻辑 |
| `partial` 的判定口径 | 已含工具次数与 token；`maxIters` 到顶还不算 partial |
| 出参脱敏 | 现在只有长度截断，没做字段级脱敏 |
