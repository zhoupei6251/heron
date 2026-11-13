# Agent 会话隔离

**日期：** 2026-11-13
**问题：** 两次研究之间会「记得」上一次的上下文——测试夹具的数据被当成真实历史引用。

---

## 一、根因：两条持久化通道，开关只挡住了一半

AgentScope 有两个落盘位置，**都不受 `disableSessionPersistence()` 控制**：

| 通道 | 路径 | 谁写 | 有效开关 |
|---|---|---|---|
| transcript | `./.agentscope/workspace/agents/<agentName>/sessions/*.jsonl` | `SessionTranscriptWriter` | `disableTranscript()` |
| **AgentState（真凶）** | `~/.agentscope/state/<agentName>/<userId>/<session>/agent_state.json` | `JsonFileAgentStateStore` | **没有开关** |

`JsonFileAgentStateStore` 里写死 `user.home + "/.agentscope/state"`，`HarnessAgent.Builder` 上没有
任何 state store 选项。也就是说：**只要 agent 名一样，下一次运行就会把上一次的完整上下文读回来。**

实测那份 `agent_state.json` 有 116KB，里面就是早前测试用的「机会 900」提示词，
而 17:20 那次研究把它读了进来。

---

## 二、修复

```java
.name("heron-research-" + Long.toString(System.currentTimeMillis(), 36))  // 每进程唯一
.disableTranscript()
.disableSessionPersistence()
```

为什么用「名字唯一」而不是「清状态」：状态路径由 agent 名决定，名字变了就读不到旧目录，
不需要删除任何文件、也不需要依赖框架提供不存在的开关。

---

## 三、验证：两组对照，两个进程

暗号法——第一轮让它记住 `ZEBRA-7391`，第二轮（另一个 JVM）问它。

| 组 | 第一轮 | 第二轮 | 结论 |
|---|---|---|---|
| 对照组（固定 agent 名） | `OK` | **`ZEBRA-7391`** | 会话被完整读回，污染确认 |
| 修复组（每进程唯一名） | `OK` | **`不知道`** | 隔离生效 |

同一份代码、同一个模型、同一个提示词，唯一差别是 agent 名。所以隔离是**名字**带来的，不是模型碰巧忘了。

测试状态目录已清理，`~/.agentscope/state/` 现在是空的。

---

## 四、这同时推翻了此前的一个结论

上一版报告里「Agent 编造了一条证据」是**我判断错了**。
它引用的「机会 900」「0.4712 序列」不是凭空生成，而是从 `agent_state.json` 里读回来的旧上下文
（那是 `AgentResearchRealRunTest` 里的测试夹具）。

**真问题在隔离，不在模型。** 已同步更正 `heron-real-opportunity-rerun.md` §三。

---

## 五、后续（已解决，见 `heron-agent-state-mysql.md`）

本节当时留了三个局限，现在都已处理或不再成立：

| 当时的局限 | 现在 |
|---|---|
| 同一进程内仍共享 | **已解决**：改成按会话标识创建 Agent（`ResearchAgentFactory`），同进程内 A/B 两会话互不串 |
| 状态目录会累积 | **不再存在**：AgentState 已落 MySQL 的 `heron_agent_state`，用户目录不再写文件 |
| 未验证生产 bean 的同款行为 | **已验证**：探针走的是 Spring 上下文里的 `ResearchAgentFactory`，不是手工装配 |

