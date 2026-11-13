# 上下文与 AgentState

> 上游：<https://java.agentscope.io/v2/zh/docs/building-blocks/context> · 快照：`_upstream/building-blocks-context.md` · 基线 `v2.0.4` · 抓取 2026-11-13

## 无状态 Agent 引擎

`ReActAgent`（以及封装它的 `HarnessAgent`）是**无状态引擎**：实例本身只持有不可变配置——system prompt、模型、工具集、中间件链；所有 per-session 可变数据都放在 `AgentState` 里，以 `(userId, sessionId)` 为索引。

```
┌─────────────────────────────────────────────────────────┐
│                  HarnessAgent（单例）                    │
│  不可变配置: sysPrompt, model, toolkit, middlewares       │
│                                                          │
│  ┌─ state cache ────────────────────────────────────┐    │
│  │ ("alice","s1") → AgentState  ← call(RC(alice,s1)) │    │
│  │ ("bob","s2")   → AgentState  ← call(RC(bob,s2))   │    │
│  └──────────────────────────────────────────────────┘    │
│  per-session 门: 同 (uid,sid) 串行, 不同 (uid,sid) 并行    │
└─────────────────────────────────────────────────────────┘
```

这意味着：

- **不需要 agent-per-user 注册表**，一个实例服务全部用户。
- **不同 `(userId, sessionId)` 完全并行**；相同 `(userId, sessionId)` 自动 FIFO 串行，保证对话一致性。
- **状态完全内部化**：call 入口从存储加载、退出时自动保存，调用方不直接管 state 对象。
- **per-call 隔离**：每次 `call()` 用自己的快照；中间件和工具通过 `RuntimeContext.getAgentState()` 访问本次调用状态。

## AgentState 字段

| 字段 | 内容 |
| - | - |
| `getSessionId()` | 所属会话 |
| `getUserId()` | 所属用户（匿名会话为 null） |
| `getContext()` / `contextMutable()` | 当前对话历史（用户输入、assistant 回复、工具调用、工具结果） |
| `getSummary()` | 压缩后的摘要（开了压缩时） |
| `getPermissionContext()` | 工具权限规则 |
| `getPlanModeContext()` | Plan Mode 是否激活、计划文件路径 |
| `getTasksContext()` | `todo_write` 维护的任务清单 |
| `getToolContext()` | 工具组激活状态（`activatedGroups`） |

执行控制（中断信号）**不在** `AgentState` 上，每次调用独立。

## 持久化与恢复链路

```
call(msgs, RuntimeContext(userId, sessionId))
  ├─ per-session 门: 相同 (uid,sid) 串行, 不同会话并行
  ▼
  从缓存或 stateStore 加载 AgentState → rc.setAgentState(state)
  ▼
  推理循环：中间件就地改写 state.contextMutable()
  ▼
  stateStore.save(userId, sessionId, "agent_state", state)
  ▼
  返回结果
```

这套机制是 `ReActAgent` 自带的，`HarnessAgent` 直接继承，无需配置。Agent 不绑定固定 session——每次读写 `RuntimeContext` 指定的槽位（缺省回退 builder 的 `defaultSessionId`）。

> **状态存储不在每条消息后落盘**，而是 call 结束 / shutdown 时整体写入，对后端吞吐压力很低。

### 内置与扩展实现

| 实现 | 模块 | 适用场景 |
| - | - | - |
| `InMemoryAgentStateStore` | `agentscope-core` | 单测 / 单进程演示；进程退出全丢 |
| `JsonFileAgentStateStore` | `agentscope-core` | **`HarnessAgent` 默认值**，落 `~/.agentscope/state/<agentId>/`（`agentscope.state.home` 可改根目录）；单机 |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | **生产首选**，多副本共享；支持 Jedis / Lettuce / Redisson（Standalone / Cluster / Sentinel） |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | 状态要沉淀进关系库（审计、报表） |

```java
// 默认（单机）：省略 .stateStore(...)
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent").model(model).workspace(workspace).build();

// 多副本生产
JedisPooled jedis = new JedisPooled("redis://redis.prod:6379");
HarnessAgent agent = HarnessAgent.builder()
        .name("MyAgent")
        .model(model)
        .workspace(workspace)
        .stateStore(new RedisAgentStateStore(jedis))
        .distributedStore(RedisDistributedStore.fromJedis(jedis))
        .build();
```

> **强制约束**：用了 `filesystem(SandboxFilesystemSpec)` 或 `filesystem(RemoteFilesystemSpec)` 却没换分布式状态存储时，`build()` 直接抛 `IllegalStateException`——沙箱状态必须跨副本共享。

### 跨进程、跨机器实时恢复

只要状态存储是分布式的，这一切自动成立：节点 A 开对话 → 节点 B（不同 JVM、不同物理机）用相同 `(userId, sessionId)` 首次 `call()` 时自动从 Redis 拉回 `AgentState`。带来三件事：故障转移、滚动发布不断线、跨端接续（Web ↔ CLI）。

`(userId, sessionId)` 二元组决定命名空间：多数场景只用 `sessionId`；要按用户分桶再加 `userId`。

### 多用户隔离

- `sessionId` —— 决定哪段对话是哪段，独立快照。
- `userId` —— 决定这段对话归谁，也决定文件落到谁的命名空间。

生产做 `AgentState` 级隔离，在 `RuntimeContext` 上设 `userId` 即可（`RedisAgentStateStore` 里 `userId` 是 key 的一部分），不依赖文件路径分桶。

### 直接读写与清空

```java
AgentState state = agent.getAgentState("alice", "session-001");
String json = state.toJson();
AgentState restored = AgentState.fromJsonString(json);

agent.clearContext("alice", "session-001");   // 保留 (uid,sid) 与权限/工具/任务/Plan 状态，只清对话与摘要
```

`clearContext` 要在该会话当前请求完成后调用；它不取消正在执行的调用。

> 1.0 的 `Memory` 接口（`InMemoryMemory` / `LongTermMemory`）在 2.0 已 `@Deprecated(forRemoval = true)`，新代码用 `AgentState.getContext()` + `AgentStateStore`。

### Per-session 中断

每次执行有独立、仅运行时存在的 `InterruptControl`，不进 `AgentState`、不随历史持久化：

```java
agent.interrupt("alice", "session-001");
agent.interrupt("alice", "session-001", new UserMessage("请停止。"));
```

空闲 session 不受影响。同 session 内，排队中的 B 与运行中的 A 各有独立信号。已废弃的无参 `interrupt()` 定位默认 session 的当前执行。

`AgentState.shutdownInterrupted` 是单独持久化的恢复标记；中断信号不传给后续执行，也不会被另一节点加载。

### 并发规则

| 情形 | 行为 |
| - | - |
| 不同 `(userId, sessionId)` | 完全并行，各自独立 `AgentState` |
| 相同 `(userId, sessionId)` | per-session 异步门按 FIFO 串行，无需外部锁 |
| `interrupt(userId, sessionId)` | 精确命中单个 session，其他在飞 call 不受影响 |

内存状态缓存随实例服务过的 session 数增长；几百个 session 可忽略，百万级才需要考虑 agent factory + 有界实例池。

## RuntimeContext —— per-call 元数据

`io.agentscope.core.agent.RuntimeContext`，在 `agent.call(msgs, ctx)` 传入，hook 与 tool 在本次调用期间共享。自由/类型属性**不持久化**；`sessionId` / `userId` 决定读写哪个 `AgentState` 槽位。

```java
RuntimeContext ctx = RuntimeContext.builder()
        .userId("alice")
        .sessionId("s-001")
        .put("request_id", "req-2026-06-01-abc")
        .put(MyTenantInfo.class, new MyTenantInfo("tenant-7"))
        .build();

Msg result = agent.call(List.of(new UserMessage("Hi")), ctx).block();
```

| 方法 | 说明 |
| - | - |
| `getSessionId()` / `getUserId()` | 内置字段，路由状态槽位与租户 |
| `getAgentState()` / `setAgentState(...)` | call-scoped 的 `AgentState`，框架在 call 入口注入 |
| `RuntimeContext.resolveAgentState(ctx, agent)` | 静态辅助：优先 `ctx.getAgentState()`，回退 `agent.getAgentState()` |
| `get(String)` / `put(String, Object)` | 字符串键存取 |
| `get(Class<T>)` / `put(Class<T>, T)` | 按类型存取 |
| `getExtra()` | 字符串属性 map（可变视图） |
| `RuntimeContext.empty()` | 空上下文 |

两条容易踩的坑：

- **存储后端在 builder 时绑定**，不能 per-call 切换实例；per-call 变的只是寻址的 `(userId, sessionId)` 槽位。
- **中间件和工具里一律用 `RuntimeContext.resolveAgentState(ctx, agent)`**。并发时 `agent.getAgentState()` 返回最后一次活跃 session 的状态（结果不确定），`ctx.getAgentState()` 才是本次 call 的。
