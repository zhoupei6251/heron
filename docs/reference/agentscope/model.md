# Model

> 上游：<https://java.agentscope.io/v2/zh/docs/building-blocks/model> · 快照：`_upstream/building-blocks-model.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：在 AgentScope Java 中配置并连接 LLM 模型提供商

## 分层

`agentscope-core` 只保留通用契约（`Model`、`ChatModelBase`、`Formatter`、`ModelRegistry`、`ModelProvider` SPI）；具体提供商实现都在各自的模型扩展模块里。

运行时是两层结构：上层 **Credential**（`io.agentscope.core.credential` 的通用基类）承载某提供商的鉴权字段，下层 **Chat Model** 是在该凭证上对接的具体推理模型。

```
CredentialBase/
└── ChatModelBase/
    ├── OpenAIChatModel
    ├── AnthropicChatModel
    ├── DashScopeChatModel
    ├── GeminiChatModel
    └── OllamaChatModel
```

Credential 的 `listModels()` 返回 `List<ModelCard>`——这就是「先注册凭证、再从凭证挑模型」的前端流程。

## 扩展模块

| 提供商 | Maven artifact | 主要包名 |
| - | - | - |
| OpenAI | `agentscope-extensions-model-openai` | `io.agentscope.extensions.model.openai` |
| OpenAI Official | `agentscope-extensions-model-openai-official` | `io.agentscope.extensions.model.openaiofficial` |
| DashScope | `agentscope-extensions-model-dashscope` | `io.agentscope.extensions.model.dashscope` |
| Gemini | `agentscope-extensions-model-gemini` | `io.agentscope.extensions.model.gemini` |
| Anthropic | `agentscope-extensions-model-anthropic` | `io.agentscope.extensions.model.anthropic` |
| Ollama | `agentscope-extensions-model-ollama` | `io.agentscope.extensions.model.ollama` |

从 v1 迁移的四步：加扩展依赖 → 模型实现的 import 从 `io.agentscope.core.model.*` 改为 `io.agentscope.extensions.model.<provider>.*` → formatter 的 import 从 `io.agentscope.core.formatter.<provider>.*` 改为 `io.agentscope.extensions.model.<provider>.formatter.*` → Spring Boot 改用对应 starter 与 `agentscope.<provider>.*` 配置。

## 三种创建方式

### 1. 字符串 model id（非 Spring 简单场景）

`dashscope:qwen-plus`、`openai:gpt-4.1-mini`、`deepseek:deepseek-v4-flash` 这类 id，配好标准环境变量即可：

```java
ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model("dashscope:qwen-plus") // 底层由 ModelRegistry.resolve(modelId) 解析
                .build();
```

扩展模块通过 Java SPI 自动发现。各提供商读自己的标准环境变量：`DASHSCOPE_API_KEY`、`OPENAI_API_KEY`、`GLM_API_KEY`、`DEEPSEEK_API_KEY`、`ANTHROPIC_API_KEY`、`GEMINI_API_KEY`；Ollama 有 `OLLAMA_BASE_URL` 就用，否则用本地默认端点。

### 2. 显式 builder（推荐用于需要控制的场景）

要自定义 API key、base URL、formatter、transport、timeout、生成参数或提供商专属配置时，直接构造 `Model` 实例：

```java
DashScopeChatModel model =
        DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(true)
                .formatter(new DashScopeChatFormatter())
                .build();

ReActAgent agent = ReActAgent.builder().name("assistant").model(model).build();
```

### 3. Spring Boot starter

`agentscope-openai-spring-boot-starter` / `-dashscope-` / `-gemini-` / `-anthropic-` / `-ollama-`：直接依赖对应扩展模块，创建 Spring 管理的 `Model` bean；通用的 `agentscope-spring-boot-starter` 负责公共基础设施。这些 starter **不**通过静态 `ModelRegistry` 创建模型，高级用户仍可自定义 `Model` bean。

```yaml
agentscope:
  model:
    provider: openai
  openai:
    api-key: ${OPENAI_API_KEY}
    model-name: gpt-4.1-mini
    stream: true
```

**Builder customizer**：属性绑定之后、`builder.build()` 之前执行，用于设置 builder 专属能力（formatter、默认生成参数、代理/client 配置）。可注册多个，用 `@Order` / `Ordered` 排序。

| Starter | Customizer 类型 |
| - | - |
| `agentscope-openai-spring-boot-starter` | `OpenAIChatModelBuilderCustomizer` |
| `agentscope-dashscope-spring-boot-starter` | `DashScopeChatModelBuilderCustomizer` |
| `agentscope-gemini-spring-boot-starter` | `GeminiChatModelBuilderCustomizer` |
| `agentscope-anthropic-spring-boot-starter` | `AnthropicChatModelBuilderCustomizer` |
| `agentscope-ollama-spring-boot-starter` | `OllamaChatModelBuilderCustomizer` |

```java
@Configuration(proxyBeanMethods = false)
class ModelCustomizerConfiguration {

    @Bean
    @Order(0)
    OpenAIChatModelBuilderCustomizer openAIModelDefaults() {
        return builder -> builder.defaultOptions(
                GenerateOptions.builder()
                        .temperature(0.2)
                        .parallelToolCalls(false)
                        .build());
    }
}
```

## ModelRegistry 与 ModelCreationContext

`ModelRegistry` 是全局注册中心，解析优先级依次为：`register(name, model)` 直接注册的命名实例 → `registerFactory(regex, factory)` → Java SPI 自动发现的 `ModelProvider`。

简单场景用 `provider:model` + 标准环境变量；要精细控制用显式 builder；`ModelCreationContext` 主要面向动态解析模型的**集成层**（多租户网关、插件系统、框架适配层）。

```java
ModelCreationContext context =
        ModelCreationContext.builder()
                .apiKey(tenantApiKey)
                .baseUrl(tenantBaseUrl)
                .stream(false)
                .option("contextWindowSize", 128000)          // 标量配置，key 由提供商约定
                .component(GenerateOptions.class, ...)        // 以类型为 key 的组件
                .build();

Model model = ModelRegistry.resolve("openai:gpt-4.1-mini", context);
```

### 缓存策略

| 策略 | 行为 |
| - | - |
| `DEFAULT` | `resolve(String)` 按 model id 缓存；`resolve(String, nonEmptyContext)` 默认**不缓存** |
| `DISABLED` | 永不缓存，每次解析都新建实例 |
| `ENABLED` | 显式开启缓存；建议用 `cacheId(...)` 表达租户或配置维度身份 |

`CachePolicy.ENABLED` 搭配 `option(...)` 或 `component(...)` 时必须提供 `cacheId`。

### ModelProvider SPI

扩展模块通过 `META-INF/services/io.agentscope.core.model.spi.ModelProvider` 暴露，`ModelRegistry` 自动发现。新提供商实现 `supports(String, ModelCreationContext)` + `create(String, ModelCreationContext)`；只实现旧版 `supports(String)` / `create(String)` 仍可用（context-aware 方法有兼容默认实现）。

## Chat Model

| 提供商 | 模型类 | 说明 |
| - | - | - |
| OpenAI | `OpenAIChatModel` | Chat Completions API，兼容 vLLM 与 OpenAI 兼容端点（含 DeepSeek、Kimi 等） |
| OpenAI Official | `OpenAIResponsesChatModel` | Responses API（官方 SDK）；推理、结构化输出 |
| Anthropic | `AnthropicChatModel` | Claude，支持 prompt 缓存与 thinking |
| DashScope | `DashScopeChatModel` | Qwen，多模态（视觉/音频/视频）、推理 |
| Gemini | `GeminiChatModel` | Google Gemini，多模态 |
| Ollama | `OllamaChatModel` | 本地托管，凭证可选 |

凭证类随扩展模块提供（`OpenAICredential`、`AnthropicCredential`、`DashScopeCredential`、`GeminiCredential`、`OllamaCredential` 等）。OpenAI 兼容的 `DeepSeekCredential`、`KimiCredential`、`XAICredential` 仍在 core 中。

### 共享 builder 字段

| 字段 | 类型 | 说明 |
| - | - | - |
| `apiKey` | `String` | API key（部分提供商也支持 `credential(...)`） |
| `modelName` | `String` | 模型标识符（如 `"qwen-plus"`） |
| `stream` | `boolean` | 是否流式输出 |
| `defaultOptions` | `GenerateOptions` | 生成参数（`temperature`、`maxTokens`、`thinkingBudget`、`parallelToolCalls` 等） |
| `formatter` | `Formatter` | 覆盖默认消息 formatter |
| `baseUrl` | `String` | 自定义服务端点（如 OpenAI 兼容反代） |

推理模型示例（`enableThinking(true)` + `thinkingBudget`）：

```java
DashScopeChatModel model =
        DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen3-235b-a22b-thinking-2507")
                .stream(true)
                .enableThinking(true)
                .formatter(new DashScopeChatFormatter())
                .defaultOptions(GenerateOptions.builder().thinkingBudget(2048).build())
                .build();
```

### 直接调用

`Model` 暴露统一的 `stream(messages, tools, options)`，返回 `Flux<ChatResponse>`：

```java
model.stream(
                List.of(new UserMessage("Count from 1 to 5.")),
                List.of(),
                GenerateOptions.builder().build())
        .doOnNext(chunk -> System.out.println("Chunk: " + chunk.getContent()))
        .blockLast();
```

`ChatResponse` 含若干 content block（`TextBlock`、`ThinkingBlock`、`ToolUseBlock`、`DataBlock`）与记录 token 数、耗时的 `ChatUsage`。实际开发一般不直接调模型，而是由 `ReActAgent` 调度。

### 结构化输出

把模型输出直接绑定到 Java POJO：`agent.call(msgs, structuredOutputClass, runtimeContext)`，结果挂在 `Msg.metadata` 的 `structured_output`，用 `getStructuredData(Class)` 取。

```java
public class WeatherInfo {
    public String city;
    public double temperature;
    public String unit;
}

Msg msg = agent.call(
                List.of(new UserMessage("What's the weather in Shanghai?")),
                WeatherInfo.class,
                RuntimeContext.empty())
        .block();

WeatherInfo info = msg.getStructuredData(WeatherInfo.class);
```

**两条路径**：

| 路径 | 条件 | 机制 |
| - | - | - |
| Native | `supportsNativeStructuredOutput() = true` | `response_format` + `json_schema` 让模型直接出合规 JSON |
| Fallback（默认） | `false` | 注入 `generate_response` 合成工具，模型用 tool call 返回结构化数据 |

native 失败（如模型返回 400）会**自动降级**到 fallback。

| 提供商 | `supportsNativeStructuredOutput` |
| - | - |
| OpenAI（GPT-4o 等） | `true` |
| OpenAI Official（Responses API） | `true` |
| OpenAI（DeepSeek/GLM formatter） | `false`，走 fallback |
| DashScope | `false`（原生端点只支持 `json_object`，不支持 `json_schema`） |
| Anthropic | `false`（默认） |

- DashScope 的思考模式（`enableThinking(true)`）**不支持**结构化输出，框架强制 fallback。
- 确认端点支持 `json_schema` 时可 `.nativeStructuredOutput(true)` 显式开 native。
- **结构化输出与工具调用共存**：部分 OpenAI 兼容 API（Kimi、Deepseek 等）会优先遵循 `response_format` 而跳过工具调用，设 `.nativeStructuredOutputWithTools(false)` 解决（OpenAI 原生模型不用设）。

### Formatter

Formatter 把 `Msg` 转成各提供商 API 的请求载荷，通过 builder 的 `formatter(...)` 配置。每家内置两种：

| 类型 | 适用场景 |
| - | - |
| **ChatFormatter**（默认） | 标准单 agent 对话，每条 `Msg` 1:1 映射，保留原始角色 |
| **MultiAgentFormatter** | 多 agent（辩论、moderator），连续 agent 消息会聚合并标注发送者名字 |

| 提供商 | Chat | MultiAgent |
| - | - | - |
| DashScope | `DashScopeChatFormatter` | `DashScopeMultiAgentFormatter` |
| OpenAI | `OpenAIChatFormatter` | `OpenAIMultiAgentFormatter` |
| OpenAI Official | — | `ResponsesMultiAgentFormatter` |
| Anthropic | `AnthropicChatFormatter` | `AnthropicMultiAgentFormatter` |
| Gemini | `GeminiChatFormatter` | `GeminiMultiAgentFormatter` |
| Ollama | `OllamaChatFormatter` | `OllamaMultiAgentFormatter` |

载荷格式不属于以上几种时，实现 `Formatter<TReq, TResp, TParams>`（`io.agentscope.core.formatter`）并传给同一个 `formatter(...)`。

### 自定义提供商

最小路径：一个 `CredentialBase` 子类 + 一个 `ChatModelBase` 子类。

```java
public class MyProviderCredential extends CredentialBase {

    private final String apiKey;
    private final String baseUrl;

    public MyProviderCredential(String apiKey, String baseUrl) {
        super("my_provider:" + apiKey.substring(0, Math.min(4, apiKey.length())));
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null ? "https://api.myprovider.com/v1" : baseUrl;
    }

    @Override
    public Class<? extends ChatModelBase> getChatModelClass() {
        return MyProviderChatModel.class;
    }
}

public class MyProviderChatModel extends ChatModelBase {

    @Override
    protected Flux<ChatResponse> doStream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // 调提供商 API，把响应封装为 ChatResponse 流
        return Flux.empty();
    }
}
```

可选：注册工厂让字符串 id 也能解析：

```java
ModelRegistry.registerFactory(
        "myprov:.*",
        modelId -> new MyProviderChatModel(
                new MyProviderCredential(System.getenv("MYPROV_API_KEY"), null),
                modelId.substring("myprov:".length())));
```

## 前端集成：ModelCard

`ModelCard` 是模型能力的声明式描述，用来驱动前端（模型选择器、参数表单、能力开关）动态渲染，前端不必硬编码提供商逻辑。当前是最小 record：

| 字段 | 类型 | 说明 |
| - | - | - |
| `modelName()` | `String` | 模型标识符（如 `"claude-sonnet-4-6"`） |
| `displayName()` | `String` | 可读名称 |
| `contextSize()` | `Integer` | 最大上下文窗口（token） |

```java
AnthropicCredential cred = new AnthropicCredential(System.getenv("ANTHROPIC_API_KEY"));
List<ModelCard> cards = cred.listModels().block();
```

> 字段当前最小化；能力标记（输入/输出 MIME 类型）与参数 schema 会随模型发现基础设施完善而扩展。

## Heron 现状

`AgentRuntimeConfig.heronAgentModel` 走的是**显式 builder** 这条路：

```java
return OpenAIChatModel.builder()
    .baseUrl(baseUrl)      // sys_config 的 heron.model.base-url
    .modelName(name)       // heron.model.name
    .apiKey(apiKey)        // heron.model.api-key
    .build();
```

要点：

- 用的是 OpenAI **兼容**协议，配置来自基座 `sys_config`（`heron.model.*`）而非环境变量，所以上游「字符串 model id + 标准环境变量」那条路在 Heron 不适用——`ModelRegistry.resolve` 读的是 `OPENAI_API_KEY` 等环境变量。
- 三项配置任一为空直接抛 `HeronBusinessException(503, "model_not_configured")`，没有回退默认模型。
- 没设 `formatter` / `stream` / `defaultOptions`，即全用默认值；要调温度、并行工具调用、思考预算，就在这个 builder 上补 `defaultOptions(...)`。
- 没有用 `spring-boot-starter`：上游 starter 会钉不同的 Spring Boot 版本，Heron 是 Boot 4.1.2，所以是手工装配 bean（见 pom 里的注释）。
- 结构化输出（`call(msgs, Class, ctx)`）目前没用——研究层工具链走的是工具调用 + 文本，若要产出强类型结果，这是现成入口。
