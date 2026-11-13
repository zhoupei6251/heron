# 机会快照落库

**日期：** 2026-11-13
**目的：** 机会必须能被事后复核——源会变，只存派生信号等于永久丢掉证据。

---

## 一、改了什么

| 层 | 改动 |
|---|---|
| 迁移 | `031-opportunity-snapshot.sql`：`heron_data_opportunity` 加 `snapshot_json longtext` |
| 实体 | `HeronDataOpportunity.snapshotJson` |
| 服务 | `snapshotForStore()`：存形状、契约、`retrieved_at`、`data_sha256`，点只留尾部 120 个；超 64KB 时连点都不留，只写元信息并标注 |
| 写入 | 新建与同日重算都刷新快照（数据变了，判断依据也要跟着变） |
| 工具 | `query_opportunity_detail` 返回 `snapshotRetrievedAt` / `snapshotDataSha256` / `snapshotSourcePoints` / `snapshotTruncated` / `snapshotPoints` |

**测试：** 302 项全绿（新增 2 项：尾部截取与截断标记、详情里能读到快照）。

---

## 二、验证

重跑分析后，7 条机会全部带快照，每条约 1.9KB、65 个点：

```
frankfurter-audcny  长度 1909  源点数 65  存点 65  截断 false
frankfurter-jpycny  长度 1912  源点数 65  存点 65  截断 false
（其余五条同形）

样例首末点：["2026-08-14", 4.7742] ... ["2026-11-12", 4.5483]
```

随后用同一个真实机会重跑了一次研究，Agent 这次能拿到数据，并做了三件有价值的事：

1. **逐点复算**：`4.5483 - 4.6598 = -0.1115`，`-2.3928%`，与记录吻合；
2. **指出 `coverage=0.542`**（65/120），因此「120 期窗口新低」这个说法不成立，只能说「至少自 8 月下旬最低」；
3. **给出可拍的角度**：AUD/CNY -2.39% 对比 EUR/CNY -0.94%，主线是「澳元自身更弱」。

这正是快照存在的意义：**它现在能自己核对，不必信 headline。**

---

## 三、验证过程中发现的两个问题（比功能本身更重要）

### 3.1 工具读错了键，导致「源是空的」这个错误结论

`dataset/1` 的数据点在 **`rows`** 下，而 `query_trend_series` 一直读的是 `points`。
于是工具**永远返回 0 个点**，Agent 据此判断「源返回空」，我又据此认定「frankfurter 源坏了」并去改了域名。

域名 301 是真的（`api.frankfurter.app` → `api.frankfurter.dev/v1`），但它**不是**那次 0 点的原因。
已修：工具与快照都改读 `rows`，并额外返回 `metric` / `unit`（之前读 `metric` 字符串，实际是对象，所以永远是空）。

**教训：工具返回「空」时，先怀疑工具读错了字段，再怀疑上游。**

### 3.2 会话记录跨轮污染

仓库根出现了 `heron/.agentscope/workspace/agents/heron-research/sessions/default.jsonl`，
里面有上一轮测试夹具的「机会 900」和 `0.4712`。

`disableSessionPersistence()` **没有阻止会话被持久化并重新加载**——两次运行共用 `sessionId=default`，
于是第二次研究「记得」第一次的内容，还把测试数据当成真实历史引用。

我已据此推翻了上一版报告里「模型编造证据」的结论：**它是读到了旧会话，不是凭空生成。**
会话目录已清理，`.agentscope/` 已在 `.gitignore` 里。

---

## 四、还没做的

| 项 | 说明 |
|---|---|
| 会话隔离 | 每次研究应有独立 `sessionId`（按 `researchId`），否则同类污染会反复发生 |
| 信号口径说明 | `signals` 没写 z 是拿什么算的（水平值 vs 变动率），Agent 用变动率复算得出 -2σ，与 -3.6σ 不是同一统计量，但它无从判断 |
| 快照大小上限的实测 | 目前 65 点约 1.9KB；`rows` / `geo` 形状的大结果还没遇到过，64KB 上限没被触发过 |
