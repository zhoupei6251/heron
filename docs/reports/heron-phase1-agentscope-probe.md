# AgentScope 接入探针报告

**日期：** 2026-11-13
**阶段：** Phase 1 前置冒烟（未实施业务代码）
**结论：** ❌ **AgentScope 2.0.4 无法与基座自带的 spring-ai 2.0.2 共处同一个 classpath。**
不是版本没对齐，是两套 JSON 生态。

---

## 一、一句话

`agentscope` 用 Jackson 2，`spring-ai` 用 Jackson 3；两者都要通过 `victools` 从 Java 类型派生 JSON Schema，
而 victools 5.0 为了支持 Jackson 3 **把返回类型换了**。同一份代码在两套生态里无法都满足。

---

## 二、证据链（按发现顺序）

### 2.1 依赖树：没有 Netty，但有三处降级

用「临时摘依赖跑基线 → 装回 → diff」的方式确认（`-Dexcludes` 对 `dependency:tree` 不生效）：

| 库 | 基线（spring-ai 2.0.2 带来） | 加 agentscope 后 |
|---|---|---|
| `io.modelcontextprotocol.sdk:mcp` | 2.1.0 | **0.17.2** |
| `com.github.victools:jsonschema-generator` | 5.0.0 | **4.38.0** |
| `com.networknt:json-schema-validator` | 3.0.1 | **2.0.0** |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | 5.3.2 |

Netty 条数：基线 16，加 agentscope 后仍是 16 → **不是它带进来的**（来自 `spring-boot-netty:4.1.2` 与 `aws-sdk netty-nio-client`）。

### 2.2 spring-ai 在这个应用里是活的

```
ruoyi-admin/pom.xml:68   → ruoyi-common-mcp → spring-ai-starter-mcp-server-webmvc
ruoyi-admin/pom.xml:97   → ruoyi-ai → ruoyi-common-ai → snail-ai-agent-executor-model → spring-ai
application.yml          → spring.ai.mcp.server.enabled: true（端点 /mcp，与 Jetty 共端口）
```

heron 业务代码对 `org.springframework.ai` / `aizuda` 的引用数：**0**。但它在 classpath 上，且在启动时初始化。

### 2.3 排除冲突项后，跑起来就炸

给 agentscope 加 6 个 exclusion，让 spring-ai 的版本赢，然后写探针单测（`Toolkit.registerTool` + `getToolSchemas`）：

```
NoSuchMethod: 'com.fasterxml.jackson.databind.node.ObjectNode
  com.github.victools.jsonschema.generator.SchemaGenerator.generateSchema(
      java.lang.reflect.Type, java.lang.reflect.Type[])'
```

### 2.4 根因：victools 5.0 换的是返回类型

```
victools 4.38.0:  com.fasterxml.jackson.databind.node.ObjectNode generateSchema(Type, Type...)
victools 5.0.0:   tools.jackson.databind.node.ObjectNode        generateSchema(Type, Type...)
```

`com.fasterxml.jackson` = Jackson 2；`tools.jackson` = Jackson 3（本仓库 heron 模块有 54 个文件在用）。

- agentscope-core 2.0.4 依赖 `com.fasterxml.jackson.core:jackson-databind`
- spring-ai-model 2.0.2 有 **33 处**类引用指向 victools，且是 Jackson 3 版本
- **agentscope 2.1.0-SNAPSHOT 仍然依赖 `com.fasterxml.jackson.core:jackson-databind`**（已查该版本 pom）

所以这个冲突不是「升级一下就好」，方向相反：谁赢另一个就死。

---

## 三、其余发现（与冲突无关，但真实）

| 发现 | 含义 |
|---|---|
| `harness/agent/tool/ShellExecuteTool` | 框架**内置 shell 执行工具** |
| `harness/agent/tool/FilesystemTool` | 内置文件系统工具 |
| `harness/agent/tools/` 另有 `McpServerRegistrar`、`SkillManageTool` 等 | 内置工具面比预期大 |
| `Builder.disableShellTool()` / `disableFilesystemTools()` / `disableWebTools()` / `disableDynamicSkills()` / `disableSubagents()` | **都有开关**，能关干净 |
| `org.xerial:sqlite-jdbc` | 会话/断点很可能落 SQLite（`disableSessionPersistence()` 存在，说明有持久化） |
| `io.github.bonede:tree-sitter-bash` | native 库，用于 bash 解析 |
| `Model` 接口只有 2 个方法（`stream` / `getModelName`） | 测试可用假模型，不需要真 key |

**好消息：** 安全边界可控；`HarnessAgent` 的装配不需要真模型；`@Tool` API 与文档一致。

---

## 四、三条路与代价

| 方案 | 做法 | 代价 | 判断 |
|---|---|---|---|
| **A. 摘掉基座 AI 栈** | 从 `ruoyi-admin` 去掉 `ruoyi-common-mcp`、`ruoyi-ai`；heron 去掉 `ruoyi-common-ai`；关掉 `spring.ai.mcp` 配置；前端 `views/ai/chat`、`monitor/snailai` 一并下线 | 丢掉基座的 AI 聊天界面与 `/mcp` 端点；属基座改动，**需你授权**；`ruoyi-demo` 也依赖 common-mcp，会跟着动 | 技术最干净，但这是产品取舍 |
| **B. 独立进程** | agentscope 单独跑一个 JVM/服务，HTTP 通信 | 多一个部署单元、一套 IPC 契约；失去「进程内」；`FrozenContext` 等对象要序列化跨进程 | 两边都要时唯一干净解 |
| **C. 暂时不上 agentscope** | 保持现有栈；改用现有 `OpenAiModelClient` 做单次调用 | 没有工具循环/多轮调查 | 最省，但拿不到「自主调查」 |

**不推荐：** 用 shade 把 agentscope 的 Jackson 2 / victools 4 重定位打包。能跑，但维护成本高，升级一次要重做一遍。

---

## 五、我的建议

**先别选 A。** 理由是它看起来最干净，实际是拿掉一个基座已交付的功能面（AI 聊天 + MCP 端点）来给第二个 Agent 框架腾地方——这是产品决策，不该由技术冲突顺手决定。

**如果这个项目的目标确实是「自主调查」，那 B 是唯一两全的路；如果可以先不做工具循环，C 成本最低。**

有一个前置问题值得先回答：**基座的 snail-ai / MCP 你们到底用不用？**
- 代码层面：`ruoyi-common-ai` 只有一个 `@AutoConfiguration`，`ruoyi-modules/ruoyi-ai` 只有一个 controller，heron 业务 0 引用。**看起来是壳。**
- 功能层面：`/mcp` 端点开着、前端挂着 AI 聊天页。**看起来是留着的口子。**

这两种读法对应 A 与 C，需要你确认。

---

## 六、本阶段状态

- **pom 已恢复**，工作区没有残留依赖改动。
- 探针测试**已删除**（离开 agentscope 依赖无法编译），证据全部落在这份文档里。
- 未修改任何业务代码、未建表、未改配置、未重启服务。
- **未验证**：本环境没跑过完整应用启动，所以「加依赖后应用起不来」不是实测结论，是「工具 schema 生成必然失败 + MCP 服务端开着」推导出的判断。真要确认，得在改配置的前提下起一次应用。

---

## 七、解决：把基座 AI 栈移出运行时（2026-11-13 当天完成）

**决定：** snail-ai 与 MCP 的**代码保留**，但不再作为 Heron 的核心 AI 方向，因此不再装配进应用。

### 7.1 改了什么

| 文件 | 改动 |
|---|---|
| `ruoyi-admin/pom.xml` | `ruoyi-common-mcp`、`ruoyi-ai`、`ruoyi-demo` 三个依赖**注释停用**（附原因，放开即恢复） |
| `ruoyi-modules/ruoyi-heron/pom.xml` | 注释停用 `ruoyi-common-ai`；新增 `io.agentscope:agentscope-harness:2.0.4`（**不需要任何 exclusion**） |
| `ruoyi-admin/.../application.yml` | `spring.ai.mcp.server.enabled: true → false`，并注明当前不生效 |

`ruoyi-demo` 一并停用，是因为它的 `McpDemoClientService` 是无条件 `@Service`，只要 common-mcp 不在就会启动失败。

未删除任何模块、任何源码：`ruoyi-common-mcp`、`ruoyi-common-ai`、`ruoyi-ai`、`ruoyi-demo` 都完整保留在仓库里。

### 7.2 验证结果

```
依赖树（ruoyi-admin -am）
  org.springframework.ai  : 0
  victools                : 仅 4.38.0（单一版本）
  modelcontextprotocol    : 仅 0.17.2（单一版本）
  agentscope              : 2.0.4

测试
  AgentScope 探针        : 2 项通过
  heron 默认组           : 292 项，0 失败，0 错误，5 skipped，BUILD SUCCESS
```

探针两项都过了，说明：

1. `@Tool` 的 schema 派生在 victools 4.38.0 上正常；
2. `disableShellTool()` / `disableFilesystemTools()` / `disableWebTools()` 之后，工具集里**只剩我们注册的那个只读工具**，没有 shell、没有文件系统。

### 7.3 副作用（需要知道，但不在本次处理）

| 副作用 | 说明 |
|---|---|
| `/mcp` 端点消失 | 服务端配置已置 false，依赖也不再装配 |
| 前端 `views/ai/chat`、`views/monitor/snailai` 无后端 | 页面与菜单仍在，点开会 404；是否下线由产品决定 |
| 前端 `views/demo/*` 无后端 | 同上 |
| 恢复方式 | 放开 `ruoyi-admin/pom.xml` 里三段注释即可，但**恢复后 agentscope 必须再次去掉**（冲突会回来） |

### 7.4 仍未验证

- 未启动过应用。依赖树与单测层面已无冲突，但「Spring 上下文能否带 agentscope 正常起来」是 Phase 1 的事。
- 未验证 `ruoyi-ai` / `ruoyi-demo` 里是否有其它运行时副作用（如菜单、定时任务）：目前只确认了 admin 与 heron 源码对它们零引用。

