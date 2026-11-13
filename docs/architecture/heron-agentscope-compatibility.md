# AgentScope Java 2.0 兼容性核实

**日期：** 2026-11-13
**阶段：** Phase 0（只读核实，未引入任何依赖、未改任何代码）
**上游：** `docs/plans/plan.md` §3.1「正式实施前必须核实当前 AgentScope Java 2.0 的实际版本、API、依赖兼容性和 HarnessAgent 能力」

---

## 一、结论速览

| 项 | 结论 | 依据强度 |
|---|---|---|
| AgentScope Java 2.0 是否存在 | **存在**，仓库 `agentscope-ai/agentscope-java` | 仓库 README 已读取 |
| HarnessAgent 是否存在 | **存在**，`io.agentscope.harness.agent.HarnessAgent`，`HarnessAgent.builder()` | 源码片段已读取 |
| JDK 21 是否满足 | **满足**，官方要求 JDK 17+ | 官方 quickstart |
| Maven 坐标 | `io.agentscope:agentscope-harness`（另有 `agentscope-core`、`agentscope-dependencies-bom`） | README + pom 片段 |
| 版本 | 稳定 2.0.x（README 示例用 `2.0.3`，上下文索引列出 `v2.0.4`）；`2.1.0-beta1` 为预发布 | 两处交叉 |
| 能否用官方 Spring Boot starter | **不建议用**：starter 的 parent 钉 `spring-boot.version=4.0.3`，BOM 钉 `4.0.4`；本项目是 **4.1.2** | 两处 pom 片段 |
| 不用 starter 能否工作 | **可以**：`agentscope-core` 的 pom 里**没有任何 Spring Boot 依赖**，只依赖 reactor-core 与 Jackson 2 | core pom 片段 |
| 与本项目最大冲突 | **三处**：Reactor 线程模型、Jackson 2 与 Jackson 3 并存、ThreadLocal 上下文（Sa-Token / 租户 / 审计）跨线程丢失 | 分析结论，见 §4 |

**一句话：技术上可行，但不能走官方 starter 那条路；难点不在「能不能跑起来」，在「跑起来之后业务上下文还在不在」。**

---

## 二、已核实的事实（逐条给来源）

### 2.1 仓库与版本

- 仓库：`agentscope-ai/agentscope-java`，README 自称 "AgentScope Java 2.0"，要求 **JDK 17+**，建议 Maven 3.9+。
- README 出现的版本号：`1.1.0`、`2.0.0-RC1` ~ `RC5`、`2.0.0 GA`（首个生产可用版本）、`2.0.3`（Maven 示例）、`2.1.0-SNAPSHOT`。
- 版本索引另列出 `v2.0.4`、`v2.1.0-beta1`。

**采用建议：** PoC 钉 `2.0.4`。不碰 `2.1.0-*`（beta/SNAPSHOT 不该进生产依赖）。`2.0.0` 标称 GA，但 `2.0.3/2.0.4` 是同一 minor 线上的修补版本，取最新 patch 更稳。

### 2.2 Maven 坐标

```
io.agentscope:agentscope-harness:<version>          # HarnessAgent + 子 Agent + workspace
io.agentscope:agentscope-core:<version>             # ReActAgent / Toolkit / Memory / Event
io.agentscope:agentscope-dependencies-bom:<version>  # 版本对齐
io.agentscope:agentscope-extensions-model-dashscope:<version>
io.agentscope:agentscope-extensions/agentscope-spring-boot-starters/...  # 不建议用，见 §3.3
```

### 2.3 依赖栈（决定能不能塞进现有进程）

`agentscope-core` 的 pom 明确只有这些：

```xml
<dependency><groupId>io.projectreactor</groupId><artifactId>reactor-core</artifactId></dependency>
<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
```

**注释原话：**「The core SDK can be used standalone with JDK 17+ without any Spring Boot framework. Spring Boot integration is exclusively provided through the separate agentscope-spring-boot-starters modules, which all target Spring Boot 4.x.」

这条很关键——它给了我们**绕开 starter 版本钉死**的合法路径。

### 2.4 主要 API（照抄文档，不是推测）

**创建 HarnessAgent**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))  // 省略则按解析顺序
    .additionalContextFile("SOUL.md")               // workspace 相对路径，全文内联
    .maxContextTokens(8000)                         // MEMORY 注入预算
    .build();
```

**注册 Java 工具**

```java
@Tool(name = "get_current_time", description = "...", readOnly = true, concurrencySafe = true)
public String getCurrentTime(@ToolParam(name = "timezone", description = "IANA timezone") String timezone) { ... }

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new SimpleTools());
```

`@Tool` 支持 `readOnly` / `concurrencySafe` / `stateInjected` / `dangerousFiles` / `dangerousDirectories` / `converter`。JSON Schema 由 Java 类型自动派生——**这意味着工具签名即契约，参数校验靠类型，不是靠我们自己写校验**。

**子 Agent 委派**

```java
.subagent(SubagentDeclaration.builder()
    .name("remote-researcher")
    .description("Remote research subagent")
    .url("http://agent-task-server:8080")     // 远程模式
    .headers(Map.of("Authorization", "Bearer xxx"))
    .build())
```

`SubagentDeclaration` 两种模式：
- **本地**：`workspacePath` 指向含 `AGENTS.md` 的目录，system prompt 从 `AGENTS.md` 读；
- **内联**：`inlineAgentsBody` 直接给 system prompt，`tools` 是父工具白名单过滤器。

另有 `disableDynamicSubagents()`：默认 `false` 时用 `DynamicSubagentsMiddleware`（每一步重新从 workspace 解析子 Agent），`true` 时退回构建期一次性合并的 `SubagentsMiddleware`。

**迭代上限**

`ReActAgent.Builder.maxIters(int)`：`iter >= maxIters` 时调 `summarizing()` 退出循环，并发 `ExceedMaxItersEvent`。**这正好对上 plan.md §5.2 的「Supervisor 最大推理轮数」**，不用自己实现。

**流式事件**

```java
agent.streamEvents(new UserMessage("..."), ctx)   // 返回 Flux<AgentEvent>
     .doOnNext(event -> { ... })
     .blockLast();
```

事件类型见过：`AgentEventType.TEXT_BLOCK_DELTA`、`TOOL_CALL_START`，对应 `TextBlockDeltaEvent`、`ToolCallStartEvent`；还有 `ModelCallEndEvent`（带 usage）。

**运行时上下文**

```java
RuntimeContext ctx = RuntimeContext.builder().userId("alice").sessionId("demo-session").build();
```

### 2.5 线程模型

- 返回 `Flux<AgentEvent>`，`ReActAgent` 返回 `Mono<Msg>`——**全程响应式**。
- 文档注释：「ReActAgent in 2.0 is thread-safe, so we just use a singleton instance.」
- 但 `Memory` 与 `Toolkit` 是**有状态且非线程安全**的，官方 starter 把它们声明成 `prototype` scope，并注明「In multi-threaded / web environments, it is recommended to obtain instances lazily via `ObjectProvider<Memory>` or method injection」。

---

## 三、与本项目的对齐矩阵

本项目事实：JDK `21`、Spring Boot `4.1.2`、Jetty + Spring MVC（阻塞）、`ruoyi-heron` 219 个 Java 文件、Jackson **3**（`tools.jackson`）、单租户自部署。

| 维度 | AgentScope 要求 | 本项目现状 | 判定 |
|---|---|---|---|
| JDK | 17+ | 21 | ✅ 直接满足 |
| Spring Boot | starter 钉 4.0.3 / 4.0.4 | 4.1.2 | ⚠️ 不用 starter；core 无 Spring 依赖，手写装配 |
| Web 栈 | Reactor（Flux/Mono） | Jetty + MVC，**根 pom 无 reactor/webflux** | ⚠️ 新增 `reactor-core`；Agent 调用必须**离开请求线程**，否则请求线程被 block |
| JSON | Jackson **2**（`com.fasterxml.jackson`） | Jackson **3**（`tools.jackson`），heron 内 0 处 Jackson 2 | ⚠️ 两套 JSON 栈并存。包名不同，可共存，但工具出入参要统一走一层转换 |
| 有状态 Bean | Memory / Toolkit 非线程安全 | RuoYi 大量单例 Service | ⚠️ 每个研究任务一个 Memory 实例，不能共享 |
| 上下文传递 | Reactor 自有调度线程 | Sa-Token、租户、`@Log`、`SecurityContext` 全在 ThreadLocal | 🔴 **最大风险**，见 §4 |
| 文件系统 | HarnessAgent 有 workspace / sandbox fs / `dangerousFiles` | plan.md §6.3 明令「不允许访问任意文件系统路径」 | 🔴 必须显式关掉或限制，见 §5 |
| 定时任务 | 自带并发与预算控制 | 已有 `@Scheduled` + `heron_auto_slot` 幂等 + SnailJob 可用 | ✅ 不引入第二套调度 |
| 审计 | 自有事件流 | `@Log` + `sys_oper_log` + `heron_task_step` / `heron_usage_record` | ✅ 复用现有落库，Agent 事件往里写 |

### 3.3 为什么不用官方 Spring Boot starter

两个 pom 片段：

```xml
<!-- agentscope-extensions/agentscope-spring-boot-starters/pom.xml -->
<spring-boot.version>4.0.3</spring-boot.version>
<!-- agentscope-dependencies-bom/pom.xml -->
<spring-boot.version>4.0.4</spring-boot.version>
```

本项目 `pom.xml:23` 是 `4.1.2`。starter 里那些 `@AutoConfiguration` / `@ConditionalOnBean` 在 4.1.x 上**大概率能跑**，但：

1. 一旦引入 starter，Maven 会为 AgentScope 的 Spring 相关传递依赖解析到 4.0.x 版本线，可能与 `spring-boot-dependencies` 4.1.2 的管理产生版本仲裁噪音；
2. starter 做的事很少——就一个 `Memory`（`InMemoryMemory`）、一个空 `Toolkit`、一个 `ReActAgent` 单例。这些我们本来就要按任务的形状自己造；
3. 自己装配才能保证 **Memory 每任务一份**、**Toolkit 只挂只读工具**、**maxIters 从配置读**。

**结论：Phase 1 只引 `agentscope-harness`（+ 传递的 core / reactor），手写一个 `@Configuration` 装配，不引 starter。**

---

## 四、🔴 最大风险：ThreadLocal 上下文跨线程丢失

这是本阶段最需要盯的一个技术判断，也是你提的第二个问题（「现有任务体系能否与 Agent 会话正确衔接」）的技术根因。

AgentScope 全程跑在 Reactor 的调度线程上。`Flux`/`Mono` 默认不传播 ThreadLocal。而本项目的业务上下文**全部**是 ThreadLocal：

| 上下文 | 载体 | 不处理会怎样 |
|---|---|---|
| 登录态 / 权限 | Sa-Token `SaHolder` | 工具里调 `@SaCheckPermission` 的 Service 会抛未登录；数据权限过滤失效 |
| 租户 | `TenantHelper`（本项目单租户，但基座仍读） | 写库时 `create_dept` 等填充异常 |
| 审计操作人 | `LoginHelper` / `@Log` 切面 | 审计记录记成系统账号或空 |
| 数据源路由 | `dynamic-datasource` 的 `DataSourceContextHolder` | 多数据源场景下取错库 |
| 异常处理 | `ServiceException` 被框架捕获 | 异常在 Reactor 里变成 `onError`，栈信息与业务错误码脱节 |

**这不是「可能有问题」，而是「一定有问题」，只分暴露得早还是晚。**

三种处理（Phase 1 必须实测选一种，不能凭猜）：

| 方案 | 做法 | 代价 |
|---|---|---|
| A. 显式快照 + 重回填 | 进 Reactor 前抓 `LoginUser`/租户/操作人成一个不可变对象，工具入口处重新 `SaHolder`/`TenantHelper` push | 每个工具都要包一层；漏一个就静默降级 |
| B. `Hooks.enableAutomaticContextPropagation()` | Reactor 3.5+ 提供的 ThreadLocal 自动传播 | 依赖每个 ThreadLocal 可复制；Sa-Token 的 holder 有 `RequestContext` 副本，跨线程未必成立 |
| C. 工具层不做鉴权，一律走「已授权的只读门面」 | Agent 只能碰我们包好的只读查询，权限在创建任务那一刻校验一次 | 粒度粗；但**这与 plan.md §6.3「Agent 只拥有业务查询权限」最契合** |

**倾向 C + A 组合：** 权限在「创建研究任务」时校验（谁发起、以谁的身份），工具层用显式传入的 `userId` / `deptId` 参数，不依赖隐式 ThreadLocal。Reactor 里就不存在「上下文丢了」的问题，因为它本来就不靠上下文。

这一条必须在 Phase 1 的 PoC 里用**真实 Spring 容器**验证，不能用裸 main 方法糊过去——裸 main 里没有 Sa-Token，永远验不出问题。

---

## 五、🔴 第二风险：文件系统与沙箱

`HarnessAgent` 的核心卖点之一是 workspace：子 Agent 的 system prompt 从 `AGENTS.md` 读，Skills 也从目录读，`@Tool` 还能声明 `dangerousFiles` / `dangerousDirectories`。

这与 plan.md §6.3 直接冲突：

> 3. 不允许访问任意文件系统路径。
> 4. 不允许任意执行 Shell 命令。

**两种做法，Phase 1 必须二选一：**

1. **不用 workspace**（推荐给 Phase 1）：`SubagentDeclaration` 走 `inlineAgentsBody` 内联 prompt，`HarnessAgent.builder()` 不设 `.workspace(...)`。子 Agent 依然可用，只是没有「目录里的技能」。
2. **用 workspace 但指向只读隔离目录**：由我们打包时生成，运行时只读，且 `dangerousDirectories` 显式加上仓库根、`data/`、`script/`。

无论哪种，**Phase 2 之前必须验证：Agent 无法通过工具读到 `application-prod.yml`**。这一条按「安全边界」处理（Tier 2），不是常规功能。

---

## 六、能力清单：分三档如实标注

### 6.1 已核实（读过源码或官方文档片段）

| 能力 | 结论 |
|---|---|
| HarnessAgent 构建 | `HarnessAgent.builder()` + name/model/workspace/additionalContextFile/maxContextTokens |
| ReActAgent 迭代上限 | `maxIters`，超限走 `summarizing()` 并发 `ExceedMaxItersEvent` |
| 线程安全 | ReActAgent 2.0 线程安全（官方注释）；Memory/Toolkit **不**线程安全 |
| Java 工具注册 | `@Tool` / `@ToolParam` + `Toolkit.registerTool`，Schema 由类型派生 |
| 子 Agent 委派 | `SubagentDeclaration.builder()`，支持本地（AGENTS.md）、内联、远程 HTTP 三种 |
| 流式事件 | `streamEvents()` → `Flux<AgentEvent>`，含文本增量与工具调用事件 |
| Token 用量事件 | `ModelCallEndEvent` 携带 usage |
| 运行上下文 | `RuntimeContext.builder().userId().sessionId()` |
| 零 Spring 依赖可独立运行 | core pom 实证 |
| JDK 21 | 满足 17+ 要求 |

### 6.2 官方文档提到、本阶段尚未实测

| 能力 | 为什么没实测 |
|---|---|
| Skills | 只看到目录约定，未见完整 API；且与「禁止文件系统」冲突 |
| Workspace 记忆注入 | `.maxContextTokens()` 是注入预算，实际注入行为未跑过 |
| Checkpoint / Session 持久化 | 看到 `sessionId` 参数，**没看到持久化存储实现**——`InMemoryMemory` 的名字暗示默认不落盘 |
| 远程子 Agent 协议 | `agent-task-server` 是什么、要不要单独部署，未核 |
| 费用统计聚合 | 只看到 usage 事件，没看到汇总 API |

### 6.3 明确不适合本项目

| 能力 | 原因 |
|---|---|
| `agentscope-spring-boot-*` starters | 钉 Boot 4.0.x，本项目 4.1.2；且做的事太少，不如自己装配 |
| AG-UI / A2A 协议层 | 面向「前端接 Agent」，本项目已有自己的任务与前端；再引一层协议等于第二套状态 |
| AgentScope 的 Memory 当业务存储 | plan.md §3.2 已明确：AgentScope 的 Session/Memory/Workspace 不能替代 Heron 的业务状态、Frozen Context、任务恢复和幂等 |
| 把 `HarnessAgent` 当常驻单例 | Memory 有状态；必须每任务一份，或每任务独占 sessionId |

---

## 七、Phase 1 PoC 必须回答的问题（验收清单）

按你提的三个问题组织，每条都要有**可复现的证据**，不接受「文档上说可以」。

### 问题一：HarnessAgent 能否稳定嵌入现有 Spring Boot

| # | 要验什么 | 通过标准 |
|---|---|---|
| 1.1 | `agentscope-harness:2.0.4` 与 Spring Boot 4.1.2 能否同进程启动 | 应用能起，`@SpringBootTest` 通过 |
| 1.2 | reactor-core 引入后是否与 Jetty/MVC 冲突 | 现有 290 项默认组测试仍全绿 |
| 1.3 | Jackson 2 与 Jackson 3 并存会不会串 | 工具返回对象能被 Agentscope 正确序列化，且 `JsonUtils` 行为不变 |
| 1.4 | Agent 调用不占请求线程 | 一次 5 分钟研究期间，Web 请求 p99 不受影响 |
| 1.5 | 优雅停机 | 停应用时正在跑的 Agent 任务能收敛，不留僵尸线程 |

### 问题二：现有任务体系能否与 Agent 会话正确衔接

| # | 要验什么 | 通过标准 |
|---|---|---|
| 2.1 | ThreadLocal 上下文 | 在 Reactor 线程里调一个 `@SaCheckPermission` 的 Service，结果可预期（要么通、要么按 §4 方案 C 不走 ThreadLocal） |
| 2.2 | 会话 ↔ 任务映射 | 一个 `heron_production_task` 能稳定映射到一个 Agent `sessionId`，重试时**复用**而不是新建 |
| 2.3 | checkpoint 落进现有表 | Agent 中间状态能写进 `heron_task_step.checkpoint`，且 attempt 递增时能读回 |
| 2.4 | 幂等 | 同一 `request_hash` 重复触发，不产生第二次模型费用 |
| 2.5 | 失败与重试语义 | `ExceedMaxItersEvent` 与工具异常能落成 `error_code`，且可被人读懂 |
| 2.6 | 预算硬约束 | `maxIters` / 工具调用次数 / 超时到点必须真的停，不能只是「提示模型别继续」 |

### 问题三：多 Agent 是否带来足够质量提升

| # | 要验什么 | 通过标准 |
|---|---|---|
| 3.1 | 三组对照可跑 | A=单次 LLM、B=ReActAgent、C=HarnessAgent+子 Agent，同一输入同一快照 |
| 3.2 | 样本量 | ≥ 30 条真实数据机会（`heron_data_opportunity` 现在每天只能稳定产出个位数，**样本可能不够，见 §8**） |
| 3.3 | 评价维度可打分 | 事实准确率、洞察有效性、可制作性至少三项能做人工盲评 |
| 3.4 | 成本可算 | 每组每条的平均输入/输出 token 与金额 |
| 3.5 | 结论可证伪 | 若 C 组质量提升 < 成本增幅的某个阈值，**方案要能退回 B 或 A**，不是硬上 |

---

## 八、一个必须先说清的前提问题

你提的第三个问题（多 Agent 能否带来质量提升）成立的前提是**有足够多的样本可评**。

但本阶段盘点到的现状是：数据机会在同一天经过信号、去重、合并之后，**只稳定产出个位数**（步骤 1 实测：某日 107 个源 → 1 条机会，Frankfurter ×7 合并而来）。

这意味着：

- 按每天 1~3 条机会算，攒 30 条样本要 **10~30 天**；
- 在样本攒够之前，「多 Agent 是否更好」这个问题**无法用数据回答**，只能用 3~5 条做定性观察。

**两条路，必须选一条：**

1. 先扩样本：把窗口放大（`dataAnalysisWindowPoints` 从 120 提到 360）、放宽阈值、或允许同一源的多个实体各自成条——代价是机会质量下降，做出来的片子可能不好看；
2. 先做定性：Phase 1 只盯「能不能跑通、上下文对不对」，把「多 Agent 值不值」推迟到样本自然积累之后再评。

**我的建议是 2。** 理由：多 Agent 的价值判断需要真实点击率/完播率做最终裁决，而不只是 LLM 打分的盲评；而现在的发布数据回流（`heron_news_metric`）本身还没跑量。在没有播放量数据的情况下比较 A/B/C，比的是「文案看着像不像样」，这个结论不可靠。

---

## 九、结论

| 问题 | 答复 |
|---|---|
| 技术上能不能用 | **能。** JDK 21 满足，core 无 Spring 依赖，手写装配可行 |
| 要不要用官方 starter | **不要。** 版本钉在 4.0.x，且能力太薄 |
| 最大的坑 | ThreadLocal 上下文（Sa-Token / 租户 / 审计）在 Reactor 线程上必丢；文件系统访问与安全边界冲突 |
| 最大的不确定性 | Session/Checkpoint 持久化没有实证；「多 Agent 更好」在当前样本量下**无法证明** |
| Phase 1 该做什么 | 只做 PoC，只验 §7 的 16 条，**不接业务、不建表、不上生产** |

---

## 十、本阶段未做 / 未验证

- **未引入任何依赖**，未执行 `mvn dependency:tree`，所以「reactor-core 加入后与现有依赖的版本仲裁结果」是分析结论，不是实测。
- **未在 Spring Boot 4.1.2 上实际启动过 AgentScope**。§3 的对齐矩阵中 Boot 版本一行标的是「未验证」，依据是官方 pom 的版本钉死 + core 无 Spring 依赖这一事实推导出的可行性。
- **Session / Checkpoint 的落盘实现未核实**。文档只出现 `sessionId` 参数与 `InMemoryMemory`，没有持久化存储的类型。这一条列进 Phase 1 的 2.3。
- **未核实 `agentscope-harness` 的完整传递依赖清单**（只有 core 的 pom 片段），harness 层是否还引入 Netty / Vert.x / 其他 HTTP 栈未知。这一条必须在 Phase 1 的第一步就查，因为它直接决定「会不会和 Jetty 抢端口/抢线程」。
- 网络检索在本阶段返回为空，所有事实来自直接读取 GitHub 仓库文件与文档索引；**未交叉核对官方站点 java.agentscope.io 的版本声明**。
