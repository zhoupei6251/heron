# 上下文压缩

> 上游：<https://java.agentscope.io/v2/zh/docs/harness/compaction> · 快照：`_upstream/harness-compaction.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：在不丢失关键信息的前提下，把对话上下文控制在模型的 token 预算内

本页讲 `HarnessAgent` 用来把对话控制在模型 token 预算内的几种策略，建立在 [context-and-agent-state.md](context-and-agent-state.md) 描述的无状态引擎和 `AgentState` 持久化之上——压缩操作的对象正是持久化链路保存和恢复的同一份 `AgentState`。

> **两条链路如何配合**：压缩在内存里更新 `AgentState.contextMutable()`，状态存储在 call 结束时把更新后的 `AgentState` 整体持久化。两条路径独立但按顺序执行——状态存储拿到的永远是压缩后的版本。

## 四套策略

| 策略 | 解决的问题 | 触发时机 | 中间件 |
| - | - | - | - |
| **对话摘要压缩** | 上下文太「深」——消息条数 / token 累计太多 | 每次模型推理前 | `CompactionMiddleware` |
| **大工具结果卸载** | 上下文太「宽」——单条工具结果体量过大 | 工具执行后 | `ToolResultEvictionMiddleware` |
| **上下文溢出兜底** | 真的撞到模型 `context_length_exceeded` | `call()` 抛错时 | `HarnessAgent.recoverFromOverflow` |
| **预压缩参数截断** | 工具调用参数（`write_file` 的内容）体量大但后期没人看 | 摘要之前的轻量预处理 | `CompactionConfig.TruncateArgsConfig` |

四套策略**正交，可任意组合，默认全部不开**。

### 1. 对话摘要压缩（`CompactionMiddleware`）

按消息条数或估算 token 触发，把对话**前缀**用一次 LLM 调用压成结构化摘要，**保留尾部 N 条最近消息原文**，然后把 `[summary] + [recent tail]` 写回 `AgentState.contextMutable()`。

```java
HarnessAgent.builder()
    .compaction(CompactionConfig.builder()
        .triggerMessages(30)     // 30 条触发
        .keepMessages(10)        // 压缩后保留最近 10 条原文
        .build())
    .build();
```

默认摘要 prompt 把内容组织成 `SESSION INTENT / SUMMARY / ARTIFACTS / NEXT STEPS` 四个小节，适合工程 / 编排类 agent。`CompactionConfig` 支持 `.model(...)` 为摘要指定独立模型（不设则用主模型）。完整字段表（`triggerTokens`、`keepTokens`、`flushBeforeCompact`、`offloadBeforeCompact`、`model`、`TruncateArgsConfig`）与摘要 prompt 模板见 [harness-memory.md](harness-memory.md)，此处不重复。

### 2. 大工具结果卸载（`ToolResultEvictionMiddleware`）

跟摘要压缩独立。某条工具结果文本超过阈值（默认 **80K 字符 ≈ 20K tokens**），全文写到工作区某个目录，上下文里只保留**首尾各约 2K 字符 + 一个 `read_file` 路径提示符**。agent 想看全文自己 `read_file`。

```java
HarnessAgent.builder()
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();
```

默认排除 `read_file` / `write_file` / `edit_file` / `list_files` / `memory_*` / `session_search`——这些工具要么自带分页、要么返回值很小。`grep_files` 和 `glob_files` 会强制限制结果条数，但仍可触发大结果卸载，作为单条结果异常大时的第二道保护。**Shell `execute` 默认不排除**，因为命令输出可能非常大。

### 3. 上下文溢出兜底

模型直接返回 `context_length_exceeded` / `maximum context` / `token limit` 等错误时，`HarnessAgent.recoverFromOverflow()` 强制走一次 `triggerMessages=1` 的极端压缩，然后**自动重试一次**。前提是构造时配了 `.compaction(...)`，否则错误原样抛回上层。

这条链路无需额外配置：`compaction` 开了，溢出恢复就自动开。

### 4. 预压缩参数截断（可选）

在 LLM 摘要前先做一遍**不走 LLM** 的字符串截断——`write_file`、`edit_file` 这类工具入参体量大但事后没人看：

```java
CompactionConfig.builder()
    .triggerMessages(80)
    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
        .maxArgLength(2000)
        .truncationText("... [truncated] ...")
        .build())
    .build()
```

很多场景下光这一步就能把触发摘要的频率压下一大截，几乎零成本。

## 压缩与 Memory 的联动

`CompactionConfig.flushBeforeCompact`（默认 `true`）决定摘要发生前是否先把对话前缀里的事实抽取到长期记忆——由 `MemoryFlushMiddleware` + `MemoryFlushManager` 完成，读 `<workspace>/MEMORY.md` 与 `memory/*.md`，把新事实增量写进去。等摘要丢掉前缀消息时，信息不会随之消失——agent 仍可通过 `memory_search` / `memory_get` 回头查。

类似地，`offloadBeforeCompact`（默认 `true`）在摘要前把**原始消息**整段写到永不压缩的 `*.log.jsonl`，供 `session_search` 检索。

> Memory 子系统的完整机制（双层结构、后台维护、记忆工具）见 [harness-memory.md](harness-memory.md)。压缩与 memory 是常一起用的两个组件，但开关各自独立。

## 压缩不会触碰的内容

`ConversationCompactor` 只处理 `AgentState.contextMutable()` 里的**对话消息列表**。下面这些活在 `AgentState` 其它字段里，**完全不受摘要压缩影响**：

- **Plan Mode 状态**（`AgentState.getPlanModeContext()`）：是否在 plan 阶段、当前计划文件路径。计划文件本身在工作区 `plans/` 下，生命周期由 Plan Mode 管理。见 [harness-plan-mode.md](harness-plan-mode.md)。
- **子 agent 后台任务**（`task_id`、状态、结果）：住在 `<workspace>/agents/<parentAgentId>/tasks/<sessionId>.json`，由 `TaskRepository` 单独维护；主 agent 下一轮推理前通过 system reminder 反向注入完成结果，**不进入对话消息流**，摘要无从压缩。见 [harness-subagent.md](harness-subagent.md)。
- **`todo_write` 任务清单**（`AgentState.getTasksContext()`）：独立字段，跟 `AgentState` 一起持久化，但不参与对话压缩。见 [harness-plan-mode.md](harness-plan-mode.md)。
- **权限规则**（`getPermissionContext()`）：独立字段，自带持久化。

这些组件各有自己的状态机和恢复机制，压缩通路对它们透明——可以放心开 `.compaction(...)`，不用担心丢 plan 或丢未完成的后台 task。

## 用 agent 自己查历史会话

启用会话能力时（默认开），三个查询工具自动注册：

- `session_list agentId="..."` —— 列出某个 agent 的历史会话。
- `session_history agentId="..." sessionId="..." lastN=20` —— 看某次会话最近 N 条消息。
- `session_search query="..." agentId="..."` —— 在历史会话里关键词搜索。

它们读的是**永不压缩的对话日志**（`<workspace>/agents/<agentId>/sessions/<sessionId>.log.jsonl`），所以即使上下文已被压成摘要，agent 也能查到原始消息。

## Heron 现状

`AgentRuntimeConfig` 没有调用 `.compaction(...)` 也没有 `.toolResultEviction(...)`，并且调用了 `.disableMemoryHooks()`、`.disableSessionPersistence()`、`.disableTranscript()`、`.disableWorkspaceContext()`——四套压缩策略**一套都没开**。

这意味着当前 `maxIters`（默认 8，`heron.agent.max-iters` 可配）跑完之前，一旦单次工具结果很大或对话变长，只能靠模型上下文窗口硬扛；撞到上限时没有兜底重试，错误会直接抛回业务层。

按投入产出排序，接入成本最低的是：

| 优先级 | 动作 | 理由 |
| - | - | - |
| 1 | `.toolResultEviction(ToolResultEvictionConfig.defaults())` | 与工作区、记忆开关解耦，一行即可挡住「单条工具结果过大」 |
| 2 | `.compaction(CompactionConfig.builder().triggerMessages(n).keepMessages(m).build())` | 同时获得溢出兜底；注意 `flushBeforeCompact` 默认 `true`，会连带触发记忆 flush，而记忆 hooks 目前是关的 |

相关：[harness-memory.md](harness-memory.md)、[context-and-agent-state.md](context-and-agent-state.md)、[harness-plan-mode.md](harness-plan-mode.md)
