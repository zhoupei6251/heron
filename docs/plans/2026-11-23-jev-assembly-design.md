# JEV 接入（装配层）设计

日期：2026-11-23。上游结论：`docs/reports/heron-jev-architecture-review.md`（P0 = 0，分阶段接入）、
`docs/reports/heron-jev-2.1.0-beta1-capability-check.md`（构件能力面核实）。

## 决定

只做装配层：加依赖 + 一个可开关的 `JevClient` Bean。**不接任何业务判断点，主链零变化。**

评审结论是「P0 = 0，没有任何必须接的能力」。这一层的作用是把外部判断的客户端和配置准备好，
等阶段 1 的 SHADOW 点定了再写调用方；在此之前 Bean 没有调用者，是刻意的。

## 为什么默认端点指向 Command Code

Command Code 的 Jev 是 SystemOne 协议兼容实现，可以直接接进官方 `JevClient`：

| 项 | 值 |
| --- | --- |
| Base URL | `https://api.commandcode.ai/provider/v1` |
| 端点 | `POST /provider/v1/systemone` |
| 鉴权 | `Authorization: Bearer <CMD_API_KEY>` |
| 模型 | `typesafe/jev` |
| 定价 | 输入 $0.04/M tokens，输出不计费；上下文 32K |

三条兼容性是对 2.1.0-BETA1 构件字节码核实的，不是照文档假设：

1. `JevClient` 发的头就是 `Authorization: Bearer <apiKey>`，与 Command Code 要求一致。
2. URL 拼接规则：baseUrl 以 `/v1/systemone` 结尾直接用；以 `/v1` 结尾补 `/systemone`；否则补
   `/v1/systemone`。所以 baseUrl 填到 `/provider/v1` 正好落到目标端点。
3. 模型名走请求体 `SystemOneRequest.model`，client 上的 `model` 是兜底。

换回 TypeSafe 官方只需改 `heron.jev.base-url` 与 `heron.jev.model`，不动代码。

## 配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `heron.jev.enabled` | `false` | Spring 开关，独立于 `heron.agent.enabled` |
| `heron.jev.api-key` | — | 先读 sys_config，回落环境变量 `CMD_API_KEY` |
| `heron.jev.base-url` | `https://api.commandcode.ai/provider/v1` | |
| `heron.jev.model` | `typesafe/jev` | |
| `heron.jev.timeout-seconds` | `5` | 单点超时；重试最坏 15 秒，再长就该走旁路 |

开关与研究员开关分开：关掉研究 Agent 不该连带关掉外部判断，反之亦然。

## 失败语义

- 开关开着但缺 key：启动即抛 `HeronBusinessException(503, "jev_not_configured")`，与
  `model_not_configured` 同一口径。不静默降级、不默认放行。
- 开关关着：一个 Bean 都不建，应用行为与引入依赖前完全一致。

## 验证

- `mvn -pl ruoyi-modules/ruoyi-heron -am -DskipTests compile`：BUILD SUCCESS。
- `JevConfigTest`：开关关着不建 Bean / 开着且配了 key 建出客户端 / 缺 key 启动失败并给稳定错误码，3 例全过。
- 模块全量 `mvn -o -pl ruoyi-modules/ruoyi-heron -am test`：710 例全过。
  顺带修了两条**与本任务无关的既有坏测试**（`NewsPlaceholderSourceKindTest`、
  `NewsPublishTransactionTest` 没跟上 `NewsWorkServiceImpl` 新增的 `DatasetFetchService` 参数，
  由提交 `e552b0a0c` 引入），否则测试编译过不去。

## 有意不做

- **不写调用点**：SHADOW 阶段再定挂哪个判断点（选题还是核验），现在没有结论。
- **不引入任何 middleware**：Tool Selection / Guardrail / Supervision / Answer Refinement
  按评审第 3 节全部不接，理由是可复用的确定性实现已经闭环，外部判断只增加成本与外呼。
- **不加冒烟入口**：为一个临时验证加 controller 要顺带配权限与路由，不值。
- **不做 fail-open**：JEV 的 ERROR / 超时 / 未配置一律不当作 PASS。

## 遗留

数据出境口径仍未过：接入 SHADOW 前需要确认机会快照、候选标题、研究结论进外部提示词是否可接受。
装配层不发任何请求，所以这一条不阻塞；SHADOW 由 `heron.jev.enabled` 单独控制。

---

## SHADOW 落点与实现（续）

### 挂在选题，不挂核验

挂点选在 `EditorialDecisionService.decide(pools)`（调用方 `NewsAutoService.tick` 建池之后）。

| 判据 | 选题 | 核验 |
| --- | --- | --- |
| 信息对等 | 总编辑与 JEV 看同一份 `pools` 摘要 | 核验员有独立会话与工具，JEV 只拿摘要，信息不对等 |
| 状态大小 | 几十条文本，远低于 32K | 证据快照易顶上限，截断即失真 |
| 是否重复评估 | 第一处外部对照 | 核验已是独立双跑，再加是第四处 |
| 杠杆 | 选错线下游全废 | 影响单条研究结论 |

### 数据流

```
NewsAutoService.tick → buildPool → pools 摘要 → editorial.decide(pools)
                                                   ├─ 本地模型判 → Decision（主链照常使用）
                                                   └─ shadow.record(pools, decision)
                                                        └─ 异步：SystemOne(state=prompt(pools),
                                                            Choice 五选一：四条线 + NO_PUBLISH)
                                                             → heron_jev_shadow
```

一个 Choice 问题覆盖「选哪条线」与「今天发不发」两件事：`NO_PUBLISH` 就是其中一项，
不用再单独问一次 Noul，省一次判断也避免两个答案互相矛盾。

### 记录（迁移 `V54__jev_shadow.sql`）

单独建表，不塞 `pool_json`——那一列前端在读，注释里明确约束了键结构；对照数据要能独立查询、独立清理。

关键字段：`pool_id` / `pool_hash`（同哈希才可比）、`local_line`、`jev_line`、`jev_confidence`、
`agree`、`status`、`model`、`latency_ms`、`error_code`。

`status` 由 CHECK 约束兜底为 `ok` / `undetermined` 二值。

### 三条硬约定（代码里实现，测试里验证）

1. **不进任何 if**：`decide()` 的返回值与分支同不接 JEV 时完全一致，`record()` 不抛异常。
2. **未定不等于 PASS**：调用失败、超时、非法响应、预算耗尽一律记 `undetermined`。
3. **异步**：复用基座 `scheduledExecutorService`，不新建线程池；日预算默认 20 次
   （`heron.jev.daily-call-limit`），按单实例计，多副本各算各的。

### 验证

- `JevShadowRecorderTest` 5 例：没配即零动作 / 一致 / 不一致 / 调用失败记未定 / 预算耗尽不调用。
- 模块全量 715 例全过。
- 迁移 DDL 在本地库用临时表名建过再删，语法验证通过。

### 真实验证（2026-11-24）

Command Code 的 key 到位后做过端到端冒烟：`JevClient` 走 `https://api.commandcode.ai/provider/v1`
配 `typesafe/jev`，返回 `ChoiceAnswer(choice=NO_PUBLISH, confidence=0.12)`。

请求体里的 `type` 字段由 `Question` 接口上的 `@JsonTypeInfo(property="type")` 自动带上。
实测确认这一条不是隐患：Command Code 缺 `type` 会返回
`Invalid discriminator value. Expected 'noul' | 'choice' | 'score'`，而 `JevClient` 发的请求能过。

同一把 key 顺带切了文本模型：`heron.model.base-url` → `https://api.commandcode.ai/provider/v1`，
`heron.model.name` → `deepseek/deepseek-v4.1-flash`（Command Code 的 id 带供应商前缀，
`/provider/v1/models` 里核实过），一次真实 chat 调用返回正常。

### 仍未验证

- `prompt(pools)` 的真实体积没在线上槽位量过；冒烟用的是缩短的 state。
- 生产库 `heron_prod` 尚未写这批配置，也未打开 `heron.jev.enabled`。
- `sys_config` 是 `@Cacheable` 的，直接改库后必须重启或清 `sys_config` 缓存才生效。
