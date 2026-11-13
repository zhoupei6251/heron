# 消息与事件

> 上游：<https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event> · 快照：`_upstream/building-blocks-message-and-event.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：智能体通信，与前端流式数据传输

两种基础数据结构：

- **消息（`Msg`）** —— 智能体间通信与持久化的基本单元。每个 `Msg` 是一个完整对话轮次，存在上下文里并在智能体之间传递。
- **事件（`AgentEvent`）** —— 前端交互与流式传输的基本单元。携带增量进度（文本 token、工具调用片段、权限请求等），驱动实时界面与人工介入。

**关键不变式**：单次 `call` 产生的事件序列最终汇聚成**恰好一条** assistant `Msg`——完整消息状态始终可以从事件流还原。

## 消息

`io.agentscope.core.message.Msg` 代表一个对话轮次，内容是有序的类型化块列表。一条 assistant 消息对应智能体一次完整 `call` 周期（反复推理和执行，直到产出最终回复）。

### 字段

| 方法 | 类型 | 说明 |
| - | - | - |
| `getId()` | `String` | 唯一消息标识 |
| `getName()` | `String` | 发送方名称（可空） |
| `getRole()` | `MsgRole` | `USER` / `ASSISTANT` / `SYSTEM` / `TOOL` |
| `getContent()` | `List<ContentBlock>` | 有序内容块（不可变） |
| `getMetadata()` | `Map<String, Object>` | 任意键值元数据 |
| `getTimestamp()` | `String` | `yyyy-MM-dd HH:mm:ss.SSS` |
| `getUsage()` | `ChatUsage` | Token 用量（仅 assistant） |
| `getGenerateReason()` | `GenerateReason` | `MODEL_STOP` / `TOOL_SUSPENDED` / `REASONING_STOP_REQUESTED` / `ACTING_STOP_REQUESTED` / `ALL_TOOLS_DENIED` / `INTERRUPTED` / `MAX_ITERATIONS` |

### 内容块

均位于 `io.agentscope.core.message`：

| 块类型 | 说明 | 允许出现在 |
| - | - | - |
| `TextBlock` | 纯文本 | USER、ASSISTANT、SYSTEM |
| `DataBlock` | 二进制数据（图片/音频/视频），base64 或 URL；**统一替代旧的 Image/Audio/VideoBlock** | USER、ASSISTANT |
| `DocumentBlock` | 文档输入（PDF/Word/纯文本），来源可为服务商文件 ID（`FileIdSource`，带 `provider` + `file_id`）、URL 或 base64 | USER |
| `ImageBlock` / `AudioBlock` / `VideoBlock` | 旧版具体多媒体块（仍兼容，新代码用 `DataBlock`） | USER |
| `ThinkingBlock` | 模型推理过程（思维链） | ASSISTANT |
| `ToolUseBlock` | 工具调用，含 `id` / `name` / `input` / `state`（`ToolCallState`） | ASSISTANT |
| `ToolResultBlock` | 工具执行结果，含 `state`（`ToolResultState`） | ASSISTANT |
| `HintBlock` | 以用户上下文形式注入循环的指令 | ASSISTANT |

> **角色约束在构造时强制**：`USER` 只能含 text/data/document/image/audio/video；`SYSTEM` 只能含 `TextBlock`；`ASSISTANT` 可含所有类型。

### 创建

按 role 固定的子类：`UserMessage` / `AssistantMessage` / `SystemMessage` / `ToolResultMessage`。content 是普通字符串时自动包成 `TextBlock`。

```java
// 用户消息 —— 文本
UserMessage userText = new UserMessage("user", "这张图片里有什么？");

// 多模态用户消息
UserMessage userMulti =
        new UserMessage(
                "user",
                TextBlock.builder().text("描述这张图片：").build(),
                DataBlock.builder()
                        .source(Base64Source.builder()
                                .data("...")
                                .mediaType("image/png")
                                .build())
                        .build());

// 系统消息 —— 仅文本
SystemMessage systemMsg = new SystemMessage("system", "你是一个有用的助手。");

// 助手消息 —— 允许所有块类型
AssistantMessage assistantMsg = new AssistantMessage("agent", "结果如下...");

// 需要 metadata / timestamp / usage / generateReason 时用 builder
UserMessage msg = UserMessage.builder().name("user").textContent("Hello").build();
```

### 取内容

| 方法 | 返回值 |
| - | - |
| `getTextContent()` | 所有 `TextBlock` 按 `\n` 拼接；无文本块返回空串 |
| `getContentBlocks(Class<T>)` | 按类型过滤的块列表 |
| `getFirstContentBlock(Class<T>)` | 首个匹配块，无则 null |
| `hasContentBlocks(Class<T>)` | 是否存在该类型块 |

```java
String text = msg.getTextContent();
List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
if (msg.hasContentBlocks(ToolResultBlock.class)) { /* ... */ }
```

## 事件

`io.agentscope.core.event.AgentEvent` 系列表示增量进度：文本 token 到达、工具调用逐步构建、结果流式返回。每个事件都轻量且自包含。

### 生命周期与关联键

每个事件带 `getReplyId()` 关联正在构建的消息；一次回复内用 `getBlockId()` 或 `getToolCallId()` 作为内容块生命周期的关联键。事件遵循 **start → delta → end**：

```
AgentStartEvent
  推理阶段
    ModelCallStartEvent
      TextBlockStartEvent → TextBlockDeltaEvent ×N → TextBlockEndEvent          (blockId)
      DataBlockStartEvent → DataBlockDeltaEvent ×N → DataBlockEndEvent          (blockId)
      ToolCallStartEvent  → ToolCallDeltaEvent ×N  → ToolCallEndEvent           (toolCallId)
    ModelCallEndEvent
  执行阶段
    ToolResultStartEvent
      ToolProgressEvent ×N（来自 ToolEmitter）
      ToolResultTextDeltaEvent ×N
      ToolResultDataDeltaEvent ×N
      ToolResultEndEvent                                                       (toolCallId)
AgentEndEvent
```

`blockId` 是 `replyId` 作用域内的关联键，不要求全局唯一；某类块在一次回复最多一个生命周期时，实现可用稳定类型标识作 `blockId`。

### 事件公共方法

| 方法 | 类型 | 说明 |
| - | - | - |
| `getId()` | `String` | 唯一事件标识 |
| `getCreatedAt()` | `String` | ISO 8601 时间戳 |
| `getType()` | `AgentEventType` | 事件类型枚举 |
| `getSource()` | `String` | 事件来源路径。顶层 agent 为 `null`；本地子 agent 为 `parentSessionId/agentId/subagentSessionId`（如 `"main/researcher/sub-1"`），远程子 agent 为 `parentSessionId/agentId/taskId` |
| `getMetadata()` | `Map<String, Object>` | 可选元数据；远程子 agent 转发时写入 `AgentEvent.METADATA_TASK_ID` 与 `AgentEvent.METADATA_PARENT_SESSION_ID` |

### 事件类型

**生命周期**

| 事件 | 关键方法 |
| - | - |
| `AgentStartEvent` | `getReplyId()`、`getSessionId()`、`getName()`、`getRole()`（默认 `"assistant"`） |
| `AgentEndEvent` | `getReplyId()` |
| `ExceedMaxItersEvent` | `getReplyId()` —— 达到最大推理-执行迭代次数 |
| `RequestStopEvent` | 中间件或工具发起的提前停止 |

**文本流式**：`TextBlockStartEvent` / `TextBlockDeltaEvent`（`getDelta()`）/ `TextBlockEndEvent`，均带 `getReplyId()` + `getBlockId()`。

**思考流式**：`ThinkingBlockStartEvent` / `ThinkingBlockDeltaEvent` / `ThinkingBlockEndEvent`，结构与文本对应，用于思维链。

**数据流式**：`DataBlockStartEvent`（`getMediaType()` 返回 MIME）/ `DataBlockDeltaEvent`（`getData()` 返回增量 base64）/ `DataBlockEndEvent`。

**工具调用流式**

| 事件 | 关键方法 |
| - | - |
| `ToolCallStartEvent` | `getReplyId()`、`getToolCallId()`、`getToolCallName()` |
| `ToolCallDeltaEvent` | `getDelta()` —— JSON 参数片段 |
| `ToolCallEndEvent` | 参数完成 |

**工具结果流式**

| 事件 | 说明 |
| - | - |
| `ToolResultStartEvent` | 工具开始执行（带 `toolCallId` / `toolCallName`）；串行批次中在该工具**轮到执行时**发送，不是批次受理时 |
| `ToolProgressEvent` | `ToolEmitter` 发布的中间进度，**仅展示，不发 LLM** |
| `ToolResultTextDeltaEvent` | 工具返回值的增量文本；`getDelta()` 是 **LLM 实际收到的结果** |
| `ToolResultDataDeltaEvent` | 二进制/结构化输出，含 `mediaType` / `data` / `url` |
| `ToolResultEndEvent` | 最终结果就绪；`getState()` ∈ `SUCCESS` / `ERROR` / `INTERRUPTED` / `DENIED` / `RUNNING` |

> 结果按完成顺序到达（用 `replyId` + `toolCallId` 关联），但 PostActing、上下文写入与下一轮模型调用仍等整批完成，结果保持原调用顺序。

**模型调用**：`ModelCallStartEvent`（带 `modelName`）/ `ModelCallEndEvent`（带 `inputTokens` / `outputTokens`）。

**人工介入**

| 事件 | 说明 |
| - | - |
| `RequireUserConfirmEvent` | 暂停等用户确认；`getToolCalls()` 返回待确认 `ToolUseBlock` 列表 |
| `RequireExternalExecutionEvent` | 暂停等外部执行；`getToolCalls()` |
| `UserConfirmResultEvent` | 用户给出确认结果，`getConfirmResults()`；`replyId` 与当初暂停的 `RequireUserConfirmEvent` 相同 |
| `ExternalExecutionResultEvent` | 外部执行回填，`getToolResults()`；`replyId` 与 `RequireExternalExecutionEvent` 相同 |
| `AllToolsDeniedEvent` | 最近一轮推理产出的工具被**全部**拒绝；`getDeniedToolCalls()`。经 `onActing` 链发出，middleware 可据此发 `RequestStopEvent`；无人处理时默认继续下一轮（向后兼容） |

**子 Agent**：`SubagentExposedEvent`（`agent_spawn(expose_to_user=true)` 暴露的子 agent），带 `getSubagentId()` / `getAgentId()` / `getSessionId()` / `getLabel()`，SSE 消费端可据此渲染新会话入口。

## 从事件流重建消息

事件与消息是同一数据的两种视图：按 `replyId` / `blockId` / `toolCallId` 聚合即可还原出完整 `AssistantMessage`。参考实现见 `agentscope-core` 的 `agent/StreamingHook.java` 与 `agentscope-examples/.../streaming/AgentEventStreamExample.java`。

```java
StringBuilder accumulated = new StringBuilder();

agent.streamEvents(userMsg)
        .doOnNext(event -> {
            if (event instanceof AgentStartEvent start) {
                System.out.println("[start replyId=" + start.getReplyId() + "]");
            } else if (event instanceof TextBlockDeltaEvent delta) {
                accumulated.append(delta.getDelta());
            } else if (event instanceof ToolCallStartEvent tc) {
                System.out.println("[tool] " + tc.getToolCallName());
            } else if (event instanceof ToolResultEndEvent end) {
                System.out.println("[tool result state=" + end.getState() + "]");
            } else if (event instanceof AgentEndEvent end) {
                System.out.println("\n[end] full text:\n" + accumulated);
            }
        })
        .blockLast();
```

> 这种设计让部署更灵活：后端用 SSE 把事件流推给前端，前端在客户端重建消息；连接中断后从任意检查点重放事件序列也能精确恢复状态。

Spring WebFlux SSE 形态参考 `streaming/StreamingWebExample.java`。

## Heron 现状

- 当前用的是**阻塞式** `agent.call(...)`（`AgentRuntimeConfig` 里只建 `HarnessAgent`，没有 `streamEvents`），所以事件流这条能力完全没用到，前端拿不到 agent 的逐步进度。
- 若要把研究/生产过程实时显示到 `heron-ui`（例如数据看板线的「机会发现」进度条），入口是 `agent.streamEvents(msgs, ctx)` 转 SSE；关键关联键是 `replyId` + `toolCallId`，不要用 `ToolProgressEvent` 当工具结果（那是展示用的，见 [tool.md](tool.md#工具进度流式输出)）。
- `Msg` 的角色约束在构造期强制，Heron 侧若要自己拼提示词消息（例如把已有草稿历史塞回对话），注意 `SYSTEM` 消息只能放 `TextBlock`。
