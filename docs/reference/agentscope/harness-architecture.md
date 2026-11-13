# Harness 架构

> 上游：<https://java.agentscope.io/v2/zh/docs/harness/architecture> · 快照：`_upstream/harness-architecture.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：HarnessAgent 是什么、各能力如何协作、状态如何在一次 `call()` 中流转

`HarnessAgent` 是 `ReActAgent` 的一层薄包装，把长期运行 agent 必需的工程能力打包进同一个 builder：工作区驱动的人格、长期记忆、子 agent 编排、沙箱隔离、技能装配、计划模式、Channel 路由。

裸 `ReActAgent` 只解决「一次请求 → 推理 → 工具 → 回复」。Harness 回答另一组问题：下一轮怎么接上一轮、上下文如何保持有界、多用户如何隔离、危险操作如何先 review 再执行、可复用能力如何沉淀。

## 三条核心原理

1. **能力叠加在推理循环的关键时机上，不改写循环。** 工作区注入、压缩、子 agent、沙箱、Plan Mode 都钩在 ReAct 循环的关键点上，core 算法本身没动。
2. **能力之间互不依赖，只通过三个共享对象通信：**
   - `RuntimeContext` —— 这次 `call()` 是谁在说话（`sessionId`、`userId`、extra）。**不持久化**。
   - 工作区 —— 谁读写哪些文件。物理落本机 / 沙箱 / KV 由配置决定。
   - `AgentStateStore` —— 跨调用怎么恢复运行时状态。
3. **内置 middleware 注册顺序固定，你自己加的跑在最前面。** `.middleware(...)` 注册的会排在 Harness 内置之前。

## 能力清单（按需在 builder 上开）

| 能力 | 解决什么 | Builder 入口 | 本地文档 |
| - | - | - | - |
| 工作区驱动的人格 | 人格 / 知识 / 子 agent / 技能 / MCP 白名单都以文件存在 | `.workspace(path)` | [harness-workspace.md](harness-workspace.md) |
| 状态持久化 | 同 `(userId, sessionId)` 跨请求、跨进程、跨副本恢复 | 默认开；`.stateStore(...)` 换实现 | [context-and-agent-state.md](context-and-agent-state.md) |
| 双层长期记忆 | 有价值的事实自动沉淀到 `MEMORY.md` | 默认开；`.memory(...)` 定制 | [memory](https://java.agentscope.io/v2/zh/docs/harness/memory.md) |
| 对话压缩 | 上下文有界；模型溢出时强制重试 | `.compaction(...)` | [compaction](https://java.agentscope.io/v2/zh/docs/harness/compaction.md) |
| 大工具结果卸载 | 超 80K 字符的结果落盘 + 占位符 | `.toolResultEviction(...)` | [compaction](https://java.agentscope.io/v2/zh/docs/harness/compaction.md) |
| 子 agent 编排 | 委派给子 agent，同步或后台，自动反向通知 | `.subagent(...)` 或 `workspace/subagents/` | [subagent](https://java.agentscope.io/v2/zh/docs/harness/subagent.md) |
| 可插拔文件系统 | 本机 + shell / 共享存储 / 沙箱 | `.filesystem(...)` | [filesystem](https://java.agentscope.io/v2/zh/docs/harness/filesystem.md) |
| 沙箱隔离 | 文件与命令隔离、跨调用恢复、多副本 | `.filesystem(new DockerFilesystemSpec()...)` | [sandbox](https://java.agentscope.io/v2/zh/docs/harness/sandbox.md) |
| 计划模式 | 只读思考阶段 + HITL 退出 | `.enablePlanMode()` | [plan-mode](https://java.agentscope.io/v2/zh/docs/harness/plan-mode.md) |
| 技能装配 | Git / Nacos / MySQL / classpath / 工作区 | `.skillRepository(...)` | [skill](https://java.agentscope.io/v2/zh/docs/harness/skill.md) |
| MCP 与工具白名单 | 声明式 MCP server + 工具粒度 allow / deny | `workspace/tools.json` | [harness-workspace.md](harness-workspace.md) |
| Channel 路由 | 会话管理、per-session 并发控制、多 agent 路由、流式事件 | `agent.channel(...)` / `GatewayBootstrap` | [channel](https://java.agentscope.io/v2/zh/docs/harness/channel.md) |

## 状态怎么流转

状态分三层，框架在层间自动搬数据：

- **调用内** —— `AgentState`（对话上下文、权限规则、Plan Mode 状态、工具状态）+ `RuntimeContext`（`sessionId`、`userId`、沙箱句柄、extra）。
- **跨调用** —— 每次 `call()` 结束自动写盘、下次自动加载：`AgentStateStore`（默认 `~/.agentscope/state/<agentId>/`，按 `(userId, sessionId)` 寻址）里的运行时快照、`sessions/<sessionId>.log.jsonl` 的**永不压缩**对话日志、子任务记录、沙箱元数据。
- **长期记忆** —— 跨 session 累积：`memory/YYYY-MM-DD.md` 只追加；后台节流任务周期合并到 `MEMORY.md`；`MEMORY.md` 每轮注入 system prompt。

三条值得记住的规律：

- system prompt 每轮重新拼，改 `AGENTS.md` 或 `MEMORY.md` 立刻生效，不需要重启。
- 压缩、记忆提炼、后台维护都被节流闸门管着，不会每轮都跑。
- `AgentState` 由 core 的 `ReActAgent` + `AgentStateStore` 持久化，Harness 不额外做这件事。

## 自己加 middleware 时

- 用 `.middleware(...)`，你的 middleware 跑在所有 Harness 内置之前。
- 身份从 agent 上的 `RuntimeContext` 读（`userId` / `sessionId`）。
- 读写工作区用 `harnessAgent.getWorkspaceManager()`；它按当前文件系统模式正确路由。直接 `java.nio.Files` 在沙箱或远端模式下会写错地方。

## Heron 现状

`AgentRuntimeConfig` 目前把 Harness 绝大多数子系统显式关掉了：

```java
HarnessAgent.builder()
    .name("heron-research-" + Long.toString(System.currentTimeMillis(), 36))
    .sysPrompt(resource(SYSTEM_PROMPT))
    .model(heronAgentModel)
    .toolkit(toolkit)
    .maxIters(...)
    .disableShellTool().disableFilesystemTools().disableWebTools()
    .disableMemoryTools().disableMemoryHooks()
    .disableSessionPersistence().disableTranscript()
    .disableWorkspaceContext().disableAtPathExpansion()
    .disableDynamicSkills().disableDefaultWorkspaceSkills()
    .disableSubagents().disableToolsConfig()
    .build();
```

对照上表，当前等于只有「模型 + 工具集 + 迭代上限」在跑。要接哪项能力，从这张表找回对应 builder 入口即可；本组文档只覆盖了工作区、状态、权限、工具四项，其余按上表链接去上游看。

> 代码里那条「名字必须每次启动唯一」的注释指的是 `JsonFileAgentStateStore` 的默认落盘目录 `~/.agentscope/state/<agentName>/`，与本文「跨调用」一节一致。
