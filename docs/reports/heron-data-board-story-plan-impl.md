# 数据看板线 story/1 内容策划契约 · 实施报告

> 设计稿：[docs/plans/2026-11-19-data-board-story-plan.md](../plans/2026-11-19-data-board-story-plan.md)
> 落地范围：`ruoyi-modules/ruoyi-heron`
> 状态：主代码与提示词已接线，39 条新增单测全绿，全量 847 个测试与基线对比无回归

---

## 1. 为什么做这一步

参考清单里定的实施顺序是 **P0 数据看板线 → P1 趋势洞察线（已完成）→ P2 知识解释线（代码齐、运营未开）→ P3 案例拆解栏目（未做）**。
P0 里没做完的那块，是「取数 → 想标题 → 写旁白」中间缺一个**说清楚这条片子讲的是什么事**的环节：脚本作者只拿到快照和标题候选，自己临场决定讲什么，于是
「标题和正文各讲各的」「结论没有出处」「结尾是空关注引导」这类问题只能靠审稿模型事后拦，返工成本高。

story/1 就是把这个环节补成一个**结构化、可校验、随版本冻结**的产物 story。

三条铁律（设计稿 §0）落地情况：

| 铁律 | 落地方式 |
|---|---|
| ① 没故事就不做 | `EditorialLineEditor` 的看板线路径（机会路径与选源器兜底路径）都必须先拿到策划结论才返回选材；全拒 → 该线为空 → 回退解说线 |
| ② 模型不写数字 | story 里所有数值一律 `{{facts.*}}` 占位符，数值来源只有快照；`DatasetStoryContract` 复用 `DatasetContract.narrationViolations` 判「裸数字」与「占位符不可解析」 |
| ③ 脚本 JSON 一个字段都不加 | `DatasetScriptContract.FIELDS` 仍是 7 个（`dataset_sha256/source_key/title/scenes/closing/source_name/chart`）；策划结论只走「提示词 + Java 复核」两条通道 |

---

## 2. 改动清单

### 2.1 新增

| 文件 | 作用 |
|---|---|
| `domain/EditorialStory.java` | story/1 值对象（record）。`SCHEMA_VERSION = "story/1"`；嵌套 `Protagonist/Tension/Claim/Payoff/Stakes/Basis`；`fromJson(JsonNode)` / `toMap()` / `toJson()` / `allEvidenceRefs()`（信封）/ `factClaims()` / `withBasis(Basis)`（用机器依据替换模型自述依据） |
| `service/impl/DatasetStoryContract.java` | 硬校验 + 生产期复核 + 叙事映射表。`violations(story, snapshot)`、`evidenceViolations(story, production)`、`availableNarratives(snapshot, signal)`、`titleKinds(narrative)` |
| `service/impl/DatasetStoryPlanner.java` | 策划调用（stage `dataset-story`）。数据层闸 → 调模型 → 机器填依据 → 故事层闸 |
| `service/impl/StoryScriptAlignment.java` | 脚本与故事的位置对位校验（四条） |
| `resources/prompt/data-board/story-contract.md` | story 提示词契约：字段规则表、六类叙事与张力相容表、证据信封、禁止项 |
| `src/test/.../DatasetStoryContractTest.java`（16 条） | 硬校验与生产期复核 |
| `src/test/.../StoryScriptAlignmentTest.java`（7 条） | 脚本对位 |
| `src/test/.../DatasetStoryPlannerTest.java`（12 条） | 11 条拒绝码路径 + 机器填依据 |
| `src/test/.../StoryDecisionTest.java`（5 条） | 线内编辑的候选级拒绝与槽位记录 |

### 2.2 修改

| 文件 | 改动 |
|---|---|
| `domain/EditorialSelection.java` | record 增第 10 个组件 `EditorialStory story`；7/8/9 参旧构造器全部保留（委托 `story = null`），老调用点零改动；紧凑构造器只校验「有 story 时 schemaVersion 必须是 story/1」 |
| `domain/EditorialChoice.java` | record 增第 5 个组件 `List<String> storyRejects`（形如 `源键:story_duplicate`）；4 参旧构造器保留 |
| `service/impl/DatasetSignal.java` | 拆出包私有 `raw(snapshot, windowPoints, policy)`，把「过期」与「幅度不可信」两道过滤从 `of(...)` 里剥出来——策划要用它们给出**具体拒绝码**，而不是笼统地说信号弱 |
| `service/impl/EditorialLineEditor.java` | 看板线新增策划步骤：未锁定时按分数取前 3 条机会依次策划（`MAX_STORY_CANDIDATES = 3` 硬封顶），锁定时只试锁定那一条；每条拒了记 `源键:拒绝码`；全拒 → 兜底速览也要故事 → 再拒 → 该线不占槽位 |
| `service/impl/NewsProductionDomain.java` | 冻结快照 `selection` 节点在有 story 时多写一个 `story` 子节点（additive，`FrozenContext.SCHEMA` 仍是 `1.0`）；`requiredGates` 按快照里有没有 story 决定要不要 `story` 闸（历史快照重跑不因少闸失败） |
| `service/impl/NewsProductionPipeline.java` | 取数之后、写稿之前加 `storyGate`（按生产快照复核）；写稿与审稿都把 story 传下去 |
| `service/impl/DatasetTitleAuthor.java` | `BANNED` 放开为包可见；新增带 story 的重载：钩子按 narrative 收窄（`DatasetStoryContract.titleKinds`）、标题里的 `{{facts.*}}` 必须落在故事信封内、提示词多一段「已定故事」 |
| `service/impl/DatasetScriptAuthor.java` | 提示词多一段「已定故事」（段落顺序：张力 → 一条主张一段 → 落点）；复核时叠 `StoryScriptAlignment` 的对位条目；修复走同一份提示词 |
| `service/impl/EditorialReviewer.java` | 审稿依据多 story，并按「scene1=张力，sceneN=c(N-1)，closing=落点」给段落对应；多三个必答问题（开场讲没讲张力 / 结尾落到落点没有 / 假设被写成断言没有） |
| `service/impl/NewsAutoService.java` | 槽位 `decisionReason` 追加「｜策划拒绝：…」（裁到 500 字）；`decisionTrail` 根层平级追加 `storyRejects` 数组（前端只认四个原键，平级追加不会白屏） |
| `resources/prompt/data-board/title-contract.md` | 增「钩子与叙事的相容表」 |
| `resources/prompt/data-board/script-contract.md` | 增「故事对位规则」 |

---

## 3. 拒绝码与闸

### 3.1 11 条拒绝码（候选级，不是槽位级）

按设计稿 §7.1 的顺序命中即停：

| 顺序 | 码 | 判据 |
|---|---|---|
| 1 | `story_data_stale` | `DatasetSignal.stale(periodEnd, cadence, today)` |
| 2 | `story_data_thin` | 点数 < `MIN_BASELINE_POINTS`(5) 或覆盖 < 0.5 |
| 3 | `story_implausible_change` | `|change_pct| > MAX_PLAUSIBLE_CHANGE`(2.0) |
| 4 | `story_license_blocked` | `license.commercial_ok == "false"` |
| 5 | `story_signal_weak` | 无 z-score、无新极值、变化 < 该类别大变化基准（三者同时成立） |
| 6 | `story_duplicate` | 7 天内同源出过片（沿用 `heron_dataset_usage` 的去重口径） |
| 7 | `story_all_assumption` | 事实主张 < 2 条 |
| 8 | `story_needs_multi_source` | 故事文本里出现别的 `source_key` |
| 9 | `story_category_ineligible` | `CategoryPolicy.eligible == false`（如天气） |
| 10 | `story_no_stakes` | `stakes.audience` 为空 |
| 11 | `story_contract` | §5.1 硬校验任一条不过（含叙事类型没有机器前提、裸数字、引用越界、因果断言、绝对词无覆盖支撑、假设与 assumptions 不一一对应…） |

取数失败单独记 `story_fetch_failed`；找不到数据源记 `story_source_missing`；模板缺失记 `story_template_missing`。

### 3.2 生产期闸

`story` 闸在**真实生产快照**上复核（设计稿 §5.2），失败抛 `news_story_evidence_mismatch`：

- 引用仍可解析（消息里给出具体路径与第几条）
- 方向未翻转（策划 `basis.changePct` 与生产 `facts.change_pct` 同号）
- 覆盖未塌陷（生产行数 ≥ 策划点数 50%）
- 极值未失效（策划说极端，生产得仍是极端）

策划哈希与生产哈希都写进闸的 `reason` 留痕；`measured` 只放 number/boolean（`refsResolved` / `refsTotal` / `directionStable` / `coverageStable`）。

### 3.3 兼容

- 冻结快照 `schemaVersion` 仍是 `1.0`（升版本等于让全部历史任务无法重跑）。
- `story.schemaVersion = "story/1"` 独立演进；读方不认识就当没有故事。
- 无 story 的历史快照重跑：不跑 story 闸、不要求 `story` 闸、提示词不加那节——照常通过。

---

## 4. 验证

### 4.1 编译与测试怎么跑

`mvn` 全量太慢，用现成 classpath 直接 `javac` + JUnit Platform Launcher：

```bash
# 主源码（295 个文件）
mkdir -p .tmpbuild/classes
find ruoyi-modules/ruoyi-heron/src/main/java -name "*.java" > .tmpbuild/srcs.txt
javac -nowarn -proc:full -encoding UTF-8 -d .tmpbuild/classes \
  -cp "$(cat ruoyi-modules/ruoyi-heron/target/cp.txt)" @.tmpbuild/srcs.txt

# 测试 classpath
mvn -o -q -pl ruoyi-modules/ruoyi-heron dependency:build-classpath \
  -Dmdep.outputFile=D:/zhoupei/heron/.tmpbuild/testcp.txt -DincludeScope=test
```

三个坑（都踩过）：

1. **javac 的 argfile 里反斜杠是转义符**——classpath 必须全写成正斜杠，否则整条路径被吃掉，报「程序包不存在」。
2. **javac 不展开 `dir/*` 通配**（JVM 展开）。给 javac 用展开后的长 classpath，给 `java` 用 `.tmpbuild/lib/*` 通配，短且稳。
3. **JDK 是 BellSoft Liberica 21，Mockito 自附加 agent 失败**——报「Could not initialize inline Byte Buddy mock maker」。必须显式带 `-javaagent:byte-buddy-agent-1.18.15.jar`。

另外：测试运行时必须把 `ruoyi-modules/ruoyi-heron/src/main/resources` 放进 classpath，否则 `DatasetTitleAuthor.<clinit>` 抛「缺少提示词资源」。

### 4.2 结果

| 范围 | 测试数 | 通过 | 失败 |
|---|---|---|---|
| 本次涉及的 9 个测试类 | 63 | 63 | 0 |
| heron 模块全量（改动后） | 847 | 652 | 184 |
| heron 模块全量（HEAD 基线） | 808 | 613 | 184 |

基线用 `git archive HEAD` 导出到 `.tmpbuild/base` 单独编译跑的。**两次的失败类分布逐条一致**（`diff` 无差异），新增的 39 个测试全部通过 —— **没有回归**。

那 184 个失败是环境性的，与本次改动无关：绝大多数是 `@SpringBootTest` + MySQL 的用例（本机没有 `heron` 库和 Redis），另有 `AgentResearchRealRunTest` 需要 `HERON_REAL_MODEL_KEY`。

---

## 5. 遗留与下一步

- **生产链路没真跑过**：策划这一步要真模型（`heron.model.*`）与真实数据源，本机两者都没有，只跑通了单测与编译。真跑需要一份带模型密钥的环境。
- **风格映射表已另案落地**：设计稿里「叙事类型 → 视觉风格」的映射表已随模板 `style.narratives`、`StoryStyleMap` 与 `NewsTemplatePicker` 的叙事收窄实现；本报告出具时尚未开始。
- **故事的历史表现回填**没做（需播放数据回流，另立项）。
- **人工编辑故事**没做（先只做「自动生成 + 自动拒绝」）。
- 端到端验收还要等「真片是带策划结论跑出来的」——现在一条都还没有（见 `docs/2026-11-20-storyboard-and-visual-signal-report.md`）。
