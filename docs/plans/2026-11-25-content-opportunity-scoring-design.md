# 内容机会评分与选题门禁（Opportunity Scoring）

**日期**：2026-11-25
**范围**：`heron` 后端 + `prompt/editorial/`
**前置**：`2026-11-13-data-opportunity-discovery-plan.md`（已落地：信号 → 机会表 → 定时分析）、
`2026-11-12-editorial-decision-chain-design.md`（已落地：建池 → 总编辑 → 线内编辑）

---

## 0. 一句话

**发现层已经建好了，缺的是「跨来源可比」和「选题前否决」。**

现在的顺序是对的——先建池、再由总编辑选线（`NewsAutoService.tick()` → `pools()` →
`editorial.decide()`），「今天不发」也是合法结果。缺的是总编辑拿到的候选没有统一刻度：
数据机会带 0–100 分，热榜/文章/知识/研究只有一串标题。**模型只能凭文字手感选线，
这就是「用印象代替证据」**。加上选题前没有硬门禁，不合规、重复、样本不足的题要等生产完
才被 `QualityReviewer` 拦——那时钱已经花了。

---

## 1. 现状取证（2026-11-25 在 `dev` 上核实）

| 提案要的能力 | 现状 | 位置 |
|---|---|---|
| 五种内容发现入口 | **已有五种** `hot` / `articles` / `knowledge` / `datasets` / `opportunities` | `EditorialCandidatePool.java:23-26` |
| 常青主题入口 | `KnowledgeCandidateCollector` + `sourceKind=knowledge` | `HeronNewsCandidate.java:19` |
| 深度研究入口 | 研究 → `ResearchPromotionServiceImpl.promote()` → `sourceKind=research` 候选 | `ResearchPromotionServiceImpl.java:54,143` |
| 数据机会引擎 | `DatasetSignal`（纯函数）→ `DataOpportunityService` 评分落库 → `DataOpportunityRunner` 每日 | `service/impl/` 三件 |
| 评分与去重 | 阈值过滤、`dedupKey`、`status` 四态、`used_version_id` 追溯、`Column` 栏目权重 | `DataOpportunityService.java:94-102,173` |
| 主编决策 | `PUBLISH/NO_PUBLISH` + `selectedLine` + `reason` + `editorialIntent` + `focusOpportunityId` | `EditorialDecisionService.java:95,129` |
| 「不发也合法」 | 写进提示词，且分因留痕 `no_fresh_candidate_and_no_opportunity` | `daily-editor.md:44-53`、`NewsAutoService.java:430-434` |
| 四条编辑线 | `DATA_BOARD` / `TREND_INSIGHT` / `NEWS_EXPLAINER` / `KNOWLEDGE_EXPLAINER` | `ProductionBizType.java` |
| 质量审查 | `QualityReviewer` / `QualityRepairPlanner` / `QualityReportContract` | `service/impl/` |
| 效果反馈 | `MetricInsightAnalyzer`（带 `strategyEligible`）、`TrendLearnService` | `service/impl/` |

**结论：架构不重排。** 提案的第 1–3 层已在跑，本次只补两个缺口。

### 真实缺口

| # | 缺口 | 证据 |
|---|---|---|
| 1 | **候选无统一刻度**。机会有 score，其余四种来源只有 label 文字，跨来源无法排序 | `NewsAutoService.pools():626-634` 各 `*Ref` 只装 label |
| 2 | **六维评估不存在**。受众价值/新颖/证据/张力/视觉/时效一个都没落成字段 | 全仓 `prompt/` 无相关维度（已 grep 核实） |
| 3 | **硬门禁在事后**。重复检测只有 `recentWorks` 文字提示；合规靠生产后 `QualityReviewer` | `daily-editor.md:49`、`QualityReviewer` |
| 4 | **统一对象不可能**，因为 `CandidateRef` / `OpportunityRef` 都用裸 `long id`，跨表主键会撞 | `EditorialDecisionService.java:43,49` |

缺口 4 是本次必须解决的前置，不是顺手改——不解决，统一排序会把 `focusCandidateId`
校验指到错对象上。

---

## 2. 设计

### 2.1 统一机会对象：派生视图，不建表

不新增 `heron_content_opportunity`。现有三张候选表（`heron_news_candidate` /
`heron_data_opportunity` / `heron_content_decision`）已在生产，建统一表要写三套投影、
定时同步、双写一致性和历史回填，收益与风险不成比例。

改为**建池时算一次派生评分**，随池子传给总编辑：

```
tick(slotTime)
 ├ 建池（不变）
 ├ OpportunityGate     ← 新：三条硬门禁，BLOCK 的候选不进池
 ├ OpportunityScorer   ← 新：六维评分（3 确定性 + 3 模型）
 ├ pools()             ← 改：带上分数与复合 key
 └ editorial.decide()  ← 提示词多一段「按总分排序」
```

```java
/** 统一机会：三种实体投影到同一个刻度。key 必须带类型前缀，裸 id 跨表会撞。 */
record OpportunityScore(
    String refKey,          // "candidate:12" / "opportunity:12"
    String sourceType,      // opportunity | hot | article | knowledge | research
    String label,           // 原样保留，总编辑已在读的文字
    int total,              // 0-100
    Dimensions dimensions,  // 六维各 0-100
    Gate gate,              // PASS | BLOCK
    String blockReason)     // 命中时必带具体输入
{ }
```

### 2.2 六维：三维确定性、三维模型

| 维度 | 算法 | 谁算 | 权重 |
|---|---|---|---|
| 证据可信度 | 机会：`coverage` × 有无 `data_sha256` × 源等级；文章：正文长度 + 证据条数；知识：摘要长度 + 源数；研究：复用 `ResearchGrade`；热榜：固定 0.3 | 确定性 | 20 |
| 新颖程度 | `1 − max(bigram Jaccard)`，对近 14 天已发布标题（`normalizedTitle` 已有） | 确定性 | 15 |
| 时效性 | `exp(−Δt/24h)`；常青来源（知识、非新闻节奏的机会）取 0.5 底 | 确定性 | 10 |
| 受众价值 | 模型：观众为什么需要了解 | 模型 | 25 |
| 故事张力 | 确定性底分（`min(\|z\|,6)/6`、`new_extreme`、`\|rank_delta\|`）→ 模型可上浮 | 模型 | 20 |
| 视觉表现力 | 确定性底分（该线的模板形态是否含此 shape）→ 模型可上浮 | 模型 | 10 |

权重是常量起步，`HeronNewsProperties` 可覆盖。不做配置中心。

**没有向量能力**（已 grep：全仓无 embedding / cosine），所以新颖度只能用 bigram。
这是诚实的选择，不是妥协——向量版单独立项。

### 2.3 三条硬门禁（先于评分）

| 门禁 | 判据 | blockReason 必须带 |
|---|---|---|
| `duplicate_recent` | `dedupKey` 撞上近 14 天已发布作品，或标题 bigram Jaccard ≥ 0.8 | 撞了哪条已发布（版本 id + 标题） |
| `sample_insufficient` | 仅机会：`coverage < 0.6`，或窗口点数 < 8 | 实际点数 / 请求点数 |
| `compliance_risk` | 关键词黑名单命中 | 命中的词 |

**证据不足不做硬门禁**，只降分。原因：`hot` 候选只有标题，若按证据设闸会整类打光；
而「文章必须有正文」在建池时已由 `hasBody` 保证（`EditorialCandidatePool.of()`），
重复设闸没有增量。**需求里「事实无法验证不能作为确定事实发布」由审稿层兜（已有），
不在选题层假装能判。**

门禁不通过 ≠ 整槽不发：剩下的候选照常进池，只是 BLOCK 的那些不给模型看。

### 2.4 模型评分：先筛后批量打分

```
确定性评分 → 取 top-N（默认 12） → 1 次批量调用
  输入：[{refKey, sourceType, label, 确定性分, 证据摘要}]
  输出：[{refKey, audience, novelty, tension, visual, why}]
```

- 每次建池 **1 次额外模型调用**，与现有总编辑那次同量级。
- 复用 `OpenAiModelClient.chatWithoutUsage`，不新建客户端。
- **失败降级不阻断**：模型调用挂了就用确定性分（缺的三维按中性 50），
  留痕 `model_scored=false`。理由：选题评分是增强，不是闸；
  但它必须在留痕里可见，不能静默降级。
  **例外**：`compliance_risk` / `duplicate_recent` 命中时即便模型挂了也照常 BLOCK。

### 2.5 留痕：零迁移

评分写进 `heron_auto_slot.pool_json` 的 `decisionTrail`（列已存在，
`NewsAutoService.decisionTrail()` 已在写）。**不加列、不加表、不改 Flyway。**

`PoolDigest` **不动**。它的职责是证明「总编辑看到的 == 线内编辑挑的」，
模型分是每次重算不确定的，进 hash 会让校验变成噪音。已在 `PoolDigest` 注释里
写明这条原则（`PoolDigest.java:11-14`），照它执行。

### 2.6 不碰的面

- `NewsProductionPipeline`（2845 行）、`data/templates/**`、`data/tools/**` — 与选题无关。
- 三条现有候选表结构、`focusCandidateId` / `focusOpportunityId` 校验逻辑。
- 四条编辑线的 `kind` 映射与模板 `supportedLines` 校验。

---

## 3. 分三阶段，每阶段独立可验收

### 阶段 1 · 确定性评分 + 硬门禁（不调模型、不改提示词）

| 交付 | 说明 |
|---|---|
| `service/impl/OpportunityScore.java` | 值对象 + `Dimensions` + `Gate` |
| `service/impl/OpportunityFacts.java` | 三种实体 → 确定性事实的投影（纯函数） |
| `service/impl/OpportunityGate.java` | 三条门禁，纯函数 |
| `service/impl/OpportunityScorer.java` | 确定性三维 + 张力/视觉底分；`rateByModel` 留空 |
| `NewsAutoService` | `tick()` 建池前跑 gate + scorer；`decisionTrail` 加 `scores` 数组 |
| `HeronNewsProperties` | `opportunityScoring` 子对象：开关（**默认关**）、`topN`、六个权重、`blocklist` |

**验收**：开关关 → 行为与现在逐字节相同（回归靠这条）。开关开但不调模型 →
跑一周真实槽位，人工看 `scores` 数组，回答两件事：分数分布是否有区分度、
`duplicate_recent` 是否误杀。**这一阶段不改变选线结果**，只观察。

### 阶段 2 · 模型三维 + 提示词接入

| 交付 | 说明 |
|---|---|
| `prompt/editorial/opportunity-score.md` | 批量打分契约：只评三维 + 一句 why，不得改确定性分 |
| `OpportunityScorer.rateByModel()` | top-N 批量调用；输出按 `refKey` 校验，编 key 直接丢弃该条 |
| `EditorialDecisionService.Pools` | 新增 `List<OpportunityScore> scored`，**新构造重载**（已有 4 个，加第 5 个并保留旧的） |
| `daily-editor.md` | 新增「按总分排序」一节；`focusCandidateId` 改为可回 `refKey` |
| `NewsAutoService.pools()` | 保持纯投影：分数已在建池阶段算好传进来 |

**验收**：同一批候选，开关开 → 总编辑的 `reason` 里出现具体分数依据；
跑 20 次固定种子，选线结果与不带评分时不同且**有记录可解释**每处差异。

### 阶段 3 · 反馈回流（不重复立项）

已有三任务单 C2（`docs/plans/2026-11-25-video-line-three-tasks.md`）在做「表现回流」。
本阶段**只做一件事**：把 C2 产出的偏好接进 `OpportunityScorer` 的**一维权重**，
不做第二套推荐。硬约束沿用 C2 原文：不到 `L4` 或样本不稳时**不许影响分数**，
只加权不拉黑。

---

## 4. 文件清单

### 新增

| 文件 | 用途 |
|---|---|
| `service/impl/OpportunityScore.java` | 统一对象（含 `Dimensions`、`Gate`） |
| `service/impl/OpportunityFacts.java` | 实体 → 事实投影，纯函数 |
| `service/impl/OpportunityGate.java` | 三条硬门禁 |
| `service/impl/OpportunityScorer.java` | 确定性评分 + 批量模型评分 |
| `prompt/editorial/opportunity-score.md` | 批量打分契约 |
| 测试 | `OpportunityGateTest`、`OpportunityScorerTest`、`OpportunityScorePromptTest` |

### 修改

| 文件 | 改动 |
|---|---|
| `NewsAutoService` | `tick()` 建池前接 gate/scorer；`decisionTrail()` 加 `scores` |
| `EditorialDecisionService` | `Pools` 加 `scored`（新重载）；`prompt()` 增一节 |
| `prompt/editorial/daily-editor.md` | 「按总分排序」+ `refKey` |
| `HeronNewsProperties` | `opportunityScoring` 子对象，默认关 |

### 不动

`NewsProductionPipeline`、`data/templates/**`、`data/tools/**`、`PoolDigest`、
三张候选表、`script/sql/**` / Flyway。

---

## 5. 测试

| # | 断言 |
|---|---|
| 1 | `refKey` 对齐：候选 12 与机会 12 是两条不同对象，不互相命中 |
| 2 | 重复检测：近 14 天已发布标题 → `duplicate_recent`，reason 含版本 id |
| 3 | 机会 `coverage=0.4` → `sample_insufficient`，reason 含 实际/请求点数 |
| 4 | 关键词命中 → `compliance_risk` |
| 5 | BLOCK 的候选**不进** `Pools`，不给模型看 |
| 6 | `hot` 候选（只有标题）不被证据类规则打死 |
| 7 | 模型返回编造的 `refKey` → 该条丢弃，其余保留 |
| 8 | 模型调用抛异常 → 用确定性分继续，`model_scored=false` 留痕 |
| 9 | 模型异常 + 命中合规 → 仍然 BLOCK（降级不能绕过闸） |
| 10 | 开关关 → `scores` 为空，选线结果与改动前一致 |
| 11 | 总分排序稳定：同一输入两次打分，确定性三维完全一致 |
| 12 | `PoolDigest` 不含分数：同一批候选，有无评分算出同一 `poolHash` |

命令：

```
mvn -pl ruoyi-modules/ruoyi-heron -am test
mvn -pl ruoyi-modules/ruoyi-heron -am test -Dheron.test.excludedGroups=exclude,mysql
```

---

## 6. 明确不做

| 不做 | 理由 |
|---|---|
| 建 `heron_content_opportunity` 统一表 | 三套投影 + 同步 + 回填的代价远大于派生视图 |
| 向量/embedding 去重 | 全仓无向量能力，为新颖度引入一个新依赖不划算，且 bigram 够用 |
| 路线 C（Hybrid）/ D（Reference Inspired） | 现有四条线已覆盖「数据讲 / 主题讲 / 趋势讲 / 常青讲」；Reference 形态需要先有爆款结构库，`TrendLearnService` 产出的是技法卡不进选题池，是独立议题 |
| 把审稿从 L3 改成选题层闸 | 审稿要读全文，选题层拿不到；「事实不可验证」在选题层判不了，硬判就是假精度 |
| 新增第二个文件生产/渲染路径 | 与本次无关 |
| 给机会表加评分列 | 分数是派生值，落库就会与实体漂移 |

---

## 7. 风险

| 风险 | 应对 |
|---|---|
| 权重拍脑袋 | 阶段 1 先只观察不生效，拿真实槽位分布再定；权重进 properties 可覆写 |
| bigram 去重漏判同义改写 | 已知上限，`duplicate_recent` 只拦近似重复；写进代码注释标 `shortcut:` |
| 模型三维不稳定 | 温度/模型变更会让分数漂；`model_scored` 与 `promptVersion` 一并留痕，可回溯 |
| 额外 1 次模型调用 | 与总编辑同量级；开关可关；top-N 可调 |
| 合规词表覆盖不足 | 首版只做黑名单，明确标注这是弱保证，不替代平台规则与审稿 |

---

## 8. 建议的下一步

阶段 1 单独开一个分支做完即止，**不接阶段 2**。等一周真实槽位的分数分布出来，
拿它回答「评分到底有没有区分度」——如果分布全挤在中段，说明权重设计错了，
接进提示词只会给模型一个假依据。**先证明评分有用，再让它影响决策。**
