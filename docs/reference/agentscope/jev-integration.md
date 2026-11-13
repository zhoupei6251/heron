# Jev 结构化决策集成

> 上游：<https://java.agentscope.io/v2/zh/jev> · 快照：`_upstream/jev.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游专页（更细）可能已并入 dev 文档：<https://java.agentscope.io/dev/zh/docs/harness/jev/index.md>
> 设计动机与取舍见同目录 [jev-structured-decision.md](jev-structured-decision.md)。

`agentscope-extensions-jev` 位于 `agentscope-extensions-judge` 父模块下，为 [TypeSafe System One](https://docs.typesafe.ai/) 和 Jev 提供 Java HTTP client。

**Jev 不是聊天模型，也不会注册成 AgentScope 的 `Model` provider**，它适合在应用代码里做路由、评分、分类这类需要快速、类型安全、带置信度判断的决策。

## 何时使用

- 把非结构化输入变成 `Noul`、`Choice` 或 `Score` 类型结果。
- 需要校准概率和置信度，而不是生成一段文本。
- 在调用更大的推理模型之前做一次低成本预判。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-jev</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

> Heron 现状：`ruoyi-modules/ruoyi-heron/pom.xml` 当前只引 `agentscope-harness` 与 `agentscope-extensions-model-openai`，**未引入 jev**；要用需自行加依赖。

## 快速上手

```java
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import java.time.Duration;
import java.util.Map;

JevClient client =
        JevClient.builder()
                .apiKey(System.getenv("TYPESAFE_API_KEY"))
                .baseUrl("https://api.typesafe.ai")
                .model("jev-latest")
                .retryPolicy(new JevRetryPolicy(2, Duration.ofMillis(500)))
                .build();

SystemOneRequest request =
        SystemOneRequest.builder()
                .state("My payouts have been failing for 3 days.")
                .question(
                        "team",
                        new ChoiceQuestion(
                                "Which team should handle this?",
                                Map.of(
                                        "billing", "Payments and refunds",
                                        "technical", "Bugs and integrations")))
                .build();

SystemOneResult result = client.systemOneBlocking(request);

ChoiceAnswer answer = (ChoiceAnswer) result.answers().get("team");
if (answer.confidence() < 0.75) {
    // route to human review
} else {
    // route to answer.choice()
}
```

## 支持的问题类型

| 类型 | 结果 |
| - | - |
| `NoulQuestion` | 一个 yes/no 陈述为真的概率 |
| `ChoiceQuestion` | 选中的选项、每个选项的概率和置信度 |
| `ScoreQuestion` | 概率加权分数、等级说明、每个等级的概率和置信度 |

## Spring Boot starter

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-jev-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```yaml
agentscope:
  jev:
    api-key: ${TYPESAFE_API_KEY:}
    base-url: https://api.typesafe.ai
    model: jev-latest
    timeout: 5s
    retry:
      max-retries: 2
      initial-backoff: 500ms
```

`agentscope.jev.api-key` 可以不配置；未设置时 client 依次读取 `TYPESAFE_API_KEY`、`JEV_API_KEY` 环境变量。

高级配置：定义 `JevClientBuilderCustomizer` bean。

## Client 行为

- 调用 `POST /v1/systemone`。
- 默认 `https://api.typesafe.ai` 与 `jev-latest`。
- 未显式设置 API key 时读 `TYPESAFE_API_KEY`，`JEV_API_KEY` 兜底。
- 使用 AgentScope 共享的 `HttpTransport`。
- 默认每请求 5 秒超时，可用 `timeout(Duration)` 配置。
- 对 HTTP `429`、`529`、`5xx` 按指数退避重试。
- 校验 answer key、answer 类型、概率、score legend 是否与请求匹配。
- 不可重试的客户端错误、重试耗尽、非法响应 → 抛 `JevException`。

## 示例中间件

`io.agentscope.extensions.judge.jev.example` 包提供三个参考中间件，可直接 `ReActAgent.builder().middleware(...)` 挂载，也可复制到项目里改提示词和阈值。

| 中间件 | 拦截点 | 作用 |
| - | - | - |
| `JevToolSelectionMiddleware` | `onReasoning` | 用 Jev 筛选和排序发给主模型的工具 |
| `JevModelRouterMiddleware` | `onAgent` / `onModelCall` | 用 Jev 在多个模型之间路由 |
| `JevAutoModeMiddleware` | `onActing` | 用 Jev 在工具执行前判断风险 |

> 执行模式 `Mode` 有 `OFF` / `SHADOW` / `ENFORCE`，**默认 `OFF`**（见博客版说明）。参考中间件示例里显式给了 `SHADOW`，只记录建议、不接管行为；要真正生效需改 `ENFORCE`。

### 工具选择

`JevToolSelectionMiddleware` 减少发给主模型的 tool schema 数量，保留核心工具，用 Jev 对可选工具排序。

```java
JevToolSelectionMiddleware toolSelection =
        JevToolSelectionMiddleware.builder(client)
                .alwaysIncludeTools(Set.of("load_skill_through_path", "reset_tools"))
                .maxTools(3)
                .confidenceThreshold(0.5)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(model)
                .toolkit(toolkit)
                .middleware(toolSelection)
                .build();
```

行为：

- 在 `onReasoning` 阶段、模型调用前执行。
- 默认保留 `load_skill_through_path`、`reset_tools`、`generate_response`。
- 保留概率高于合成选项 `__none__` 的可选工具，最多 `maxTools` 个。
- 每个 reasoning step 重新选择，把完整 `input.messages()` 状态发给 Jev。
- 超过 254 个工具时先分块，再对每块胜者重排。
- `failOpen(true)` 时 Jev 失败保留原始工具列表。

### 模型路由

`JevModelRouterMiddleware` 在 agent 调用模型前，让 Jev 在已配置模型中做选择；每个候选模型有自己的路由标准，返回选择结果、校准概率和置信度。

```java
JevModelRouterMiddleware modelRouter =
        JevModelRouterMiddleware.builder(client)
                .choice("fast", fastModel, "Direct lookups, extraction, and localized changes.")
                .choice("powerful", powerfulModel, "Architecture and high-stakes decisions.")
                .instructions("Choose the least costly model that can complete the task.")
                .confidenceThreshold(0.75)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(fallbackModel)
                .middleware(modelRouter)
                .build();
```

行为：

- 读取最新用户消息，每个 agent 调用只决策一次。
- 只替换 `ModelCallInput.model`；消息、工具、生成参数原样透传。
- 决策结果、各选项概率和置信度存在 `JevModelRouterMiddleware.decision(ctx)`。
- 以下情况回退到 agent 配置的原始模型：没有用户文本、置信度低于阈值、`failOpen(true)` 下 Jev 失败、结果不可用。
- 最多 255 个候选模型，超出在配置阶段直接报错。
- 带工具的请求默认回退，需通过 `compatible` 谓词声明候选能力（博客版补充）。

### 工具执行守卫

`JevAutoModeMiddleware` 在工具真正执行前，用 Jev 判断高风险调用能否自动放行。

```java
JevAutoModeMiddleware autoMode =
        JevAutoModeMiddleware.builder(client)
                .guardedTool("bash")
                .safetyThreshold(0.5)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(model)
                .toolkit(toolkit)
                .middleware(autoMode)
                .build();
```

行为：

- 在 `onActing` 阶段、工具执行前执行，位于确定性 `PermissionEngine` 管线之前。
- 名称命中 `guardedTools` 且状态不是 `ALLOWED` 的调用，向 Jev 发 `NoulQuestion`，state 含完整对话历史和工具参数。
- 多个 guarded 调用合并成一个 Jev 请求，一个调用一个 question。
- `NoulAnswer.noul()` 即 P(safe)，低于 `safetyThreshold` 的调用被拒绝。
- 被拒调用不执行：合成 `DENIED` 的 `ToolResultBlock` 写入对话状态，只把安全调用传给后续执行。
- 已通过 HITL 确认 `ALLOWED` 的调用跳过 Jev 检查，人工确认优先。
- `failOpen(true)` 时 Jev 失败放行；`failOpen(false)` 时报错。
