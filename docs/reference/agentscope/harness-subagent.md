# 子 Agent（Subagent）

> 上游：<https://java.agentscope.io/v2/zh/docs/harness/subagent> · 快照：`_upstream/harness-subagent.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：声明子 agent、同步/后台调用、自动反向通知、远程子 agent、流式转发

## 作用

让主 agent 把「可独立处理、上下文重、可并行」的任务委派出去，避免主线程膨胀。每个子 agent（本地 `HarnessAgent` 或远程 stub）跑自己的会话，与父对话相互独立：每次调用——带任务的 spawn，或 `agent_send` follow-up——都完整跑完并返回本次调用的结果（同步走工具结果，后台任务经推送回传）。存活的本地子 agent 在 follow-up send 之间保留自己的历史；远端后端可能为每次 send 开启新对话。会话持久化与恢复取决于声明（`persistSession`）和后端。

## 一个最小例子

把子 agent 的 spec 写到工作区即可，文件名就是 `agent_id`。

`workspace/subagents/reviewer.md`：

```markdown
---
description: 代码审查专家。当用户需要 review PR、找代码问题、检查代码规范时使用。
---

你是一个专注代码评审的子 agent。请按以下流程工作：
1. 先 read_file / grep_files 收集上下文
2. 给出按文件 / 行号的具体建议
3. 末尾给一个 1-5 的总体评分
```

主 agent 推理时就能调用：

```
agent_spawn agent_id="reviewer" task="review 这次 PR 的所有改动"
```

不需要任何注册。

## 几种声明方式

三类来源，构建时合并：

| 方式 | 适用 | 怎么配 |
| - | - | - |
| 内置 `general-purpose` | 通用兜底（镜像主 agent 能力） | 总是有，不需要配 |
| 工作区 spec 文件 | 项目特有的、能版本控制的 | `workspace/subagents/<id>.md` |
| 编程式声明 | 跑时才能确定（远程、动态参数） | `builder.subagent(SubagentDeclaration.builder()...)` |

### 工作区 spec 文件

非递归扫 `workspace/subagents/*.md`，文件名（去掉 `.md`）就是 `agent_id`，**不要**在 front matter 里再写 `name`。

```markdown
---
description: 代码评审专家     # 必填，agent 选择是否委派的关键依据
workspace:
  mode: isolated              # 默认 isolated；shared 表示和父共享工作区
  path: ./defs/reviewer       # 可选；不写就用默认子目录
model: openai:gpt-4o-mini     # 可选；不写就继承父 agent
steps: 8                      # 可选；这个子 agent 单次最多迭代次数
temperature: 0.2              # 可选；覆盖父的 GenerateOptions
top_p: 0.95                   # 可选
hidden: false                 # true 时不出现在 agent 可见列表（仍可程序化 spawn）
mode: subagent                # primary / subagent / all，默认 all；primary 不允许被 spawn
expose_to_user: true          # 可选三态；强制/禁止向用户暴露（不写表示不表态）
enable_pending_tool_recovery: true # 可选；不写则继承父 agent 的恢复配置
tools: [read_file, grep_files]   # 可选；继承工具的白名单
---

你是一个专注代码评审的子 agent。
```

### 编程式声明

```java
HarnessAgent.builder()
    .name("orchestrator")
    .model(model)
    .workspace(workspace)
    .subagent(SubagentDeclaration.builder()
        .name("reviewer")
        .description("代码审查专家")
        .workspace(Path.of("./defs/reviewer"))
        .workspaceMode(WorkspaceMode.ISOLATED)
        .model("qwen3-max")
        .steps(8)
        .tools(List.of("read_file", "grep_files"))
        .build())
    .subagent(SubagentDeclaration.builder()
        .name("remote-researcher")
        .description("远端调研子 agent")
        .url("http://agent-task-server:8080")     // 远程子 agent
        .headers(Map.of("Authorization", "Bearer xxx"))
        .build())
    .build();
```

三种来源互斥：`workspace(...)`、`inlineAgentsBody(...)`、`url(...)` **三选一**。

框架自动构建的本地子 agent（含 `general-purpose`）继承父级 `HarnessAgent.Builder.enablePendingToolRecovery(...)`，**默认关闭**。声明中可用 `.enablePendingToolRecovery(true)` / `(false)` 显式覆盖，`null` 表示继承。工作区 spec 支持 `enable_pending_tool_recovery`，也兼容 `enablePendingToolRecovery` 写法。开启后，新的普通消息会为悬空工具调用补充错误结果，也适用于从失败会话重新加载的工具调用；等待人工审批的工具仍须提供审批结果。远端 agent 和自定义工厂需自行配置恢复策略。

### 为子 agent 单独配置压缩

本地声明默认继承父 agent 的压缩配置与启用状态。工作区规格里 `compaction: false` 单独禁用压缩；`compaction: true` 使用默认配置（即使父已禁用也会启用）。映射形式指定独立配置，省略字段用 `CompactionConfig` 默认值：

```yaml
compaction:
  triggerMessages: 20
  keepMessages: 5
  keepTokens: 0
  flushBeforeCompact: false
```

支持字段：`triggerMessages`、`triggerTokens`、`reserved`、`keepMessages`、`keepTokens`、`keepTokensMin`、`keepTokensMax`、`keepTokensRatio`、`summaryPrompt`、`flushBeforeCompact`。**字段名区分大小写**。类型错误或未知字段会记警告，但声明仍加载并继承父配置；省略或设为 null 也继承。

Java 侧用 `SubagentDeclaration.Builder` 的 `.compaction(config)` 或 `.disableCompaction()`，**最后一次调用生效**；`.compaction(null)` 恢复继承。Java 配置还支持自定义模型、剪枝和参数截断。这些仅适用于本地子 agent；远程子 agent 自行管理压缩，远程声明里的 `compaction` 被忽略。

### 自定义工厂

子 agent 需要声明描述不了的实例时（自己的 middleware、父没有的工具、直接传 `Model` 对象），在工厂里自己构建：

```java
HarnessAgent.builder()
    .name("orchestrator")
    .model(model)
    .workspace(workspace)
    .subagentFactory(
        SubagentDeclaration.builder()
            .name("note-taker")
            .description("跨轮次积累笔记")
            .persistSession(true)
            .build(),
        parentRc -> HarnessAgent.builder()
            .name("note-taker")
            .model(noteModel)
            .workspace(noteWorkspace)
            .toolkit(noteToolkit)
            .middleware(auditMiddleware)
            .build())
    .build();
```

需要新实例时才调用工厂，参数是父 agent 的 `RuntimeContext`；持久化缓存命中时直接复用已有实例，不再调工厂。声明负责名称、描述，以及 spawn 时生效的选项：`persistSession`、`mode`、`hidden`、`exposeToUser`、`inheritParentPermissions`；描述「怎么构建实例」的字段（`model`、`tools`、`skills`、`workspace`、`steps` 等）被忽略。远程声明（`url(...)`）不能和工厂一起用。

简单场景仍可用 `subagentFactory(name, description, factory)`——它不带声明，所以每次 spawn 都是新会话。

### 内置 `general-purpose`

不需要声明文件，总是可用。角色是「通用兜底」——能力和主 agent 一致（同样的模型、工具、技能），共享主工作区。适合「想隔离上下文跑一个子任务但懒得专门写 spec」。

## ISOLATED vs SHARED

`workspaceMode` 决定子 agent 的工作区怎么算：

- **ISOLATED**（默认）：子 agent 有自己独立的工作区（声明里 `workspace.path` 没写时框架自动开一个子目录）。子 agent 的运行时状态按「父 sessionId × 用户」分桶——同一用户在不同对话里 spawn 同名子 agent 互不污染。
- **SHARED**：子 agent 直接用主工作区。适合子 agent 输出会被父立即读到的情况（例如 `general-purpose`）。

## 同步还是后台？

主 agent 通过 `agent_spawn` 创建子 agent，关键是 `timeout_seconds`：

- **`timeout_seconds > 0`（默认 30，最大 600）** —— 同步调用，主 agent block 等结果，结果作为工具结果返回。默认超时后会 **promote** 成后台任务（`status: timeout_promoted` + `task_id`），子 agent 继续跑。
- **`timeout_seconds = 0`** —— 后台调用，立即返回 `task_id`，子 agent 在后台跑。

**通过 `RuntimeContext` 强制同步**：应用侧可放入 `AgentSpawnTool.CTX_FORCE_SYNC = true`，覆盖 LLM 的异步选择；可选再放 `CTX_FORCE_SYNC_TIMEOUT_SECONDS` 指定硬超时（秒）：

```java
RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("s-1")
    .put(AgentSpawnTool.CTX_FORCE_SYNC, true)
    .put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 120) // 可选；覆盖 LLM 的 timeout_seconds
    .build();
```

开启后：

1. 设了 `CTX_FORCE_SYNC_TIMEOUT_SECONDS` 时**完全覆盖** LLM 的 `timeout_seconds`（`<=0` 回退 30s；应用设置的正数不受 600s 上限限制）。
2. 未设置时，LLM 传的 `timeout_seconds=0` 被改写成默认同步超时（30s），**不会**提交后台任务；LLM 传的正数超时仍生效。
3. 同步等待超时后返回 `status: timeout` 并中断子 agent，**不会** promote 成后台 `task_id`。

`agent_send` 同样遵守该开关。同一轮里多个强制同步的 `agent_spawn` 仍可按 Toolkit 默认并行推进。

`agent_spawn` / `agent_send` 使用自身等待超时，不受 `ExecutionConfig` 中普通工具超时限制；子 agent 内部调用的工具仍遵守普通工具超时。取消父调用仍会取消同步执行的子 agent。

如果目标可拆成多个互不依赖、资源不冲突的子任务，主 agent 可在同一轮 reasoning 里发起多个同步子 agent 调用。`ToolkitConfig.parallel` 默认 `true`，因此 `ReActAgent` 与 `HarnessAgent` 上这些同步调用并行推进，主 agent 等这一批工具结果都返回后再进入下一轮推理，相当于一次同步 fan-out / fan-in。要串行可用 `ToolkitConfig.builder().parallel(false).build()`。

任务拆解时先画清独立性和依赖图：无依赖边的节点适合交给多个子 agent 并行；有依赖的节点等上游结果后再派发或合并。短任务、关键路径适合同步等待或先用 barrier 等齐；长任务可用后台模式先跑，主 agent 继续处理其它工作，后续再取结果合并。

### 后台任务自动反向通知

后台任务跑完后**主 agent 不需要轮询**——下一次推理开始前，框架把已完成的任务结果作为系统提醒注入对话末尾：

```
<system-reminder>
后台任务已交付：
- task_id=xxx，agent=research-analyst，status=COMPLETED
  结果摘要：...
</system-reminder>
```

所以**不需要**在 prompt 里写「记得调 task_output 轮询」——那是旧版本做法。

### 后台任务工具

| 工具 | 职责 |
| - | - |
| `agent_spawn` | 创建子 agent，可选地执行任务（同步或后台） |
| `agent_send` | 向已存在的子 agent 追加消息 |
| `agent_list` | 列出当前活跃的子 agent 实例 |
| `task_output` | 通过 `task_id` 获取后台任务结果（阻塞或非阻塞） |
| `wait_async_results` | 等待后台结果；可按 `task_ids` 等指定任务全部完成，或 `wait_all=true` 等当前 session 未完成任务快照全部完成 |
| `task_cancel` | 取消正在运行的后台任务 |
| `task_list` | 列出所有后台任务及状态 |

`agent_spawn` / `agent_send` 管理子 agent **实例**；`task_output` / `wait_async_results` / `task_cancel` / `task_list` 管理后台**任务结果**。两者的桥梁是 `task_id`——`agent_spawn` 或 `agent_send` 使用 `timeout_seconds=0` 时返回。

> 多数情况下自动反向通知会把结果推回来，不需要显式调用任务工具。它们主要用作逃生口：在通知触发前主动检查进度、等待一组必须同时拿齐的结果、取消不再需要的任务、或在对话压缩后恢复任务状态。

异步结果三种收集方式：

- **主动通知**：不阻塞等待时的默认路径。子任务完成后，下一轮 reasoning 前通过 `<system-reminder>` 注入。
- **指定任务检查**：`task_output(task_id, block=false)` 主动查看某任务当前状态或终态结果。
- **等待 barrier（必须等齐时优先）**：`wait_async_results(task_ids="id1,id2")` 或 `wait_async_results(wait_all=true)`。barrier 等到集合终态，并**把各任务结果直接写进本次工具返回**，主 agent 可立刻继续推理。`wait_all=true` 以调用开始时的未完成任务快照为准，等待期间新创建的任务不会加入 wait set。

> **遗留 inbox-any**：不传 `task_ids` 也不传 `wait_all` 时，`wait_async_results` 只等到 inbox 中**任意一条**消息到达就返回，**这不是 wait-all**。要一组任务全部完成请用 `task_ids` 或 `wait_all=true`。

## 给已存在的子 agent 补一条消息

`agent_spawn` 返回值里有 `agent_key`（运行时实例句柄），用它或 `label` 追加消息：

```
agent_send agent_key="agent:reviewer:abc-123" message="顺便也看下 schema 变更"
```

spawn 时设了 `label` 也可用 label 寻址：

```
agent_spawn agent_id="reviewer" task="review 这次 PR" label="pr-reviewer"
agent_send label="pr-reviewer" message="顺便也看下 schema 变更"
```

列当前活跃子 agent：`agent_list`。

## 持久会话

默认每次 `agent_spawn` 都创建新的子 agent 实例和会话——不保留之前调用的上下文。声明里设 `persistSession(true)` 可让同一子 agent 在多次 spawn 之间复用：

```java
.subagent(SubagentDeclaration.builder()
    .name("note-taker")
    .description("跨对话轮次积累笔记")
    .persistSession(true)
    .build())
```

开启后框架按 `(userId, parentSessionId, agentId, label)` 生成确定性 key；再次 spawn 同样组合就复用已存在实例，对话历史和状态都保留。不同用户即使父会话 ID 和 label 相同，也用各自实例。`agent_spawn` / `agent_send` 的 label 按用户和父会话隔离。

持久化 key 现在包含用户身份，因此旧版本生成的 key 与新 spawn 的 key 不同；已保存条目仍可通过 `agent_send` 使用原有 `agent_key` 访问。

自定义工厂构建的子 agent 同理：用 `subagentFactory(declaration, factory)` 注册并设 `persistSession(true)`。

## 向用户暴露子 Agent

通常子 agent 对用户不可见——它们在幕后作为父 agent 的内部工具运行。`expose_to_user=true` 可把子 agent 暴露为**用户可直接交互的入口**：

```
agent_spawn agent_id="researcher" task="调研 AI 趋势" expose_to_user=true
```

这做两件事：在 Gateway 里注册子 agent 使其成为用户可寻址入口；发出 `SubagentExposedEvent` 到流式事件流，携带 `subagentId` 句柄。客户端收到后可直接向子 agent 发消息，完全绕过父 agent：

```java
chat.sendStream(SendOptions.userId("user-1"), "派一个研究员调查 AI 趋势")
    .doOnNext(event -> {
        if (event instanceof SubagentExposedEvent se) {
            // se.getSubagentId() → 用来直接和子 agent 对话
            // se.getAgentId()    → 子 agent 类型（如 "researcher"）
            // se.getLabel()      → 可选的人类可读名称
        }
    })
    .blockLast();

chat.sendToSubagent(subagentId, "重点关注 LLM agent").block();
```

适合「分支对话」场景。Channel 侧 API 见 [Channel](https://java.agentscope.io/v2/zh/docs/harness/channel)。

### 怎么开启

用 `agent.channel(...)`——bridge 自动接好，零配置：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("orchestrator")
    .model("dashscope:qwen-plus")
    .build();

// channel() 创建内部 gateway 并自动接好 bridge——expose_to_user 直接可用。
ChatUiChannel chat = agent.channel(ChatUiChannel.create());
```

没有绑定 Channel 时，`agent_spawn` 里的 `expose_to_user=true` 被**静默忽略**——子 agent 照常工作，只是不暴露给用户。

### 用代码控制是否暴露

生效值优先级（高 → 低）：

1. **`RuntimeContext` 按调用覆盖** —— 作用于当前调用里所有 `agent_spawn`；
2. **`SubagentDeclaration` 按类型策略** —— 该类型的静态默认值；
3. **LLM 传入的 `expose_to_user` 工具参数**；
4. 以上都没表态时默认 **`false`**。

```java
RuntimeContext ctx = RuntimeContext.builder()
    .userId("user-1")
    .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)   // 强制开启；传 false 则禁止暴露
    .build();
```

声明侧用三态 `exposeToUser`：`TRUE` 总是暴露，`FALSE` 永不暴露（即使 LLM 传 `true` 也被覆盖），`null`（默认）交给 context、再交给 LLM 参数：

```java
SubagentDeclaration decl = SubagentDeclaration.builder()
    .name("researcher")
    .description("调研主题并返回汇总报告。")
    .exposeToUser(true)
    .build();
```

Markdown spec front matter 同名字段（`expose_to_user: true`，不写表示不表态）。

### 跨重启与多副本

默认暴露只存在于创建它的进程里：`subagentId` 只在那个节点有效，重启即失效。要让暴露的子 agent 在**任意副本、重启之后**都能解析，配 `distributedStore(...)`：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("orchestrator")
    .model("dashscope:qwen-plus")
    .distributedStore(RedisDistributedStore.fromJedis(jedis))
    .build();

ChatUiChannel chat = agent.channel(ChatUiChannel.create());  // 恢复能力自动接好
```

`subagentId` 持久化到后端，子 agent 自己的对话按 session 从分布式 `AgentStateStore` 重新加载——即使后续消息落到不同节点，用户面对的仍是同一个子 agent。多 agent 的 `GatewayBootstrap` 传 `.distributedStore(...)`（不传则继承 main agent）。

## 让 agent 自己写新的子 agent spec

`agent_generate` 工具（**默认关闭**）可让 LLM 起草新的子 agent spec 并直接写到 `workspace/subagents/<name>.md`。开启方式（构建期）：拿到 builder 内部的 `SubagentsMiddleware` 引用，调 `enableAgentGenerateTool`。

适合「agent 跑到一半发现自己需要一类新助手」。**生产环境慎用**——通常先让 agent 把方案写出来人工 review 再写文件。

## 一些行为细节

- **`description` 要写好**：这是模型决定要不要委派的关键依据。「代码评审」远不如「当用户要 review PR、找代码风格问题时使用」有效。
- **递归保护**：子 agent 不能再 spawn 子 agent（被强制标为「叶子」）；同时还有硬上限 3 层。
- **userId 透传**：父的 `RuntimeContext.userId` 自动透到子，多租户隔离链不会断。
- **权限继承**：父的所有 DENY 权限规则自动传给子。父被禁用的工具，子也一样被禁——安全边界不会因为委派被绕过。声明里 `inheritParentPermissions(false)` 可关闭。
- **流式转发**：父 `stream()` 时，同步子 agent 的中间事件实时流回父的 `Flux`（带来源标记）。

## 远程子 agent

声明里只填 `url` + 可选 `headers`，子 agent 就走远程 HTTP 服务（Agent Protocol）执行：

```java
.subagent(SubagentDeclaration.builder()
    .name("remote-researcher")
    .description("远端调研子 agent")
    .url("http://agent-task-server:8080")
    .headers(Map.of("Authorization", "Bearer xxx"))
    .remoteStreaming(true)          // 未设置时默认 true
    .remoteStreamDetail(RemoteStreamDetail.VERBOSE)  // 未设置时为 FULL
    .remoteAskPolicy(RemoteAskPolicy.DENY)  // 默认
    .remoteContextAttributes(Map.of("region", "cn"))
    .build())
```

同样支持同步（`timeout_seconds>0`）和后台（`timeout_seconds=0`）。

| 字段 | 默认 | 说明 |
| - | - | - |
| `remoteStreaming` | `true`（未设置时） | 父用 `streamEvents()` 时，把远程任务 SSE 事件转发进父流，带 `source` 标记与 `metadata.taskId` / `metadata.parentSessionId` |
| `remoteStreamDetail` | `FULL` | 回传多少远程事件 |
| `remoteAskPolicy` | `DENY` | 如何处理远程工具确认（HITL）请求 |
| `remoteContextAttributes` | 无 | 每次提交都携带的静态调用方属性，写入 `context.attributes`；按次追加时在父 `RuntimeContext` 上用 `AgentSpawnTool.CTX_REMOTE_CONTEXT_ATTRIBUTES` 放 map |

### 远程流式详细度

| 档位 | 回传内容 |
| - | - |
| `STATUS` | 运行生命周期、工具调用起止、工具结果、确认请求 |
| `FULL`（默认） | `STATUS` 之外再加文本与思考增量 |
| `VERBOSE` | 远程 agent 发出的每一个事件——块边界、工具入参增量、**工具输出增量**、带 token usage 的模型调用、hint、agent 结果、自定义事件 |

希望父流在子 agent 是本地还是远程时表现一致，选 `VERBOSE`——这是唯一能让远程子 agent 的工具输出内容和 token 用量到达父代理的档位。它不作默认，是因为对只渲染文本的调用方这些事件纯粹是额外流量。

没有专属 wire 类型的事件以 `AGENT_EVENT` 传输，原始事件完整序列化在 `payload` 字段里，父代理解出来就是本地场景下同一个类，id、时间戳和 metadata 都在。不认识该字段的旧客户端仍读扁平字段，只是看不到这些透传事件。

### 远程授权

父的 DENY 权限规则随远程提交的 `context.deny_rules` 一并转发（与本地子 agent 的权限继承一致，可用 `inheritParentPermissions(false)` 关闭）。远程 agent 因工具确认暂停（`awaiting_confirm`）时：

- **父流式 + `remoteAskPolicy=PROPAGATE`**：向父的 `streamEvents()` 转发带非空 `source` 的 `RequireUserConfirmEvent`。通过 `POST /tasks/{id}/resume` 恢复，请求体 `decisions[{toolCallId, approved, reason}]`。
- **父非流式（`call`）或 `remoteAskPolicy=DENY`（默认）**：自动拒绝待确认项，工具结果附注 `remote tool confirmation(s) were auto-denied`。

等待确认期间任务状态保持 `RUNNING`（`awaitingConfirm=true`），所以 `wait_async_results` 等 barrier 会继续等待，直到任务 resume 并进入终态。

## 异步任务的存储位置

`WorkspaceTaskRepository` 按工作空间命名空间、父会话 ID、任务 ID 隔离本地任务句柄。按用户隔离的工作空间中，即使知道其他用户的任务 ID，也不能访问或取消其本地任务。共享范围（`AGENT`、`GLOBAL`）保留配置的共享行为。使用自定义文件系统和命名空间工厂时，应确保两者采用相同隔离边界。

后台任务状态默认写到 `workspace/agents/<parentAgentId>/tasks/<sessionId>.json`，因此：

- 共享存储模式（多副本）下任意节点都能读到任务状态；
- 任务执行**粘在创建节点**，但完成结果可被任意节点读到并正常推回父 agent；
- 取消可从任意节点调 `task_cancel`——执行节点轮询取消标记后中止。

本地后台任务不会在执行进程退出后自动恢复；孤儿任务清理器在心跳超时后（默认 **10 分钟**）将未结束的本地任务标记为 `FAILED`。远程 Agent Protocol 任务仍用自身轮询机制判断存活。

用 `RemoteFilesystem` 时，任务写入和读取会把最小的用户/会话路由信息登记到独立的 `BaseStore` 命名空间（`harness/task-scopes/...`）。宿主侧清理器通过持久化索引在重启后找到 `SESSION`、`USER` 作用域，并把失败状态写回原作用域。Agent 工具仍只能读调用者自己的作用域；索引不含任务正文，也不作为工作区文件暴露。清理时也会扫描长时间停机前的记录，因此存储保留策略应同时考虑任务记录和这些路由信息。

登记失败会记日志并在后续任务访问时重试；任务持久化保持尽力而为语义。升级前已有记录，在使用原作用域再次读写后才进入索引。支持 CAS 的存储会在写入冲突后重新检查心跳，避免过期清理判断覆盖并发更新。清理结果只报告本地执行已丢失，**不会自动重放任务**。

## 在 Plan Mode 下委派子 agent

本页称：父 agent 在 Plan Mode 时 spawn 的子 agent 会**自动继承只读限制**——在 spawn 时即被置入 Plan Mode，无法执行写操作。

> 与 [harness-plan-mode.md](harness-plan-mode.md) 冲突：那页明确标注这是「已知缺口」，子 agent **不会**自动继承只读限制，需要手工过滤 `tools` 或自行 `enablePlanMode()`。两页属于同一文档版本，接入前请以实际依赖行为验证。

## 子 Agent 流式

> 新代码用 `streamEvents()`（返回 `Flux<AgentEvent>`）。旧 `stream()` 系列（`Flux<Event>`）自 2.0.0 起 `@Deprecated(forRemoval = true)`。

父 agent 通过 `agent_spawn` / `agent_send` 同步调用本地子 agent 时，子 agent 的中间事件**实时转发**到父的 `streamEvents()` 流中。每个子事件带 `source` 字段，格式 `parentSessionId/agentId/subagentSessionId`（如 `"main/researcher/sub-1"`）；最后一段是子 agent 实例自己的 session id，所以同一父会话里多次实例化同种子 agent 也能区分。远程 Agent Protocol 子 agent 用 `parentSessionId/agentId/taskId`，并额外写入 `metadata.taskId`（`AgentEvent.METADATA_TASK_ID`）与 `metadata.parentSessionId`（`AgentEvent.METADATA_PARENT_SESSION_ID`）。

```
caller
  └─ parent.streamEvents(msg, ctx)
        │
        ├─ AGENT_START                            ← 父 agent 启动
        ├─ TEXT_BLOCK_DELTA …                     ← 父推理
        ├─ TOOL_CALL_START "agent_spawn"
        │
        │  [子 agent 创建]
        ├─ AGENT_START          (source="main/researcher/sub-1")  ← 子启动
        ├─ TEXT_BLOCK_DELTA …   (source="main/researcher/sub-1")  ← 子推理
        ├─ TOOL_CALL_START …    (source="main/researcher/sub-1")
        ├─ TOOL_RESULT_END …    (source="main/researcher/sub-1")
        ├─ AGENT_END            (source="main/researcher/sub-1")  ← 子结束
        │  [agent_spawn 返回，子结果作为 TOOL_RESULT 传给父]
        │
        ├─ TOOL_RESULT_END                        ← 父收到工具结果
        ├─ TEXT_BLOCK_DELTA …                     ← 父第二轮推理
        └─ AGENT_END                              ← 父结束
```

```java
parent.streamEvents(new UserMessage(message), ctx)
    .doOnNext(event -> {
        String src = event.getSource();
        String prefix = (src != null) ? "[" + src + "] " : "";

        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
            System.out.print(prefix + ((TextBlockDeltaEvent) event).getDelta());
        } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
            System.out.println(prefix + "[tool] " + ((ToolCallStartEvent) event).getToolCallName());
        } else if (event.getType() == AgentEventType.AGENT_START) {
            if (src != null) System.out.println("── 子 agent 启动: " + src);
        } else if (event.getType() == AgentEventType.AGENT_END) {
            if (src != null) System.out.println("── 子 agent 结束: " + src);
        }
    })
    .blockLast();
```

区分父子事件：

```java
events.filter(e -> e.getSource() == null).subscribe(…);                               // 只看父
events.filter(e -> e.getSource() != null).subscribe(…);                               // 只看子
events.filter(e -> e.getSource() != null && e.getSource().contains("researcher")).subscribe(…);
```

### SSE 转发

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String message,
                                          @RequestParam String sessionId) {
    RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
    return agent.streamEvents(new UserMessage(message), ctx)
            .map(event -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", event.getType().name());
                payload.put("id",   event.getId());
                if (event.getSource() != null) {
                    payload.put("source", event.getSource());
                }
                if (event instanceof TextBlockDeltaEvent delta) {
                    payload.put("delta", delta.getDelta());
                } else if (event instanceof ToolCallStartEvent start) {
                    payload.put("toolName", start.getToolCallName());
                }
                return ServerSentEvent.<String>builder()
                        .data(objectMapper.writeValueAsString(payload))
                        .build();
            });
}
```

### 行为边界

| 场景 | 是否实时流转发？ |
| - | - |
| `streamEvents()` + 同步本地子 agent（`timeout_seconds > 0`） | ✔ |
| `call()` 模式（非流式） | ✗（子结果以 `tool_result` 字符串返回） |
| `timeout_seconds = 0` 后台任务 | ✗（终态通过反向通知给父 agent 下一轮） |
| 远程子 agent + 父 `streamEvents()` + `remoteStreaming=true`（默认） | ✔ |
| 远程子 agent + 父 `call()` 或 `remoteStreaming=false` | ✗ |

### 错误处理

子 agent 内部出错时，框架捕获错误并写成一条 `TOOL_RESULT` 给父，**不会**把 `onError` 传播到父流——父流不会被子 agent 的失败打断。父流本身出错（如模型调用失败）按标准 Reactor 语义处理。

## Heron 现状

`AgentRuntimeConfig` 调用了 `.disableSubagents()`，且没有 `.subagent(...)` / `.subagentFactory(...)` / `channel(...)` / `distributedStore(...)`——子 agent 体系**完全关闭**，`HeronResearchTools` 是唯一的工具面。

要点：

- 要接子 agent，最小改动是去掉 `disableSubagents()` + 配 `.workspace(...)`（spec 文件默认扫 `workspace/subagents/`，任务状态落 `workspace/agents/<parentAgentId>/tasks/`）。也可以不写 spec，用 `.subagent(SubagentDeclaration.builder()...)` 编程式声明。
- 当前 `AgentRuntimeConfig` 也调用了 `.disableSessionPersistence()` 与 `.disableTranscript()`；而后台任务状态、`persistSession` 复用、跨节点 `subagentId` 恢复都依赖会话持久化能力，启用子 agent 时需一并评估这两条。
- 想走「同一轮并行跑多个子任务」的场景，`Toolkit` 默认 `parallel=true`，同步 spawn 天然并行；不需要额外配置。
