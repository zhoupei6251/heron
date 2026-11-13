# 工具（Tool）

> 上游：<https://java.agentscope.io/v2/zh/docs/building-blocks/tool> · 快照：`_upstream/building-blocks-tool.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：定义、注册并管理 agent 可调用的能力

Tool 是 agent 与外部世界交互的方式。每个 tool 通过 JSON Schema 暴露给 LLM，agent 通过统一接口调用。

默认 `JacksonJsonCodec` 只读一个完整 JSON 值，允许尾随空白；额外的 JSON 值或尾随内容抛 `JsonException`，此时参数校验回退到结构化 input。

## 三个概念

| 概念 | 是什么 |
| - | - |
| **Tool** | 实现 `AgentTool` 接口（通常继承 `ToolBase`）的对象，或在普通类方法上标 `@Tool` 的对象（reflective function tool，由 `Toolkit#registerTool(Object)` 反射注册） |
| **Toolkit** | 容器：注册 tool / MCP 客户端 / skill，向模型暴露 JSON schema，把调用分发到对应 tool |
| **Tool Group** | 一组带名字的 tool / MCP / skill，可整体激活或停用；agent 运行时通过内置 meta tool 切换 |

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new TodoTools());
toolkit.registerTool(new MyCustomTools());
```

只调 `registerTool(Object)` 时，被注册对象上所有 `@Tool` 方法进入特殊的 `"basic"` 组——**该组始终激活**。

## Java Tool

### ToolBase 属性

| 方法 | 类型 | 说明 |
| - | - | - |
| `getName()` | `String` | 暴露给 agent 的 tool 名 |
| `getDescription()` | `String` | 面向 agent 的描述 |
| `getParameters()` | `Map<String, Object>` | 参数 JSON Schema |
| `isConcurrencySafe()` | `boolean` | 是否可并发调用 |
| `isReadOnly()` | `boolean` | 是否只读、无副作用 |
| `isExternalTool()` | `boolean` | 为 `true` 时执行委派给外部 |
| `isStateInjected()` | `boolean` | 为 `true` 时框架注入 `AgentState` 参数 |
| `isMcp()` / `getMcpName()` | `boolean` / `String` | 是否来自 MCP 服务 |

接入执行与权限流程：

| 方法 | 必需 | 说明 |
| - | - | - |
| `checkPermissions(toolInput, context)` | 是 | 执行前运行时权限检查，返回 `Mono<PermissionDecision>` |
| `matchRule(ruleContent, toolInput)` | 否 | 权限系统的自定义规则匹配，返回 `boolean` |
| `generateSuggestions(toolInput)` | 否 | 生成建议权限规则，返回 `List<PermissionRule>` |
| `callAsync(param)` | 否 | 执行逻辑，返回 `Mono<ToolResultBlock>`；外部执行 tool 不需要 |

### 内置 Tool

| Tool | 说明 | 只读 |
| - | - | - |
| `TodoTools.todoWrite` | 维护当前会话的结构化任务列表（**全列表替换**语义） | 否 |

> Toolkit 在有额外 tool group 或 skill 时自动注册 `reset_tools` 与 `load_skill_through_path`，无需手动实例化。

### 注解式（最轻量）

```java
public class SimpleTools {

    @Tool(
            name = "get_current_time",
            description = "Returns the current time in a given IANA timezone.",
            readOnly = true,
            concurrencySafe = true)
    public String getCurrentTime(
            @ToolParam(name = "timezone", description = "IANA timezone, e.g. Asia/Shanghai")
                    String timezone) {
        return LocalDateTime.now(ZoneId.of(timezone))
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new SimpleTools());
```

`@Tool` 属性：

| 属性 | 说明 |
| - | - |
| `name` | tool 名（默认取方法名） |
| `description` | 面向 agent 的描述 |
| `readOnly` / `concurrencySafe` | 默认均 `false` |
| `stateInjected` | 是否注入 `AgentState` 作为额外参数 |
| `dangerousFiles` / `dangerousDirectories` | 追加自定义危险路径 |
| `converter` | 自定义返回值 → `ToolResultBlock` 的转换器 |

**返回结果语义**：

- 成功 `ToolResultBlock.success(text)`，失败 `ToolResultBlock.error(message)`；**不要用文本前缀判断失败**。
- `ToolResultBlock.text(text)` 默认是 `RUNNING`；要表达终态用 `withState(...)`。
- 注解工具的 `void` / `Void` 返回值会返回 `(completed with no output)`，避免静默成功被误认为缺失结果；普通 `null` 仍是 `null`。
- Anthropic formatter 把 `ERROR` / `DENIED` / `INTERRUPTED` 映射为 `is_error: true`。

### 继承 ToolBase

需要自定义权限策略、外部执行或更复杂 schema 时：

```java
public class WebSearchTool extends ToolBase {

    public WebSearchTool() {
        super(ToolBase.builder()
                .name("WebSearch")
                .description("Search the web for information on a given query.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "The search query.")),
                        "required", List.of("query")))
                .readOnly(true)
                .concurrencySafe(true));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        return Mono.just(PermissionDecision.allow("Web search is read-only."));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String query = (String) param.getInput().get("query");
        return doSearchAsync(query).map(text -> ToolResultBlock.builder()
                .id(param.getId()).name(getName())
                .output(List.of(TextBlock.builder().text(text).build()))
                .build());
    }
}
```

### 外部执行 Tool（HITL 基础）

把执行委派到 agent 运行时之外（人工操作员或外部系统）。`externalTool(true)`，**不必实现 `callAsync`**。agent 调用时发 `RequireExternalExecutionEvent` 并暂停；下次调用回传匹配的 `ToolResultBlock` 后发同 `replyId` 的 `ExternalExecutionResultEvent` 并继续。

```java
super(ToolBase.builder()
        .name("HumanApproval")
        .description("Request human approval for a sensitive operation.")
        .inputSchema(...)
        .readOnly(false)
        .concurrencySafe(true)
        .externalTool(true));
```

### 重名冲突与显式替换

名字是下游一切的查找键（schema 生成、权限规则、Tool Group、确认处理）。注册一个已被**另一个** tool 占用的名字会立刻抛 `IllegalStateException`，不静默覆盖。

```java
toolkit.registerTool(new LocalTools());   // @Tool(name = "dup", ...)
toolkit.registerTool(new OtherTools());   // 同名不同 tool
// IllegalStateException: Tool 'dup' is already registered and will not be silently replaced.
```

- MCP tool 走同一道校验；要共存就配 `toolNamePrefix` 或改名。
- **重复注册同一个逻辑工具是幂等的**（同一对象 + 同一方法、MCP 客户端重连刷新、`registerSchema` 声明完全相同的内容），按刷新放行；同名但内容变了算冲突。
- 有意覆盖用 `toolkit.replaceAgentTool(newTool)`——这是唯一入口，其余注册路径都 fail-fast。
- `Toolkit.copyExcludingTools(instances)` 复制时只排除指定实例，保留分组关系与配置。

## 接收 Context

`agent.call(msgs, runtimeContext)` 传入的 `RuntimeContext` 自动透传到本次 reply 内每次工具调用。

`@Tool` 方法里**没有 `@ToolParam` 标注**的参数视为「需框架注入」，按优先级解析：

| 参数类型 | 注入来源 |
| - | - |
| `ToolEmitter` | 流式中间产物 emitter（无配置时 no-op） |
| `Agent` | 当前 agent 实例 |
| `AgentState` | 本次 call 的 per-session 状态 |
| `RuntimeContext` | 本次 per-call 上下文 |
| `ToolExecutionContext` | `runtimeContext.asToolExecutionContext()`（兼容层，已 deprecated） |
| 其它自定义 POJO | `runtimeContext.get(ParamType.class)` |

判定「用户自定义 POJO」：没有 `@ToolParam`、不是基本类型、不是 `ContentBlock` / `Msg`、不在 `java.*` / `javax.*` 包下。

```java
public record UserContext(String username, String locale) {}

@Tool(name = "greet", description = "Greet the user with a custom greeting")
public String greet(
        @ToolParam(name = "greeting", description = "Greeting word") String greeting,  // 模型提供
        UserContext userCtx) {                                                        // 框架注入
    return greeting + ", " + userCtx.username() + "!";
}

// 调用方按类型注册，同款实例分发到所有需要它的 tool
RuntimeContext ctx = RuntimeContext.builder()
        .put(UserContext.class, new UserContext("alice", "en"))
        .userId("alice").build();
```

模型不需要把 `userCtx` 写进 JSON，schema 里也不会出现它。

`ToolBase.callAsync` 里用 `param.getRuntimeContext()` 取（`getAgent()` / `getInput()` / `getEmitter()` / `getToolUseBlock()` 也在 `ToolCallParam` 上）。

> `RuntimeContext` 的 string 层（`put(String, Object)`）是同一次 call 内 **middleware 与 tool 的临时通信通道**：middleware 在 `onActing` / `onReasoning` 写入，tool 注入 `RuntimeContext` 读取。

### 工具进度流式输出

长任务用 `ToolEmitter.emit()` 发中间进度，以 `ToolProgressEvent` 出现在事件流中——**仅用于展示，不发给 LLM**。返回值才是模型收到的东西（走 `ToolResultTextDeltaEvent` / `ToolResultDataDeltaEvent`）。

```java
@Tool(name = "run_task", description = "Run a long-running task")
public String runTask(ToolEmitter emitter) {
    emitter.emit(ToolResultBlock.text("正在扫描 3 个目录..."));
    return "3 files found";   // 只有这个返回值发给 LLM
}
```

> 从事件流重建结果的客户端必须累积 `ToolResult*DeltaEvent` 并**忽略** `ToolProgressEvent`；混用会让进度文本进入工具消息并在下一轮送回模型。

## MCP

接入任意 MCP 兼容的工具提供方，框架处理协议协商、工具发现与结果转换。三种连接方式：**STDIO**（本地进程 stdin/stdout）、**SSE**、**Streamable HTTP**。

MCP tool 在 toolkit 中命名为 `mcp__{server_name}__{tool_name}`；带 `readOnlyHint` 的 tool 会被权限系统自动放行。

```java
McpClientWrapper filesystem =
        McpClientBuilder.stdio()
                .name("filesystem").command("mcp-server-filesystem")
                .args("--root", "/my/project").build();

McpClientWrapper weather =
        McpClientBuilder.streamableHttp()
                .name("weather").url("https://api.weather.com/mcp")
                .header("Authorization", "Bearer xxx").build();

Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(filesystem).block();
```

## Skill

Skill 是基于 markdown 的指令集，不用写新工具代码就能扩能力：一个目录 + 带 frontmatter 的 `SKILL.md`。

**Skill 不能被直接调用。** agent 先用查看器工具 `load_skill_through_path` 读指令，再用现有 tool 执行。

```java
ReActAgent agent = ReActAgent.builder()
        .name("SkillCreator").sysPrompt("...").model(model)
        .skillRepository(new FileSystemSkillRepository(Paths.get("/path/to/skills"), false))
        .build();
```

- 多次 `skillRepository(...)` 按顺序追加（低 → 高优先级），同名后者覆盖；整批替换用 `skillRepositories(List<...>)`。
- 查看器参数：`skillId`（枚举）+ `path`（传 `"SKILL.md"` 或声明的精确资源路径如 `"references/guide.md"`，**不要传 `.` / 目录 / 绝对路径**）。
- 每次成功加载会**激活与该 skill 绑定的 tool group**，本轮余下时段可用；`path` 不存在时返回错误并列出可用资源。
- 初始化阶段 Toolkit 自动注册 `load_skill_through_path`（无分组、始终可见），并把「skill 名 + 描述」注入 system prompt。

### Skill 执行脚本需要 shell 工具

Skill 只给指令，执行靠已有 tool。要跑 `scripts/run.py` 这类脚本：

- **`ReActAgent`** —— 注册 `ShellCommandTool`（白名单 + 可选审批回调）、`ReadFileTool`、`WriteFileTool`。
  - 白名单内且**不含 Shell 操作符或展开语法**的命令才可直接执行；其他需审批回调明确批准，未配置回调则拒绝。
  - **空白名单（含 `new ShellCommandTool()`）现在也需要审批**。
  - `./script` 这类相对路径可执行程序必须同时满足「不越出当前目录」和「匹配白名单」；路径安全本身≠执行权限。
  - Windows 上任何 `%` 或 `!` 都要审批（含 `echo 50% done` 这类字面量）；可执行程序用双引号包围时，命令中任意位置出现 `& | < > ( ) ^` 也要审批。
  - **白名单不能替代沙箱**：允许 `python3` 等于允许任意代码，不可信脚本应在隔离沙箱里跑。
- **`HarnessAgent`** —— 自带 workspace 感知的 shell 与文件工具（`execute`、`read_file`、`write_file` 等），无需额外注册。

### Skill + ToolGroup：按需披露

`SkillToolGroup` 把一组 tool 绑到某个 skill：加载该 skill 时 group 自动激活，未加载时 tool 不出现在 schema 里。

```java
toolkit.createSkillToolGroup("analysis-tools", "Data analysis tools", false, "data-analysis");
toolkit.registration().tool(new AnalysisTools()).group("analysis-tools").apply();

ReActAgent agent = ReActAgent.builder()
        .toolkit(toolkit).skillRepository(skillRepo).enableMetaTool(true).build();
```

## Tool Group 与 meta tool

`ToolGroup` = 带名字的 tool / MCP / skill 集合，可整体激活停用，保持上下文聚焦。

```java
ToolGroup database =
        new ToolGroup("database", "Tools for database operations.",
                ToolGroupScope.SESSION, /* active = */ false);
database.addTool("db_query");
database.addTool("db_migrate");
toolkit.registerTool(new DatabaseTools());
toolkit.registerToolGroup(database);

ReActAgent agent = ReActAgent.builder()
        .name("router").toolkit(toolkit).enableMetaTool(true).build();
```

有至少一个非 basic group 且 `enableMetaTool(true)` 时，Toolkit 自动注册 `reset_tools`。运行时行为：

- `"basic"` 组始终暴露，meta tool 不影响。
- 每次调用 `reset_tools` **整体覆盖**激活集合——任何未显式置 `true` 的非 basic group 都会被停用。
- 本次切为激活的 group，其 description 与使用说明会被拼进返回值，教 agent 怎么用。
- 未激活 group 的 tool 不出现在 schema 中。

> **Meta tool 的输入是最终状态，不是增量。**

## 按需搜索工具

工具目录很大时启用 `ToolSearchMiddleware`，它注册只读工具 `tool_search(query, limit)`（`limit` 默认 5）。模型初始只看到搜索工具和配置为始终可见的工具；命中后这些工具在后续模型调用中可用，**搜索本身不执行工具**。

```java
ToolSearchMiddleware search = new ToolSearchMiddleware(toolkit);
ReActAgent agent = ReActAgent.builder()
        .name("assistant").model(model).toolkit(toolkit).middleware(search).build();
```

- 默认 `KeywordToolSearchStrategy` 无额外依赖，按关键词匹配名称与描述，名称权重更高；可实现 `ToolSearchStrategy` 接语义检索。
- 结果写入会话前会去重、限量；不在候选集的名称被拒绝；候选集遵守当前激活 group 与请求级覆盖规则。
- 完整构造 `new ToolSearchMiddleware(toolkit, strategy, Set.of("reset_equipped_tools"), false)`：最后参数 `true`（默认）启用原生加载协议（OpenAI Responses / Anthropic 的原生 search），不支持时设 `false` 走普通函数调用。
- 发现结果存在 `ToolContextState`，可从状态存储恢复；搜索不激活 group、不改权限。

## 工具调用拦截器

`ToolCallInterceptor` 是 Toolkit 层、单次调用级的参数拦截点。它在 **JSON Schema 校验与预置参数合并之后、会话分发记录与真实执行之前**运行，因此能看到工具将收到的确切参数与调用者身份。

表达**权限系统覆盖不了的参数级业务规则**：工具本身允许调用，但*本次*的 `file_path` 是否在该用户沙箱内、这条 SQL 是否超出数据范围。工具级策略仍归权限引擎；批次级、事件流关注点归 middleware 的 `onActing`。

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
        .parallel(true)
        .interceptor(new AuditInterceptor())                       // 追加，可重复
        .interceptor(new PathSandboxInterceptor(
                userId -> Path.of("/data/workspaces", userId)))
        .build());
```

- `interceptor(...)` 追加；`interceptors(List)` **覆盖**此前全部。
- 按 `order()` **降序稳定排序**（数值越大越靠外层），排序后运行期不可变。
- Toolkit 被并发会话共享，拦截器实现必须无状态或线程安全；可变策略放拦截器内部状态。

钩子是洋葱模型：

```java
public Mono<ToolResultBlock> intercept(
        ToolInvocation invocation,
        Function<ToolInvocation, Mono<ToolResultBlock>> next) {
    if (!invocation.toolName().equals("write_text_file")) {
        return next.apply(invocation);            // 非目标工具，直接放行
    }
    String userId = invocation.runtimeContext().getUserId();
    if (userId == null || userId.isBlank()) {
        return deny("no authenticated user context");   // 身份缺失即拒绝
    }
    Path root = workspaceRootResolver.apply(userId).toAbsolutePath().normalize();
    Path resolved = root.resolve(invocation.input().get("file_path").toString()).normalize();
    if (!resolved.startsWith(root)) {
        return deny("path must stay inside your workspace; use relative paths");
    }
    Map<String, Object> rewritten = new HashMap<>(invocation.input());
    rewritten.put("file_path", resolved.toString());
    return next.apply(invocation.withInput(rewritten))
            .doOnSuccess(result -> audit.record(userId, resolved, result));
}
```

可执行的动作：

| 动作 | 表达 | 效果 |
| - | - | - |
| 放行 | `next.apply(invocation)` | 继续链 / 执行 |
| 策略拒绝 | `ToolResultBlock.text(msg).withState(ToolResultState.DENIED)` | 不执行，结果回模型自我纠正，自动记 `denied` 决策 |
| 调用非法 | `ToolResultBlock.error(msg)` | 「本次调用失败」，不产生 denied 记录 |
| 改写参数 | `next.apply(invocation.withInput(newInput))` | 工具收到改写后参数（不做 Schema 重校验） |
| 审计 | `next.apply(invocation).doOnSuccess(...)` | 观察结果不影响执行 |
| 挂起（HITL） | 抛 `ToolSuspendException` | 转为 `suspended` 结果 |

语义要点：

- **改写发生在权限批准之后**。权限引擎判定的是模型原始输入；改写后若最终值需用户确认，应挂起人审而非静默改写。拦截器拒绝已批准的调用属预期行为。
- **拒绝消息要面向模型可行动**（"请使用工作区内的相对路径"），带拦截器身份前缀（如 `PathSandbox:`），不泄露服务端信息。
- 路径守卫要硬化：可被调用者控制的 id 先校验格式再用作路径组件；已存在路径用 `toRealPath()` 复检（`normalize()` 不消解符号链接、Windows 8.3 短名与 ADS，`Path#startsWith` 区分大小写）；改写时不要把服务端绝对根路径回显给模型。
- **拦截器绝不能阻塞**：`Toolkit#callTool` 直连路径无超时包装，阻塞 IO 用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 卸载。
- **外部工具不经过拦截器**（`externalTool=true` / `SchemaOnlyTool` 在链前短路返回 `suspended`）；框架内部合成的 `generate_response` 也不经过。
- `Toolkit#copy()`（Harness 子代理即经此构建）会携带拦截器；全新 Toolkit 构建的子代理没有，除非显式注册。
- 工具体内再委托其他工具不会被二次拦截——按外层工具名分发守卫。

## 派发前检查

需要在排队工具开始前再核验本轮状态（例如别的工具已报基础设施故障）：

```java
AtomicBoolean stopped = new AtomicBoolean();
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
        .beforeToolCall(param -> {
            if (stopped.get()) {
                throw new IllegalStateException("Run stopped: infrastructure unavailable");
            }
        })
        .build());
```

- 必须同步且不阻塞，在每次订阅和重试时、紧邻本地 `callAsync` 前执行。
- 收到的是**最终执行参数**（含拦截器改写结果）；外部工具虽不过拦截器，也会在返回挂起结果前执行此检查。
- 拒绝用普通 `RuntimeException`；**不能抛 `ToolSuspendException`**（那是挂起语义）。
- 检查位于派发日志持久化**之后**，所以被拒时 `tool/dispatch` 仍表示派发意图，最终结果记失败。
- `Toolkit.copy()` 保留配置；跨运行共享 Toolkit 时应从 `RuntimeContext` 取状态，别捕获共享的停止标记。

## 请求级工具视图与会话隔离

构建 agent 时复制工具注册表与元数据；**工具实例按引用共享**，自定义工具需自行保证并发安全。

调用期间不要改共享 Toolkit 来注入外部工具或切 group。用 `ToolRequestConfig` 描述本次请求的工具视图：

```java
ToolRequestConfig tools = new ToolRequestConfig(
        Map.of(schema.getName(), new SchemaOnlyTool(schema)),
        ToolMergeMode.MERGE_EXTERNAL_PRIORITY);
RuntimeContext ctx = RuntimeContext.builder()
        .userId(userId).sessionId(sessionId)
        .toolRequestConfig(tools).build();
agent.streamEvents(messages, ctx).subscribe(this::handleEvent);
```

- 外部工具只含 schema，执行时返回 suspended，由调用方执行后提交结果。
- 同名外部工具覆盖后端工具；`EXTERNAL_ONLY` 隐藏全部后端工具（即使外部列表为空）；`AGENT_ONLY` 不允许同时配外部工具。
- 工具组激活状态存在会话的 `ToolContextState`；需要请求视图时用 `getTool(name, config)`，无配置的 `getTool(name)` 查原始注册表。
- 按用户/会话加载 skill 的仓库实现 `io.agentscope.core.skill.repository.RuntimeContextSkillRepository`。

## 工具执行生命周期回调

`ToolExecutionCallback` 提供 `onStart(call)` / `onProgress(call, chunk)` / `onResult(call, result)`。执行器在工具真正开始执行时发 `onStart`；`onResult` 每次调用至多一次，先于有序汇总——快工具不必等慢工具。`Toolkit.callToolsWithCallback(...)` 转发全部通知，原 `callTools(...)` 签名保持兼容。

回调实现必须线程安全且不能阻塞；取消或基础设施异常可能没有最终结果。callback 只发事件，不创建 `Msg`、不改上下文。
