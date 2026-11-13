# 研究任务表与服务

**日期：** 2026-11-13
**Phase：** 2（第二步）

---

## 一、包结构先落定

AgentScope 相关代码收进一处，并且**业务侧不再依赖框架**：

```
org.dromara.heron.service.IResearchAgent        ← 端口：research(sessionId, goal) → 结论文本
org.dromara.heron.agentscope/
    AgentRuntimeConfig.java                     ← 装配
    ResearchAgentFactory.java                   ← 按会话建 Agent
    AgentscopeResearchAgent.java                ← 端口的实现
    HeronResearchTools.java                     ← @Tool 只读工具
```

**只有 `agentscope` 这个包会 import `io.agentscope`。** 包名带框架名是刻意的：它就是适配层，
换框架时整个包换掉，业务代码不动。为此加了一个薄端口 `IResearchAgent`——没有端口的话，
名字只是标签，业务照样会 import 框架类型。

---

## 二、表：`heron_research_task`

为什么另建表而不复用 `heron_production_task`：

| | 生产任务 | 研究任务 |
|---|---|---|
| 与版本的关系 | 1:1（`uk_task_version`） | 1 研究 : N 候选 |
| 幂等键 | 同一版本不能做两次 | 同一机会同一天不重复研究 |
| 预算 | 无固定路径 | 必须有（轮数/token/金额） |

**状态词表与生产保持一致**，只多一个 `partial`（预算或证据不足时提前收尾，结论可用但不完整）。

关键列：`dedup_key`（机会+日期的 sha256，唯一）、`session_id`（创建时定死）、
`attempt_no` / `lease_until` / `heartbeat_at`（领取与重试）、`result_text`、`error_code/reason`。

---

## 三、服务：`ResearchTaskServiceImpl`

```java
long createFor(long opportunityId, String goal);   // 同机会同日已存在 → 直接返回既有 id
String run(long researchId);                       // 领租约 → 跑会话 → 落结论
HeronResearchTask find(long researchId);
```

三个设计点：

1. **领取用条件更新**，不是先查后改。只有仍处于 `queued` 的一方能改成 `running`，
   并发触发时后到者拿到 0 行、直接返回现状，不会重复烧模型。
2. **会话标识在创建时定死**（`"research-" + researchId`），重试才会续上同一段上下文。
3. **`@ConditionalOnProperty(heron.agent.enabled)`**：开关关着时这个服务根本不存在，
   不会因为缺 `IResearchAgent` 让应用起不来。

---

## 四、验证

### 单测：306 项全绿（新增 3 项）

- 去重键同日稳定、跨日不同、换机会不同；
- 已有任务时返回既有 id，不插新行；
- 新建时会话标识跟着任务走。

### 端到端（真模型、真库）

```
CREATE id1=2121182568933117952 id2=2121182568933117952 相同=true   ← 幂等
RUN status=succeeded
TASK status=succeeded attempt=1 session=research-2121182568933117952
RESULT 长度=1231
```

库里两处都对上：

```
heron_research_task : succeeded, attempt=1, lease_until=NULL, error_code=NULL, 结论 1231 字
heron_agent_state   : "__anon__:research-2121182568933117952", 10308 字节
```

**「一个研究 = 一个任务 = 一段会话」这条链，第一次真正跑通了。**

---

## 五、还没做

| 项 | 说明 |
|---|---|
| **预算闸** | `rounds` / `tool_calls` / token 都是空的。现在只有 `maxIters=8`，超时 10 分钟 |
| **工具调用账本** | 计划里的 `heron_agent_tool_call` 还没建；要用 `middleware` 记，不是工具内部埋点 |
| 取消与心跳 | 字段有（`lease_until`/`heartbeat_at`），但没有心跳线程与取消路径 |
| `partial` 状态 | 定义了常量，但没有产生它的代码 |
| 控制器与权限 | 没有 REST 入口，目前只能从脚本调 |
| `owner_id` / `actor_id` | 定时与脚本入口无登录态，先写 0；接控制器时换成真实操作人 |
