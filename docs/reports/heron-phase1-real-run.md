# Phase 1：一次真实研究跑通

**日期：** 2026-11-13
**模型：** 默认 `deepseek-v4.1-flash-economy`（Ark 兼容端点），凭据走 `HERON_REAL_MODEL_KEY`
**测试：** `AgentResearchRealRunTest`（`@Tag("render")`，默认组不跑）
**耗时：** 31.93 秒，`maxIters=8` 内结束

---

## 一、这一跑回答了什么问题

Phase 1 唯一要回答的是：**模型拿到一条机会，会不会自己决定去多查数据。**

**答案：会，而且查了两次。**

调用序列（从输出里可读出）：

```
query_opportunity_detail(900)
query_trend_series(frankfurter-audcny, points=40)
query_trend_series(frankfurter-audcny, points=60)
```

两次查询是因为它发现只拿到 8 个点，怀疑是取数不足，于是又试了更大的点数。

---

## 二、更值得注意的一件事：它拒绝了一个看起来漂亮的选题

输入的机会是这样的（这条是从真实链路上跑出来的形态）：

> 澳元兑人民币近 7 日 -2.39%，跌破 30 日均值 1.8 个标准差，评分 69.94

一个"会写文案的模型"大概会直接写成"汇率创 X 月新低"。它给的是 `OBSERVE`，理由原文：

> 该数据源最多只提供 8 个点……我拿不到那 30 日均值基线，因此按当前可见数据无法核验 -1.8σ 这个关键数字；
> 剥离 z-score 后，剩下的可见事实只是 7 天累计 -2.39% 的平滑单边下行，且没有单日跳变，作为 60 秒视频的画面不够有力。

它还自己做了交叉核对：

> `abs_change=-0.0114` 与逐点差值合计 -0.0113 基本吻合。口径在"幅度"这一项上是可信的。

以及：

> `merged_sources` 只有 `frankfurter-audcny` 一个……没有第二来源可以印证这个 -1.8σ。

**这是本次实验最有价值的一条证据。** 它说明这个 Agent 不是"给个数字就编故事"：

1. 它不接受元数据里自报的统计量，要求能看到基线；
2. 它会把 headline 拆成可核验与不可核验两部分；
3. 它在证据不足时选 `OBSERVE` 而不是 `PRODUCE`。

如果它上来就给 `PRODUCE`，这套多 Agent 架构后面的四个角色设计就要重估——因为那说明瓶颈在数据质量而不在推理层。

---

> **更正（同日稍晚）：** 本节与第二节的「基线拿不到」是**我的测试桩造成的**——桩里只有 8 个点，
> 真实 `frankfurter-audcny` 有 **65 个点**，足够算 30 日基线（见 `heron-data-opportunity-first-run.md`）。
> 所以「短板在证据供给」这条判断前提不成立，模型这次判 `OBSERVE` 不能当作它能力不足的证据。
> 用真实机会 id 重跑才是有效检验。

## 三、它暴露了我工具设计的一个缺口（已修）

它两次请求 40、60 个点，实际每次都只有 8 个。**它没法从返回里区分「源只有 8 个点」和「我请求少了」**——因为返回值里只有 `points` 数组，长度 8。

这是真实的契约缺口，不是模型的问题。已改：

```java
result.put("requestedPoints", bounded);
result.put("returnedPoints", snapshot.path("points").isArray() ? snapshot.path("points").size() : 0);
```

改动 3 行，测试补了对应断言。这样模型一次调用就能判断"历史不够"还是"我没要够"，不用试第二次。

**顺带说明为什么会出现这个问题：** 原来的返回值里，`points`（我按预算截取的末尾点数）和源的真实长度是同一个字段，
模型无法区分。这类信息缺失在别处也会重演——工具返回要同时给「你要的」和「实际有的」。

---

## 四、成本与边界

| 项 | 值 |
|---|---|
| 单次研究耗时 | 31.93 s |
| 模型调用轮数 | ≤ 8（`maxIters` 上限，本次未触顶） |
| 工具调用次数 | 3（1 次机会详情 + 2 次序列） |
| 出网 | 仅模型端点；数据是造的不出网 |
| 库 | 未连库，工具协作者是 mock |

---

## 五、前提更正：库里有数据，缺的只是机会表

> **本节已更正（2026-11-13 晚）。** 初版写的是「库里基本是空的」，那是**错的**——
> 依据来自 MCP 的 `information_schema` 查询，而那个连接看不到完整的表清单。改用 JDBC 直连 3307 复核后：

| 表 | 真实行数 | 说明 |
|---|---:|---|
| `heron_data_source` | 119 | 数据源注册表本来就有 |
| `heron_news_candidate` | 2317 | 新闻线索一直在采 |
| `heron_news_work` | 12 | 有稿子 |
| `heron_video_version` | 15 | 出过 15 个版本 |
| `heron_production_task` | 26 | 跑过 26 次生产 |
| `heron_auto_slot` | 7 | 槽位记录 |
| `heron_data_opportunity` | 0 | **唯一一张今天才建的表**（`030-data-opportunity.sql`） |

所以准确的说法是：

1. **这条生产链路是真跑过的**（15 个版本、26 个任务），不是空库。
2. **缺的只有「数据机会」这一环**：`030` 迁移此前从未执行，所以机会表不存在、一条机会都没落过库。
3. 这次真跑用的机会仍是按真实形态构造的（机会表当时为空），这一点不变。
4. 「有多少条机会的 z-score 建立在不足 30 点历史上」——表已建好但仍无数据，要先跑一次分析才能回答。

**结论不受影响**（"会主动多查、会拒绝口径不成立的选题"是 Agent 行为），但上一版关于「链路从未端到端跑过」的说法是错的，已删。

---

## 六、附带发现：框架往工作目录写了会话记录

跑完之后模块目录下多出了 `ruoyi-modules/ruoyi-heron/.agentscope/`：

```
workspace/agents/heron-research/sessions/default.jsonl      完整对话（含工具输出）
workspace/agents/heron-research/sessions/sessions.json
workspace/agents/heron-research/sessions/default.log.jsonl
workspace/default/heron-research/default/events/*.jsonl
```

三件事必须记下来：

1. **`disableSessionPersistence()` 挡不住它。** 我原先以为这个开关能避免留运行态，实测不成立——
   它没有落 SQLite（那是另一个机制），但会话 transcript 照写。
2. **路径跟工作目录走。** 默认解析到 `./.agentscope`，生产上就是应用的启动目录，位置不可控。
3. **transcript 里是完整对话与工具返回。** 一旦工具开始返回业务数据，这些文件就是一份旁路数据副本——
   plan.md §9.3 要求「注意敏感数据脱敏，不能无条件记录所有原始数据」，这条得在 Phase 2 之前处理。

已做的最小处置：删除产物 + `.gitignore` 忽略 `.agentscope/`。
**未做：** 把落盘路径钉到受控目录——这需要先确认框架是否支持设置 base path（`workspace(Path)` 可能相关，需实测），属 Phase 2。

---

## 七、未验证

| 项 | 说明 |
|---|---|
| 真实数据库 | `query_opportunity_detail` 用的是 mock 行；真库要 mysql 测试组，本地跑不了 |
| 真实抓数 | `query_trend_series` 的返回值是 stub，`DatasetFetchService.snapshot` 没真出网 |
| token 费用 | 本轮未记录 usage；预算闸是 Phase 2 |
| 上下文线程安全 | 测试里没有 Spring 容器，Sa-Token / 租户上下文未参与 |
| 多 Agent | 本次是单 Agent，没有子 Agent 委派 |

---

## 八、对下一步的影响

1. **单 Agent + 两个工具已经能做出「有依据的判断」**，不需要立刻上四个角色。
2. 真正的短板在**证据供给**：这次的 `OBSERVE` 是因为"基线拿不到"，而这很可能源自
   `DatasetSignal` 用 30 点算 z-score、而部分源历史不足的口径断层。**这属于数据线的问题，不是 Agent 的问题**——但当前库里没数据，无法证实。
3. 因此下一步不是继续加 Agent 功能，而是**先让这条链路在真库上跑出数据**：
   执行迁移 → 跑一次分析 → 看机会表。否则后面所有关于"值不值得做"的判断都没有事实基础。

---

## 九、怎么复跑

```bash
cd heron
export HERON_REAL_MODEL_KEY="$(grep '^HERON_REAL_MODEL_KEY=' .env | cut -d= -f2-)"
./mvnw.cmd -pl ruoyi-modules/ruoyi-heron -am test \
  -Dtest=AgentResearchRealRunTest -Dheron.test.excludedGroups=exclude,mysql
```

`HERON_REAL_MODEL_BASE` / `HERON_REAL_MODEL` 可覆盖端点与模型名，默认与本项目其它真链路测试一致。
