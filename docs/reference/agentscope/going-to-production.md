# 上生产（Going to Production）

> 上游：<https://java.agentscope.io/v2/zh/docs/others/going-to-production> · 快照：`_upstream/others-going-to-production.md` · 基线 `v2.0.4` · 抓取 2026-11-13
> 上游副标题：从单机原型到多副本分布式部署：AgentStateStore / Filesystem / Skill / Sandbox / 快照 / 观测的组件选型与配置清单

本页**只讲单机 → 分布式生产的差异**：哪些组件必须换、换成什么、为什么 builder 会在你漏配时直接抛 `IllegalStateException`。

> Heron 是单租户自部署，本页的多副本前提不适用——先读文末「Heron 现状」再决定要不要照做。

## 一键配置：`DistributedStore`

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis);
// 或 MysqlDistributedStore.create(dataSource);
// 或 OssDistributedStore.create(ossClient, bucket, prefix);

HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(...)  // 选择你的 workspace 模式
    .build();
```

混合 store（MySQL 管状态 + Redis 管 sandbox）：

```java
DistributedStore store = DistributedStore.builder()
    .agentStateStore(MysqlDistributedStore.create(ds).agentStateStore())
    .baseStore(MysqlDistributedStore.create(ds).baseStore())
    .sandboxSnapshotSpec(RedisDistributedStore.fromJedis(jedis).sandboxSnapshotSpec())
    .sandboxExecutionGuard(RedisDistributedStore.fromJedis(jedis).sandboxExecutionGuard())
    .build();
```

**aistio 托管 Store（备选）**：已部署 aistio 控制面时，可由它托管 BaseStore / 沙箱锁与快照 / MessageBus / AsyncToolRegistry / TaskRepository / 可选 SessionTurnGate，但**仍需自备 `AgentStateStore`**（Redis/MySQL/Postgres/OSS）。控制面开 `--enable-hosted-store`（生产建议 Postgres）；`withAgentStateStore` 已含托管 TaskRepository，SandboxFilesystem 模式下子 agent 的后台任务必须走这条路径。Redis/Postgres/MySQL/InMemory 的 `AgentStateStore` 支持 versioning CAS，其余后端是 LWW；多副本可选 turn gate + `ConflictPolicy.FAIL` 减少重复 turn，正确性靠 CAS。鉴权是共享 internal token、租户取自请求体，**不适用于同一控制面上互不信任的多租户**；`queueDrain` 是破坏性操作（读即 ack）。

## 一图速览

| 维度 | 单机默认 | 分布式生产替换 |
| - | - | - |
| 一键配置 | 不需要 | **`.distributedStore(RedisDistributedStore.fromJedis(jedis))`** |
| `AgentStateStore` | `JsonFileAgentStateStore` | 由 `distributedStore` 自动注入 |
| Filesystem | `LocalFilesystemSpec`（不配即此项） | `RemoteFilesystemSpec` 或 `SandboxFilesystemSpec` |
| Sandbox 快照 | `NoopSnapshotSpec` / `LocalSnapshotSpec` | 由 `distributedStore` 自动注入 |
| 沙箱执行串行化 | 单进程内即可 | 由 `distributedStore` 自动注入 |
| Skill 来源 | `workspace/skills/` | `GitSkillRepository` / `MysqlSkillRepository` / `NacosSkillRepository` |
| 观测 | 默认无 tracing | `OtelTracingMiddleware` + OpenTelemetry SDK |

### 能力矩阵

| 能力 | Redis (`agentscope-extensions-redis`) | OSS (`agentscope-extensions-oss`) | MySQL (`agentscope-extensions-mysql`) |
| - | :-: | :-: | :-: |
| `AgentStateStore` | `RedisAgentStateStore` | `OssAgentStateStore` | `MysqlAgentStateStore` |
| `BaseStore` | `RedisStore` | `OssBaseStore` | `JdbcStore` |
| `SandboxSnapshotSpec` | `RedisSnapshotSpec` | `OssSnapshotSpec` | `JdbcSnapshotSpec` |
| `SandboxExecutionGuard` | `RedisSandboxExecutionGuard` | —（对象存储不适合做锁） | `JdbcSandboxExecutionGuard` |

四个组件各解决一个问题：

- `AgentStateStore` —— 保存 session 运行时状态（对话历史、压缩摘要、权限规则、Plan Mode、tool state）。决定请求落到另一台机器或进程重启后能否继续同一个 `(userId, sessionId)`。
- `BaseStore` —— 给 `RemoteFilesystemSpec` 提供共享 KV 文件存储，承载 `MEMORY.md`、`memory/`、`skills/`、`sessions/`。多副本时让不同 pod 看到同一份长期记忆。
- `SandboxSnapshotSpec` —— 保存沙箱 workspace 快照，容器销毁 / pod 重启 / 换节点时恢复工作区，避免 `pip install` 与生成文件全丢。
- `SandboxExecutionGuard` —— 对同一 sandbox slot 的命令执行做跨节点串行化（`AGENT` / `GLOBAL` 共享 scope 下会用）。

> OSS 不提供 guard（对象存储不适合做锁）；需要时用 `DistributedStore.builder()` 混入 Redis 的 guard。

**核心校验链路**：

- `filesystem(RemoteFilesystemSpec)` + 没换 `stateStore` 也没配 `distributedStore` → `build()` 抛 `IllegalStateException`。
- `filesystem(SandboxFilesystemSpec)` + 本地 `AgentStateStore` → `build()` 通过但打 **warning**：沙箱状态不能跨 JVM 恢复。

## 1. 状态存储

`AgentState`（对话上下文、摘要、权限规则、Plan Mode、tool state）跨进程恢复的唯一通路就是 `AgentStateStore`。

| 实现 | 模块 | 何时使用 |
| - | - | - |
| `InMemoryAgentStateStore` | `agentscope-core` | 单测；进程退出全丢 |
| `JsonFileAgentStateStore` | `agentscope-core` | 单机开发；**HarnessAgent 默认**，根目录 `~/.agentscope/state/<agentId>/` |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | **多副本生产首选**；Jedis / Lettuce / Redisson（Standalone / Cluster / Sentinel） |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | 要把状态沉淀进关系库（审计 / 报表 / 联表） |

```java
// Jedis Standalone
AgentStateStore stateStore = RedisAgentStateStore.builder()
        .jedisClient(new JedisPooled("redis://localhost:6379"))
        .keyPrefix("myapp:session:")
        .build();

// Lettuce Cluster（写多读少更顺）
// .lettuceClusterClient(RedisClusterClient.create(...))

// Redisson（已在用 Redisson 时）
// .redissonClient(redisson)
```

**按租户隔离**：只用 `sessionId` 够单租户。生产应在每次调用的 `RuntimeContext` 上同时设 `userId` 与 `sessionId`，存储按二元组寻址（Redis 折进 key，MySQL 折进主键）。其他维度（租户、agent）自己拼进字符串：

```java
agent.call(msg, RuntimeContext.builder()
        .userId(tenantId + ":" + userId)
        .sessionId(agentId + ":" + sessionId)
        .build()).block();
```

## 2. Filesystem 与 IsolationScope

| 模式 | 配置 | 提供 shell？ | 适用 |
| - | - | - | - |
| 本机 + shell | `LocalFilesystemSpec` 或不配 | ✅ 宿主 `sh -c` | 单进程 / 信任环境 |
| 共享存储 | `RemoteFilesystemSpec(store)` | ❌（要 shell 走沙箱） | 多副本 / 多 pod 共享长期记忆 |
| 沙箱 | `DockerFilesystemSpec` 等 5 种 | ✅ 沙箱内 | 不可信代码 / 跨调用恢复 / 多用户隔离 |

| Scope | 含义 | 典型场景 |
| - | - | - |
| `SESSION`（沙箱默认） | 每个 sessionId 独立 slot | 多用户 SaaS，每段对话独立 |
| `USER`（Remote 默认） | 同 `userId` 跨 session 共享 | 同一用户多设备共享长期记忆 |
| `AGENT` | agent 内所有用户共享 | 公共知识库型 agent |
| `GLOBAL` | 全局一个 slot | 谨慎 |

```java
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER)
            .anonymousUserId("_default"))   // 未传 userId 时的 fallback
    .build();
```

`anonymousUserId` 是生产细节：调度器触发、admin 操作等场景 `userId` 可能为 null，fallback **别用空字符串**，否则所有匿名调用聚到一个共享桶。

## 3. Remote 模式的 BaseStore

| 实现 | 依赖 | 并发安全 | 适用 |
| - | - | - | - |
| `RedisStore` | `agentscope-extensions-redis` | Lua 实现 CAS `putIfVersion`，`ZRANGEBYLEX` 做 prefix search | 主推；多副本共享 |
| `JdbcStore` | `agentscope-extensions-mysql`；MySQL / PostgreSQL / SQLite / H2 自动判别 | 单语句 CAS UPDATE | 已有关系库 / 需要联表 |
| `InMemoryStore` | — | — | 测试 |

```java
DistributedStore store = RedisDistributedStore.fromJedis(
        new JedisPooled("redis://prod-redis:6379"));

HarnessAgent agent = HarnessAgent.builder()
        .name("multi-tenant-agent").model(model).workspace(workspace)
        .distributedStore(store)                              // 注入 stateStore + baseStore
        .filesystem(new RemoteFilesystemSpec()
                .isolationScope(IsolationScope.USER)
                .workspaceIndex(WorkspaceIndex.open(workspace)))   // 加速 ls/glob
        .build();
```

### 不要把 OSS 当 KV 用

`MEMORY.md` / `memory/YYYY-MM-DD.md` / `agents/<id>/context/<sid>/` 每秒可能写几次，OSS 的延迟与 per-request 成本会立刻失控。正确分工：

| 数据形态 | Store | 谁来管 |
| - | - | - |
| 高频小 KV（记忆、会话快照、任务记录） | Redis / MySQL（`BaseStore`） | `RemoteFilesystemSpec` |
| 大对象（沙箱整个 workspace tar，几十 MB） | OSS / S3 | `OssSnapshotSpec` / 自定义 `RemoteSnapshotSpec` |
| 跨节点共享卷（多沙箱挂同一目录） | NAS / EFS | `AgentRunFilesystemSpec.nasConfig(...)`（仅 AgentRun 原生支持） |

### 路由表与 CompositeFilesystem

| Workspace 路径 | 命名空间段 |
| - | - |
| `AGENTS.md` / `MEMORY.md` / `tools.json` | `root` |
| `memory/` | `memory` |
| `skills/` | `skills` |
| `subagents/` | `subagents` |
| `knowledge/` | `knowledge` |
| `agents/<agentId>/sessions/` | `sessions` |
| `agents/<agentId>/tasks/` | `tasks` |
| `.addSharedPrefix("prompts/")` 等额外目录 | 自动派生 |

每段下再按 `IsolationScope` 切桶（`USER` → `agents/<agentId>/users/<userId>/`）。Redis key 大致长成 `agentscope:store:item:agents\0X\0users\0alice\0memory\0memory/2026-06-02.md`。

`RemoteFilesystemSpec.toFilesystem(...)` 实际产出 `CompositeFilesystem`：底层是不带 shell 的 `LocalFilesystem`（兜底读本地模板），顶层每条路由是一个 `OverlayFilesystem`（远端 + 只读本地模板）。**写永远落 Remote，读优先 Remote 再退本地模板**——即 [harness-workspace.md](harness-workspace.md) 的两层读架构在 Remote 模式的具体形态。

`WorkspaceIndex` 是可选的 SQLite 索引（落在 `<workspace>/.index/`），加速 `ls` / `glob` / `exists` / `grep`，不开就每次全表扫 KV；失败会自动降级。

## 4. Skill 集中管理

四层优先级从低到高：`projectGlobalSkillsDir` → `skillRepository` → `workspace/skills/` → `<userId>/skills/`。

| Repository | 模块 | 特点 | 推荐场景 |
| - | - | - | - |
| `GitSkillRepository` | `agentscope-extensions-skill-git-repository` | 团队 git 仓库；HEAD 变化才拉；只读分发 | 早期 / 小团队；改 skill 走 git PR |
| `MysqlSkillRepository` | `agentscope-extensions-skill-mysql-repository` | `writeable(true/false)` 双模式；可从 agent 侧写回 | 平台统一治理；多团队多 agent |
| `NacosSkillRepository` | `agentscope-extensions-nacos-skill` | 从 Nacos 加载，支持指定版本或标签 | 集中管理版本和标签 |
| `ClasspathSkillRepository` | `agentscope-core` | 跟 JAR 一起发；Fat JAR 兼容 | 产品内置不可改的能力包 |

```java
HarnessAgent agent = HarnessAgent.builder()
        .skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
        .skillRepository(MysqlSkillRepository.builder(dataSource)
                .databaseName("agentscope")
                .skillsTableName("skills")
                .createIfNotExist(true)
                .writeable(false)                  // 只读分发，生产建议
                .build())
        .build();
```

`skillRepository(...)` 可重复调用，后注册的优先级更高、同名覆盖。

**生产 checklist**：

- 优先 `MysqlSkillRepository(writeable=false)` 或 `NacosSkillRepository`，agent 端只读，写回走管理台 + 审核流。
- 不想让 agent 看到 `workspace/skills/` → `.disableDefaultWorkspaceSkills()`。
- 开 `enableSkillManageTool` 让 agent 自起草 skill 时**必须**配 `enableSkillPromotionGate(...)`；生产**严禁** `autoPromote=true`。
- `NacosSkillRepository` 建议指定版本或生产标签，避免测试版本影响线上；更新后需重新加载。

## 5. 需要 shell：沙箱 + 快照

必走沙箱的三种情况：模型可能跑不可信代码；需要跨调用恢复整个工作目录（`node_modules`、生成文件、`pip install` 后的环境）；多用户硬隔离。

| Spec | 模块路径 | 适用 |
| - | - | - |
| `DockerFilesystemSpec` | `io.agentscope.harness.agent.sandbox.impl.docker` | 单机 / 本地集群；最熟悉 |
| `KubernetesFilesystemSpec` | `...impl.kubernetes` | 已跑 K8s；走 pod / Job |
| `DaytonaFilesystemSpec` | `...impl.daytona` | Daytona 服务（开发环境即服务） |
| `E2bFilesystemSpec` | `...impl.e2b` | E2B 云沙箱；最快上云 |
| `AgentRunFilesystemSpec` | `...impl.agentrun` | 阿里云 AgentRun；原生 NAS / OSS mount |

**Snapshot 是沙箱的分布式生命线**：沙箱默认瞬时，下次 `call()` 可能起在另一个节点的新容器里。

| Spec | Store | 模块 | 何时用 |
| - | - | - | - |
| `NoopSnapshotSpec` | — | `agentscope-harness` | 生产别用 |
| `LocalSnapshotSpec(Path)` | 本地 tar | `agentscope-harness` | 单机调试 |
| `OssSnapshotSpec` | 阿里云 OSS | `agentscope-extensions-oss` | **大对象首选** |
| `RedisSnapshotSpec` | Redis | `agentscope-extensions-redis` | 小工作区 + 短 TTL（注意内存代价） |
| `JdbcSnapshotSpec` | MySQL BLOB | `agentscope-extensions-mysql` | 已有关系库、不想加中间件 |
| 自实现 `RemoteSnapshotClient` → `RemoteSnapshotSpec` | S3 / GCS / MinIO | — | 不在内置列表 |

```java
DistributedStore redisStore = RedisDistributedStore.fromJedis(jedis);
DistributedStore ossStore = OssDistributedStore.create(
        ossClient, "agentscope-sandbox-snapshots", "prod/");

DistributedStore store = DistributedStore.builder()
        .agentStateStore(redisStore.agentStateStore())
        .baseStore(redisStore.baseStore())
        .sandboxSnapshotSpec(ossStore.sandboxSnapshotSpec())   // 大快照放 OSS
        .sandboxExecutionGuard(redisStore.sandboxExecutionGuard())
        .build();
```

**执行锁**：`SESSION` / `USER` 天然按 session/user 分桶，并发不撞；`AGENT` / `GLOBAL` 多副本时 N 个节点可能在同一个 slot 上 `exec`。

| 实现 | 模块 | 机制 |
| - | - | - |
| `RedisSandboxExecutionGuard` | `agentscope-extensions-redis` | Redis `SET NX PX` 租约 |
| `JdbcSandboxExecutionGuard` | `agentscope-extensions-mysql` | MySQL `GET_LOCK()` / `RELEASE_LOCK()` |

只有要自定义锁参数（租约 TTL）时才在 spec 上显式覆盖，其余交给 `distributedStore`。也可实现 `SandboxExecutionGuard` 接 Zookeeper / etcd。

**Workspace projection**：默认把 `AGENTS.md, skills, subagents, knowledge, .skills-cache` 五个 root 打 tar 在沙箱启动时 hydrate（内容 hash 比对、增量重写）。用 `workspaceProjectionRoots(List.of(...))` 调整，`workspaceProjectionEnabled(false)` 关掉。

**AgentRun 特有**：唯一原生支持多沙箱实例共享同一目录（NAS mount）的实现——业务是「同一用户不同 session 看到同一份 workspace」时，比每次 hydrate snapshot 更高效：

```java
.filesystem(new AgentRunFilesystemSpec()
        .apiKey(System.getenv("AGENTRUN_API_KEY"))
        .accountId(System.getenv("ALI_ACCOUNT_ID"))
        .region("cn-hangzhou")
        .templateName("python-3.12")
        .nasConfig(new AgentRunNasMountConfig().fileSystemId("...").mountTargetDomain("...").mountDir("/workspace"))
        .addOssMount(new AgentRunOssMountConfig().bucketName("data").mountDir("/mnt/oss")))
```

## 6. 多副本部署 checklist

| 关注点 | 推荐组合 |
| - | - |
| 会话 / `AgentState` | `RedisDistributedStore` 或混合 `DistributedStore` 注入；`(userId, sessionId)` 承载租户/用户/agent 维度 |
| 工作区文件 | `distributedStore(...)` + `RemoteFilesystemSpec` + `WorkspaceIndex` + `IsolationScope.USER` |
| 大对象 / 快照 | 混合 store 里用 `OssDistributedStore.sandboxSnapshotSpec()`（**别把大快照写 Redis**） |
| 跨节点 sandbox 共享 | AgentRun + NAS mount，或自管 K8s + 注入的 `SandboxExecutionGuard` |
| Skill 治理 | `MysqlSkillRepository(writeable=false)` 或 `NacosSkillRepository`；禁用 autoPromote |
| 子 agent 任务记录 | 自动用 `WorkspaceTaskRepository`，落 Remote / Sandbox，不需额外配 |
| 暴露的子 agent | 注册表由 `distributedStore` 接好，`subagentId` 任意副本可解析；把某 `subagentId` 的消息路由回同一节点（粘性）可让恢复只作故障切换兜底；`GatewayBootstrap` 要传 `.distributedStore(...)` |
| 优雅停机 | `GracefulShutdownManager`（默认注册 JVM hook）；接好 SIGTERM；视需要 `setConfig(...)` 调 inflight 等待 |
| 可观测 | `OtelTracingMiddleware` + OpenTelemetry SDK + OTLP exporter |
| 限流 | 自写 `MiddlewareBase`（`onModelCall`），见 [middleware.md](middleware.md) |

## 7. 完整生产模板

Agent 在调用之间无状态，**单例即可服务并发请求**；每次 `call()` 靠 `RuntimeContext` 的 `(userId, sessionId)` 定位状态。

```java
Path workspace = Paths.get("/var/agentscope/workspace");
JedisPooled jedis = new JedisPooled(System.getenv("REDIS_URI"));
DistributedStore store = RedisDistributedStore.fromJedis(jedis);

HarnessAgent agent = HarnessAgent.builder()
        .name("coding-assistant")
        .model("dashscope:qwen-plus")
        .workspace(workspace)
        .distributedStore(store)
        .filesystem(new DockerFilesystemSpec()
                .image("python:3.12-slim")
                .isolationScope(IsolationScope.USER))
        .compaction(CompactionConfig.builder()
                .triggerMessages(50)
                .keepMessages(20)
                .build())
        .toolResultEviction(ToolResultEvictionConfig.defaults())
        .skillRepository(MysqlSkillRepository.builder(skillsDataSource())
                .createIfNotExist(false).writeable(false).build())
        .middlewares(List.of(new OtelTracingMiddleware()))
        .build();
```

```java
agent.call(msg, RuntimeContext.builder()
        .userId(httpRequest.tenantUserId())
        .sessionId(httpRequest.sessionId())
        .build()).block();
```

## 8. 常见坑位

- **忘记传 `RuntimeContext`** —— 不传 `sessionId` 时所有请求共享 `defaultSessionId`，串台。多用户场景每次 `call()` 都要传 `userId` + `sessionId`。
- **用 `java.nio.Files` 写工作区** —— 沙箱 / Remote 模式下落错位置。永远走 `agent.getWorkspaceManager()`。例外：builder 装配期的种子文件（`initWorkspaceIfAbsent` 之类），那时还没运行时上下文，用 `java.nio.Files` 是对的。
- **`tools.json` 的 `allow` 会过滤内置工具** —— 白名单里务必保留 `read_file` / `memory_search` / `agent_spawn`，否则整套内置工具一起被砍。
- **`IsolationScope` 改了旧数据不会自动迁移** —— 上线前定下来，改了等于换命名空间。
- **分布式文件系统 + 本地 `AgentStateStore`** —— K8s 多副本第一次 build 就抛 `IllegalStateException`，**设计如此**。
- **OSS / NAS 走完 IAM 再上线** —— AK/SK 是平台凭证，用 RAM Role + STS 临时凭证更稳。
- **本地 `AgentStateStore` + 沙箱仅用于开发** —— build 时的 warning 是故意的。

## Heron 现状

**本页清单对 Heron 基本不适用**，原因是形态不同：

| 上游前提 | Heron 实际 |
| - | - |
| 多副本水平扩展、任意 pod 接住任意用户 | 单实例自部署，`ruoyi-admin` 单进程（`heron.jar`） |
| 多租户、按 `userId` 分桶 | 单租户，用户身份由基座 Sa-Token 管理，不进 agent |
| 分布式 `AgentStateStore` | 默认 `JsonFileAgentStateStore` 落在 `~/.agentscope/state/<agentName>/`，且代码里用「每次启动唯一 agentName」避免读回旧状态 |
| 共享存储 / 沙箱 | 都没开：`disableSessionPersistence()`、`disableTranscript()`、`disableFilesystemTools()`、`disableShellTool()` |

所以只有三条与 Heron 无关但值得记住的结论：

1. 一旦 Heron 改成多副本（例如 k8s 双实例），**必须**同步换 `distributedStore` + 分布式 `stateStore`，否则会话会在实例间漂。
2. 上游把所有 `extensions-redis/mysql/oss` 都拆成独立 artifact，Heron 没引任何一个；要用得先加依赖（见 `ruoyi-modules/ruoyi-heron/pom.xml`）。
3. 基座已有 Redis / MySQL / OSS 的配置与客户端（`ruoyi-common-redis` / `-mybatis` / `-oss`），真要接分布式 store，优先复用基座连接而不是新建一套凭据——这与项目「不建第二套存储配置」的约定一致。
