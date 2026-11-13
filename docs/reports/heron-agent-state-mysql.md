# AgentState 落 MySQL

**日期：** 2026-11-13
**Phase：** 2（第一步）
**目的：** 把研究上下文从用户目录搬进数据库：多副本可共用、部署机不留目录、重启能续。

---

## 一、改了什么

| 层 | 改动 |
|---|---|
| 依赖 | `io.agentscope:agentscope-extensions-mysql:2.0.4` |
| 迁移 | `032-agent-state.sql` → 表 `heron_agent_state` |
| 配置 | `AgentRuntimeConfig` 新增 `heronAgentStateStore(DataSource)`，`@ConditionalOnMissingBean`；Agent 用 `.stateStore(...)` |
| 测试 | `AgentRuntimeConfigTest` 用 `InMemoryAgentStateStore` 替掉，单测不连库 |

**运行时建表是关掉的**（`initializeSchema=false`）：表由迁移建，符合项目既有约定。
库名不写死，取连接自身的 `catalog`。

---

## 二、表结构（由库的实现决定，非我设计）

```sql
CREATE TABLE heron_agent_state (
  session_id varchar(255) NOT NULL,   -- "<userId>:<sessionId>"，实测确认
  state_key  varchar(255) NOT NULL,   -- 固定 "agent_state"
  item_index int NOT NULL DEFAULT 0,
  state_data longtext NOT NULL,
  version    bigint NOT NULL DEFAULT 0,
  created_at datetime DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (session_id, state_key, item_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> 键的构成我先猜错了一次（以为是 `<userId>:<agentId>`）。真实数据是 `<userId>:<sessionId>`——
> 起隔离作用的是**会话标识**，不是 agent 名。


---

## 三、验证

用**生产装配**跑了一次真实研究（agent 从 Spring 上下文取，不是手工 new）：

```
AGENT_BEAN=heron-research-mwese7a4

heron_agent_state:
  session_id = "__anon__:heron-research-mwese7a4"
  state_key  = "agent_state"
  长度 22516 字节  version=1

~/.agentscope 本次无新增文件
```

**结论：状态确实从用户目录搬到了 MySQL。**

302 项测试全绿。

---

## 四、按 researchId 隔离（已做）

单例 Agent 的 `state_key` 固定是 `"agent_state"`，同一进程里所有研究共用一行。已改成：

```java
ResearchAgentFactory factory;              // 一个 Bean
factory.create("research-" + researchId);  // 每个研究一个 Agent，会话标识在构建时定死
```

`HarnessAgent` 没有带上下文的 `call` 重载，`defaultSessionId` 只能在构建时给——所以「按研究隔离」
必然意味着「每个研究一个 Agent 实例」。名字固定为 `heron-research`，隔离靠会话标识；
同一个会话标识再次进入就是有意的续跑。

### 验证（同一进程内，三段对话）

| 步骤 | 结果 |
|---|---|
| A 会话：「记住暗号 zbr7391」 | `OK` |
| **B 会话**：「暗号是什么？」 | **`不知道`** ← 隔离成立 |
| **A 会话（重新进入）**：「暗号是什么？」 | **`zbr7391`** ← 续跑成立 |

库里对应两行：

```
session_id = "__anon__:research-zbr7391-A"   version=2   （两次调用）
session_id = "__anon__:research-zbr7391-B"   version=1
```

同时加了保护：会话标识为空直接抛 `IllegalArgumentException`——空标识等于所有研究共用一段上下文。
测试 303 项全绿。


---

## 五、两个实现细节，值得记住

1. **存储构造是急切的**：`new MysqlAgentStateStore(...)` 当场就会查表校验，表不存在直接抛。
   所以表必须先建好——这也是我把它拆成独立 `@Bean` 的原因，测试里才能用内存实现替掉。
2. **别把库名传 null**：探针里传了 `null`，它自己建了个叫 `agentscope` 的**库**（root 权限下静默 CREATE DATABASE）。
   已清理。现在库名取连接 catalog，不会再发生。
