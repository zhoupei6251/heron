# 让 Agent 学会判断：Jev 与结构化决策（博客蒸馏）

> 上游：<https://java.agentscope.io/v2/zh/blogs/jev-structured-decision-for-agents> · 快照：`_upstream/blogs-jev-structured-decision-for-agents.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 一篇观点文，讲「为什么」而不是「怎么配」。接口细节看同目录 [jev-integration.md](jev-integration.md)。

## 核心论点

Agent 看起来在「推理」和「执行」之间循环，但每一步真正密集发生的是**判断**：选哪个模型、暴露哪些工具、这条命令能否放行、置信度够不够直接返回。单个都不复杂，遍布运行时后成为被低估的系统性开销。

这类判断有三个共同特征：**高频、低复杂度、需要确定性输出**。

现有两种做法都不合适：

| 做法 | 问题 |
| - | - |
| 让主模型顺便判断 | 每次判断都走完整推理路径，返回自由文本还要再解析；输出格式不受控（让它返 JSON，它可能先加一段「好的，我来分析一下」） |
| 硬编码规则 | 确定性强但覆盖不了长尾；「列出目录文件」和「删除目录」在规则层难区分，在语义层一目了然 |

结论：Agent 缺的不是「更聪明的推理」，而是「更便宜、更可靠的判断」。

## Jev 是什么

[TypeSafe AI](https://docs.typesafe.ai/) 的结构化决策模型。不聊天、不生成文本、不做工具调用；接收上下文和结构化问题，返回类型化答案与**校准概率**。

| 问题类型 | 判断模式 | Agent 场景 |
| - | - | - |
| `NoulQuestion` | 是/否的概率 | 「这个操作安全吗？」 |
| `ChoiceQuestion` | 选项集合的概率分布 | 「该用哪个模型？哪个工具？」 |
| `ScoreQuestion` | 等级评分 | 「这段回答质量如何？」 |

和聊天模型的差别在返回形式：问「这条 bash 命令安全吗」，不返回分析文本，直接给 `noul: 0.92`（P(安全)=92%）。

| 维度 | 聊天模型 | Jev |
| - | - | - |
| 输出 | 自由文本 / 工具调用 | 类型化答案 + 概率 |
| 延迟 | 秒级到十秒级 | 亚秒级 |
| 格式可靠性 | 依赖 prompt engineering 和后解析 | 结构化协议保证 |
| 成本 | 按推理路径计费 | 按决策请求计费 |

## 为什么不注册成 Model provider

`Model` 接口面向聊天推理、返回 `ChatResponse`；Jev 返回结构化答案。两者在调用频率、超时预期、类型系统上都不匹配，硬塞进同一接口只会让配置语义混乱。所以 Jev 是独立扩展模块：直接注入 `JevClient`，或通过 middleware 挂进 Agent 循环。

## 三个拦截点对应三种判断

| 中间件 | 拦截点 | 判断类型 | 工程价值 |
| - | - | - | - |
| `JevModelRouterMiddleware` | `onAgent` / `onModelCall` | Choice：选模型 | 规划资源分配 |
| `JevToolSelectionMiddleware` | `onReasoning` | Choice：选工具 | 规划上下文可见性 |
| `JevAutoModeMiddleware` | `onActing` | Noul：判断安全性 | 判断行为边界 |

Agent 入口、推理前、行动前——三个位置正好是生命周期中最需要判断的地方。

博客给的是 SHADOW 模式的示例（默认 `OFF`，只记录建议）；要接管行为需改 `ENFORCE`。执行模式：`OFF` / `SHADOW` / `ENFORCE`，默认 `OFF`。工具推荐/路由失败回退原流程；执行防护在 `ENFORCE` 下失败会拒绝受保护调用。

```java
JevModelRouterMiddleware modelRouter =
        JevModelRouterMiddleware.builder(client)
                .choice("fast", fastModel, "Direct lookups, extraction, and localized changes.")
                .choice("powerful", powerfulModel, "Architecture and high-stakes decisions.")
                .instructions("Choose the least costly model that can complete the task.")
                .confidenceThreshold(0.75)
                .execution(new io.agentscope.extensions.judge.jev.JevExecution.Options(
                    io.agentscope.extensions.judge.jev.JevExecution.Mode.SHADOW,
                    java.time.Duration.ofSeconds(2), "v1", (ctx, record) -> {}))
                .build();
```

判断失败不是让 Agent 停摆，而是**降级到安全路径**（置信度不够退回默认模型）。

## 什么时候不该用

Jev 不是「更便宜的大模型」，以下仍需主模型深度推理：

- **需要多步逻辑推导**：如「这段代码为什么会并发死锁」，答案是推理过程，不是概率。
- **需要理解复杂代码或长文本**：如「这个 PR 能否合并」，要读完整 diff、理解架构约束、评估测试覆盖。
- **输出本身就是文本**：Jev 返回概率和选项，不返回解释、补丁、报告。

判定标准：决策能表达成「是/否」「从 N 个里选一个」「打个分」，且系统据此分支（if/else、路由、阈值）→ 适合 Jev；需要先理解再分析再推导 → 属于推理模型。

一句话：**Jev 回答「可以吗」和「选哪个」，不回答「为什么」。**

## 规划与判断

- **规划**：行动前决定「做什么、用什么做」。选模型 = 资源分配，选工具 = 上下文可见性，决定效率与成本。
- **判断**：执行前决定「能不能做、值不值得做」。风险守卫 = 行为边界，置信度评估 = 输出质量，决定安全性与可靠性。

这两类非推理计算目前多被塞进主模型推理路径或硬编码进业务逻辑。Jev 给出第三条路：抽出来交给专门优化的决策模型，用类型化接口和校准概率返回。

同模式可复用的地方：RAG rerank、意图分类、质量守卫（低分触发重试/升级）、任务分解（先排优先级再分发 subagent）。共同特征：判断不复杂，但频率高、需要确定性、失败时有明确降级路径。

> 推理决定 Agent 能走多远，判断决定它能不能走得又快又稳。
