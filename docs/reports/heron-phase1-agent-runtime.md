# Phase 1：Agent 运行时装配与只读工具

**日期：** 2026-11-13
**前置：** `heron-phase1-agentscope-probe.md`（冲突已解）、`heron-agent-implementation-plan.md` §7
**状态：** 装配与工具完成并通过测试；**真实模型调用未跑**

---

## 一、做了什么

| 文件 | 内容 |
|---|---|
| `ruoyi-modules/ruoyi-heron/pom.xml` | 加 `agentscope-harness:2.0.4` 与 `agentscope-extensions-model-openai:2.0.4` |
| `config/AgentRuntimeConfig.java` | 手工装配，`@ConditionalOnProperty(heron.agent.enabled=true)`，默认不生效 |
| `service/impl/agent/HeronResearchTools.java` | 两个只读工具 |
| `resources/prompt/research/researcher.md` | 研究员 system prompt |

**没有**建表、没有改任何现有 Service、没有接业务链路、没有开自动、没有发布。

---

## 二、装配的关键取舍

### 2.1 不用官方 starter，手工装配

原因是 starter 钉 Spring Boot 4.0.x，且它给的 `Memory` 是 `InMemoryMemory` 单例——有状态且非线程安全。
手工装配才能保证每个研究任务一份。

### 2.2 危险能力逐项关闭

```java
.disableShellTool()            .disableFilesystemTools()
.disableWebTools()             .disableMemoryTools()
.disableMemoryHooks()          .disableSessionPersistence()
.disableWorkspaceContext()     .disableAtPathExpansion()
.disableDynamicSkills()        .disableDefaultWorkspaceSkills()
.disableSubagents()            .disableToolsConfig()
```

为什么逐项写死而不依赖默认值：这些是 plan.md §6.3 的硬边界，少关一项就是给模型留一个越权入口。

> **实测更正（真跑之后发现）：** `disableSessionPersistence()` 挡不住会话记录落盘。
> 框架仍在工作目录下写了 `.agentscope/workspace/agents/<name>/sessions/*.jsonl`（含完整对话与工具输出）。
> 已在 `.gitignore` 里忽略，但**生产环境必须把落盘路径钉死**，否则它会跟着应用的启动目录走。
> 详见 `heron-phase1-real-run.md` §六。

### 2.3 模型配置不另起一套

沿用 `sys_config` 的 `heron.model.base-url` / `name` / `api-key`，读取方式与 `OpenAiModelClient` 一致
（先 `ConfigService`，再 `Environment`），未配置时抛 `model_not_configured`。

### 2.4 工具返回值不用 JsonNode

**这是接入时最容易踩的坑。** 项目用 Jackson 3（`tools.jackson`），AgentScope 用 Jackson 2 序列化工具入出参。
把 `JsonNode` 当作工具返回值，等于让两个 Jackson 栈在同一段数据上碰面。

所以工具只返回 `String` / `int` / `boolean` / `List<String>`，JsonNode 在工具内部就地转成文本。

---

## 三、两个工具

| 工具 | 取数路径 | 边界 |
|---|---|---|
| `query_opportunity_detail(opportunityId)` | `HeronDataOpportunityMapper.selectById` | 查不到返回 `found=false`；`signals` 截断到 4000 字符 |
| `query_trend_series(sourceKey, points)` | `DatasetFetchService.snapshot(sourceKey, points)` | 点数夹在 2~60（默认 30）；末尾点按字符预算取，单次返回 ≤4000 字符 |

工具的 `readOnly = true`，实现里只有 select 与现成快照读取，没有任何写入口。

---

## 四、验证

```
AgentScope 探针        : 2 项通过   （schema 派生；危险工具关得掉）
研究工具边界           : 5 项通过   （点数夹取、缺数据不编造、返回类型不含 JsonNode）
Agent 装配             : 2 项通过   （关着不建 Bean；开着只挂只读工具）
heron 默认组           : 299 项，0 失败，0 错误，5 skipped，BUILD SUCCESS
```

装配测试用的是 `ApplicationContextRunner` 轻量上下文，不启动整个应用、不连 MySQL/Redis。

**其中最有价值的一条断言：** 开关打开后，Agent 的工具集里只有
`query_trend_series` 与 `query_opportunity_detail`，没有任何名字含 shell / bash / file / write / edit 的工具。
这证明「安全边界可控」不是纸面结论。

---

## 五、未验证（下一步要做的）

| 项 | 为什么没做 |
|---|---|
| **真实模型跑一次研究** | 需要 `HERON_REAL_MODEL_KEY` 或可用的 `sys_config` 模型配置；本轮未调用任何模型 |
| 工具在真库上的行为 | `DatasetFetchService.snapshot` 会真抓一次数据（出网），单测里是 mock |
| 预算闸 | `maxIters` 目前只从配置读并交给框架；token / 工具次数 / 金额的硬闸属于 Phase 2 |
| 会话复用 | `disableSessionPersistence()` 已关，跨重试续跑要靠 `heron_task_step.checkpoint`，Phase 2 才做 |
| Reactor 线程上的权限/上下文 | 本轮没有业务调用，所以没触发；Phase 2 接业务时必须先解决（见架构审查 §6） |
| 应用整体启动 | 未启动，因为需要 MySQL + Redis，属服务操作 |

---

## 六、下一步的最小验证

跑一次真实研究，只需要一条命令级别的东西：

1. 在 `sys_config` 里配好 `heron.model.*`（或设环境变量）；
2. 打开 `heron.agent.enabled=true`；
3. 用一条真实机会 id 调一次 `queryOpportunityDetail` → 让 Agent 判断要不要再查 `queryTrendSeries`；
4. 看它是否**主动**发起了第二次查询。

第 4 条是这个 PoC 唯一真正要回答的问题：模型会不会自己决定去多看一眼数据。
它不会，就说明当前这层 Agent 的价值只是「一次带工具的 LLM 调用」，后面四个 Agent 的角色设计都要重估。
