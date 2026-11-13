# Middleware

> 上游：<https://java.agentscope.io/v2/zh/docs/building-blocks/middleware> · 快照：`_upstream/building-blocks-middleware.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：在 agent 生命周期的关键位置拦截并扩展行为

Middleware 让你不改 agent / model 代码，就往执行流程的关键位置注入逻辑（日志、追踪、输入改写、访问控制等）。

## 五个 hook 与两种类型

| 位置 | 类型 | 说明 |
| - | - | - |
| `onAgent` | Onion | 包裹一次完整 reply 流程，覆盖其中所有 ReAct 轮次、工具执行与最终输出 |
| `onReasoning` | Onion | 包裹一轮 ReAct 的推理步骤（输入组装 → 模型调用 → 流式解码） |
| `onActing` | Onion | 包裹一次工具调用的执行 |
| `onModelCall` | Onion | 包裹一次底层 `ChatModel` API 调用，最贴近模型 |
| `onSystemPrompt` | Transformer | 每次组装 system prompt 时触发；多个 middleware 串行接力，每个把上一个的输出再做一次变换 |

- **Onion** —— 包裹下一层 handler，可在 `next.apply(input)` 前后插逻辑、观察中间事件流。
- **Transformer** —— 串成流水线，前一个输出即后一个输入，没有「内层」概念。

嵌套关系：

```
onAgent/
└── ReAct loop（每一轮）/
    ├── onReasoning/
    │   ├── onSystemPrompt（组装 system prompt）
    │   └── onModelCall（模型 API 调用）
    └── onActing（每次工具调用）
```

> `onActing` 只包裹 agent 运行时**内部**的工具执行；external execution 在 agent 外部执行的工具不会被它追踪到。

## 装备

一组 hook 装在一个 `MiddlewareBase` 实现里——同一个类可以实现 5 个位置的任意子集（其余位置默认 `next.apply(input)`）。

```java
ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .sysPrompt("You are a helpful assistant.")
                .model(model)
                .toolkit(toolkit)
                .middlewares(List.of(new OtelTracingMiddleware()))
                .build();
```

`middleware(...)`（单数）加一个；`middlewares(...)` 接 `List<? extends MiddlewareBase>`，未实现的位置自动跳过、零开销。

## 内置 Middleware

### OtelTracingMiddleware

`io.agentscope.core.tracing`，为全生命周期接入 [OpenTelemetry GenAI 语义](https://opentelemetry.io/docs/specs/semconv/gen-ai/)。在 `onAgent`、`onModelCall`、`onActing` 三处打点，生成层级 span：

- `invoke_agent <name>` —— 整次 reply
- `chat <model>` —— 每次模型 API 调用
- `execute_tool <name>` —— 每次工具执行

未配置 OpenTelemetry SDK（只剩默认 no-op provider）时所有 hook 短路到 `next.apply(input)`，几乎零开销。

它从进程级 `GlobalOpenTelemetry` 读配置。要自己导出 span，还需引 SDK + OTLP exporter，并用 BOM 对齐版本（与上游一致的 `1.61.0`）：

```xml
<properties>
    <opentelemetry.version>1.61.0</opentelemetry.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-bom</artifactId>
            <version>${opentelemetry.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-sdk</artifactId>
    </dependency>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
</dependencies>
```

每个进程只构建并注册一次 SDK（`OTEL_EXPORTER_OTLP_AUTHORIZATION` 可存 `Basic <base64>` 形式的值，供 Langfuse 这类要 `Authorization` header 的后端用）：

```java
String endpoint = System.getenv().getOrDefault(
        "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318/v1/traces");
String authorization = System.getenv("OTEL_EXPORTER_OTLP_AUTHORIZATION");

var exporterBuilder = OtlpHttpSpanExporter.builder().setEndpoint(endpoint);
if (authorization != null && !authorization.isBlank()) {
    exporterBuilder.addHeader("Authorization", authorization);
}

SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporterBuilder.build()).build())
                .build();

OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();
Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
```

- 必须在 middleware 开始工作前注册 SDK。运行环境已注册 `GlobalOpenTelemetry`（如 Spring Boot 自动配置）时直接复用，只加 middleware。
- 不再调用已弃用的 `TracerRegistry.register(...)`；关闭时关掉 `SdkTracerProvider` 让 batch processor 刷出未导出的 span。
- 每次 reply 产出嵌套 span 树，属性含 agent 名、session ID、模型名、token 数、工具名与入参。

### TaskReminderMiddleware

与内置 `TodoTools` 配合，在每个 reasoning step 前把 `AgentState.tasksContext` 渲染成 `<system-reminder>` 注入上下文，避免长任务偏离计划。用 builder 的 `enableTaskList(true)` 与 `TodoTools` 一起开：

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new TodoTools());

ReActAgent agent =
        ReActAgent.builder()
                .name("planner").sysPrompt("You plan tasks step by step.")
                .model(model).toolkit(toolkit)
                .enableTaskList(true)
                .build();
```

### FinalAnswerFilterMiddleware

只输出 ReAct **最终推理轮次**的文本；产生工具调用的中间轮次文本被过滤，工具事件与其他非文本事件仍正常流式输出。

```java
ReActAgent agent = ReActAgent.builder()
        .name("assistant").model(model).toolkit(toolkit)
        .middleware(new FinalAnswerFilterMiddleware())
        .build();
```

因为只有「没观察到工具调用」才能确定是最终轮，它会把每轮文本缓冲到模型调用结束。

## 自定义 Middleware

实现 `io.agentscope.core.middleware.MiddlewareBase`，只重写需要的 hook。每个洋葱 hook 收到 `next`，调 `next.apply(input)` 进内层；可用 `Flux<AgentEvent>` 算子（`doOnNext` / `flatMap` / `map`）观察或改写中间事件流。

```java
/** 同时观察 agent / reasoning / model_call / system_prompt 四个位置。 */
public class FullObservabilityMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        System.out.println("[agent] start for " + agent.getName());
        return next.apply(input)
                .doOnComplete(() -> System.out.println("[agent] end for " + agent.getName()));
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnComplete(() -> System.out.println("[reasoning] end"));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, RuntimeContext ctx, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        System.out.println("[model_call] " + input.model().getClass().getSimpleName());
        return next.apply(input).doOnComplete(() -> System.out.println("[model_call] done"));
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.just(currentPrompt);
    }
}
```

各 hook 的 input 类型（均在 `io.agentscope.core.middleware`）：

| Hook | Input record | 字段 |
| - | - | - |
| `onAgent` | `AgentInput` | `msgs: List<Msg>` |
| `onReasoning` | `ReasoningInput` | `messages: List<Msg>`, `tools: List<ToolSchema>`, `options: GenerateOptions` |
| `onActing` | `ActingInput` | `toolCalls: List<ToolUseBlock>` |
| `onModelCall` | `ModelCallInput` | `messages`, `tools`, `options`, `model: Model` |
| `onSystemPrompt` | `String` | 当前 prompt |

要替换流入下一层的字段，构造个新 input record 再 `next.apply(...)`。

> 上游各示例的 hook 签名不完全统一：`FullObservabilityMiddleware` 带 `(Agent, RuntimeContext, Input, next)`，而「实用示例」里的 `TimingMiddleware` 等写成 `(Agent, Input, next)`。以能编译的那个为准，或按 `MiddlewareBase` 接口定义覆盖。

### 读 RuntimeContext

所有 hook 都把本次 `call` / `stream` 绑定的 `RuntimeContext` 作为第二参数传入——可读会话字段，也可按类型 / key 取属性，还能反写给下游 hook 和 tool 传值。

```java
public class RequestContextMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        System.out.printf("[req] user=%s session=%s reqId=%s%n",
                ctx.getUserId(), ctx.getSessionId(), ctx.get("request_id"));
        ctx.put("trace_id", java.util.UUID.randomUUID().toString());  // 后续 hook / tool 可读
        return next.apply(input);
    }
}
```

- 同一份 `RuntimeContext` 在整个 reply 内被各层 hook / tool 共享，内部 map 线程安全，可安全 `put`。
- **不要把请求级状态缓存到 middleware 实例字段**——一个实例通常被多个 agent / call 复用；要么放 `RuntimeContext`，要么用 Reactor `contextWrite`。
- builder 上配了全局 `toolExecutionContext` 时，框架分发 tool 时会把它合并到 per-call context **之后**（per-call 优先）。

### 执行顺序

Onion 类 hook 按 `MiddlewareBase.order()` 排序，**数值越大越在外层**；默认 `1`，相同 order 保持 Builder 注册顺序：

```
middlewares = [mw1(order=2), mw2(order=1)]
// mw1 前 → mw2 前 → 内部逻辑 → mw2 后 → mw1 后
```

覆写 `order()` 可改相对位置。例如 order `0` 会进入所有默认 order `1` 的内层。

流式 hook 里内层先看到每个事件：

```
mw1_pre → mw2_pre → mw2_event → mw1_event → ... → mw2_post → mw1_post
```

Transformer（`onSystemPrompt`）从左到右串行接力：

```
middlewares = [mw1, mw2]
// originalPrompt → mw1.onSystemPrompt() → mw2.onSystemPrompt() → final
```

一次 reply 的整体顺序：

```
onAgent
  └── 每一轮 ReAct：
        ├── onReasoning
        │     ├── prepare model input → onSystemPrompt
        │     └── onModelCall
        └── onActing（本轮每个工具调用一次）
```

## 实用示例

### 计时

```java
public class TimingMiddleware implements MiddlewareBase {
    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        long start = System.nanoTime();
        return next.apply(input)
                .doFinally(sig -> System.out.println(
                        "[timing] " + agent.getName() + ": "
                                + (System.nanoTime() - start) / 1_000_000 + "ms"));
    }
}
```

### 限速

```java
public class RateLimitMiddleware implements MiddlewareBase {

    private final long minIntervalMs;
    private final AtomicLong lastCall = new AtomicLong(0);

    public RateLimitMiddleware(Duration minInterval) {
        this.minIntervalMs = minInterval.toMillis();
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        long wait = minIntervalMs - (System.currentTimeMillis() - lastCall.get());
        Mono<Void> delay = wait > 0 ? Mono.delay(Duration.ofMillis(wait)).then() : Mono.empty();
        return delay.thenMany(next.apply(input))
                .doOnSubscribe(s -> lastCall.set(System.currentTimeMillis()));
    }
}
```

### 动态 system prompt

```java
public class DynamicContextMiddleware implements MiddlewareBase {

    private final Supplier<String> contextFn;

    public DynamicContextMiddleware(Supplier<String> contextFn) {
        this.contextFn = contextFn;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
        return Mono.just(currentPrompt + "\n\n## Current Context\n" + contextFn.get());
    }
}
```

### 模型回退

```java
public class ModelFallbackMiddleware implements MiddlewareBase {

    private final Model fallback;

    public ModelFallbackMiddleware(Model fallback) {
        this.fallback = fallback;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input).onErrorResume(err -> next.apply(
                new ModelCallInput(input.messages(), input.tools(), input.options(), fallback)));
    }
}
```

> 只是「主 → 备」回退的话不用写 middleware：`ReActAgent.Builder` 已提供 `fallbackModel(...)` 与 `maxRetries(...)`；观察切换用 `failoverListener(...)`（切换发生在 `onModelCall` 接缝之下）。

### 全部工具被拒绝时停止 agent

HITL 拒绝一轮里**全部**工具调用时，agent 默认继续下一轮（向后兼容）。要停止，用 `onActing` 观察 `AllToolsDeniedEvent` 并发 `RequestStopEvent`：

```java
public class StopOnAllDeniedMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input).flatMap(event -> {
            if (event instanceof AllToolsDeniedEvent) {
                return Flux.just(event,
                        new RequestStopEvent("All tools denied by user",
                                GenerateReason.ALL_TOOLS_DENIED));
            }
            return Flux.just(event);
        });
    }
}
```

装配后 agent 立即停止，返回 `GenerateReason.ALL_TOOLS_DENIED`。

## Heron 现状

- `AgentRuntimeConfig` 没有挂任何 middleware：既没有 `OtelTracingMiddleware`（没有 trace 数据），也没有 `TaskReminderMiddleware`。`toolkit.registerTool(heronResearchTools)` 走的是 `@Tool` 注解式工具，`HeronResearchTools` 目前是唯一工具。
- 追踪这块 Heron 走的是自己的数据库记录（生产任务 / 研究运行），不是 OpenTelemetry。要接 Otel，注意基座是 Spring Boot 4 + Jetty，如果没引 OTel starter，就得按上面那段手动注册 `SdkTracerProvider`，否则 hook 全部 no-op。
- 要加自定义 middleware（例如把 agent 每步进度落库或推给前端），入口是 `HarnessAgent.builder().middleware(...)`；注意 Harness 内置 middleware 排在你加之后（见 [harness-architecture.md](harness-architecture.md)）。
- **不要**把请求级状态放在 middleware 实例字段里——Heron 的 `HarnessAgent` 是单例 bean，会被所有请求复用。
