# 内容质量审稿标准（P2）与表现数据反馈闭环（P3）设计

**日期**：2026-11-19
**范围**：`heron` 后端 `ruoyi-modules/ruoyi-heron`（`org.dromara.heron`），数据看板线为主（`kind=data_board`），解说线（`kind=article`）只在共用层提及。本文只做设计，不改代码、不建表、不跑迁移、不 commit、不 push。
**前置**：`docs/plans/2026-11-19-data-board-story-plan.md`（story/1 策划契约，**本文不重写它**）、`docs/plans/2026-11-12-editorial-decision-chain-design.md`（决策链）、`docs/2026-11-19-p0-fact-audio-audit.md`（测试工程师取证）

---

## 0. 一句话

上一轮把「讲什么故事」定成了可校验的 `story/1`。但**片子做出来好不好看，现在没有任何一层在说**。

本文补两层：

- **P2（成片级审稿）**：一套可打分的判据，把「这条片子值不值得发」拆成**机器能判的客观事实**、**模型的主观判断**、**模型的推测**三层，各自带依据、置信度、能否机器复核。判断落在证据上，不落在印象上。
- **P3（表现数据反馈）**：把「观众看完之后的反应」和「这条片子当时是怎么做出来的」关联起来，带样本量门槛、置信度分级和人工覆盖记录，让表现数据**回流到编辑决策**而不是停在分析页。

两条边界贯穿全文：

1. **主观不得越权**：P2 的主观结论永远不能改写任何一道客观闸，不能让发布门禁放宽（自动发布仍只认落盘的 `editorialReview` PASS）。
2. **数据不得造**：P3 的每个属性都从现有冻结快照 / 现有表 / 生产期实测取，取不到就写「未记录」，缺失字段允许为空，禁止编造。

---

## 1. 现状取证（2026-11-19 的 `dev`）

| 断言 | 位置 | 事实 |
| --- | --- | --- |
| 成片已经过了 11 道闸 | `NewsProductionDomain.java:36-38` | `DATA_GATES` = schema / evidence / data / audio / subtitles / render / decode / visual / aigc_metadata / aigc_visible / assets（`DATA_KINDS` 7 类产物） |
| 闸门报告只许数值与布尔 | `NewsProductionDomain.java:314-338` | `validateChecks` 要求每条 check 恰好是 `{gate, inputLocator, passed, reason, measured}` 五个字段，`measured` 的值**只能是数值或布尔**，key 必须匹配 `[a-zA-Z][a-zA-Z0-9_]{0,63}` |
| 所以主观结论进不了 checks | 同上 | `reason` 是字符串但只描述「闸为什么过」，闸报告里放不进一段「我觉得这条挺闷」 |
| 已有一次文本级审稿 | `EditorialReviewer.java:38-82` | `Issue(severity BLOCKER/MAJOR/MINOR, segmentId, description, suggestion)`；`Verdict(result, coreFacts, issues, minorSuggestions)`；`toRecord()` 落 `{schemaVersion:"1.0", result, coreFacts?, issues[], minorSuggestions[], repairAttempts, promptVersion, reviewedAt}` |
| 审稿记录落在一列上 | `script/sql/heron/027-editorial-review.sql` | `ALTER TABLE heron_video_version ADD COLUMN editorial_review TEXT NULL` —— 就地扩列，不新建表 |
| 自动发布只认落盘的 PASS | `NewsWorkServiceImpl.java:516-540` | `requireEditorialPass` / `checkEditorialPass` 读 `editorial_review`，**缺记录即 fail closed**（`review_required`） |
| 审稿层已有一次定向修复 | `NewsProductionPipeline.java:309-330` | `reviewScript`：review → blocked 且是证据问题就抛 → 否则修一轮 → 再审 → 仍 blocked 抛 |
| 写稿层已有 2 次修复预算 | `DatasetScriptAuthor.java:30` | `MAX_REPAIRS = 2` |
| 文本模型客户端**没有视觉通道** | `OpenAiModelClient.java:136-142` | `payload()` 只发 `messages:[{role:system,content:String},{role:user,content:String}]`，**没有 image part**；仓库里也没有第二个模型客户端 |
| 关键帧其实已经有了 | `NewsProductionPipeline.java:750-759` | 成片 `contact_sheet` = `ffmpeg -vf "fps=1/step,scale=360:640,tile=3x2"`，**已经是产物清单里的一项**（`DATA_KINDS` 含 `contact_sheet`） |
| 场景时间轴是实测的 | `NewsProductionPipeline.java:449-476` | `subtitles` 闸 `measured` 里有 `scene1StartMs/scene1EndMs/.../closingStartMs/closingEndMs`、`audioMs`、`cues`、`alignedScenes`、`interpolatedBoundaries` |
| 字幕是 cue 级不是词级 | `NewsNarrationTiming.java` | `Span(id, startMs, endMs, cueInterpolated)`，插值上限 1000ms |
| 视觉焦点是机器算的 | `DatasetVisualFocus.forScene(scene, snapshot)` | 返回 `Focus(mode, refs, labels)`，mode ∈ change/comparison/range/latest/leader/item/overview；取不到就抛 `dataset_focus_invalid`，**没有静默降级** |
| 归因已有四个维度 | `HeronNewsMetricMapper.xml` `performanceBreakdown` | 用 `json_extract(v.input_snapshot,'$.selection.templateId')`、`'$.editorial.line'`、`'$.editorial.storyAngle'`、`c.source_kind` 分桶；无快照归「未记录」；每个版本只取 `fetched_at` 最新一条采样 |
| **`source` 维度对数据看板线归因错误** | `HeronNewsMetricMapper.xml` 的 `case when c.source_kind = 'research' then 'research' when c.candidate_id is null then '未记录' else 'hotlist' end` + `NewsWorkServiceImpl.java:214-237` `placeholderCandidate` | `source_kind` 全仓只有 `ResearchPromotionServiceImpl.java:143` 写过（值 `research`）；数据看板线认领时建的是**占位候选**，`source_kind` 为 NULL → 落进 `else 'hotlist'`。**当前形态的数据看板片在归因里被算成「热榜」**。P3 引用这个维度前必须先修（§11.6）。**已修**：读侧 Java 分桶 + 写侧占位写 `data_board`，存量回填 V53 待执行 |
| 维度是白名单 | `NewsMetricServiceImpl.java:26-27` | `GROUP_BY_KEYS = {template, line, angle, source}`，未知维度直接 400 `metric_dimension_invalid` |
| 表现数据是人工录入的 | `NewsMetricController.java`、`007-news-metric.sql` | `heron_news_metric` 一行一次采样；无采集器，样本天然稀少 |
| 样本不足的提示已经存在 | `heron-ui/src/views/heron/analytics/index.vue:63` | `samples >= 3 ? '可参考' : '样本不足'`；页面明确写着「还不能做的：让编辑策略自动参考历史表现」 |
| 人工覆盖有成熟先例 | `038-content-decision.sql`、`041`、`045` | `heron_content_decision` 有 `status/decided_by/decided_at/decision_note/verification_override_reason/evidence_override_reason`；`heron_auto_slot` 也在 026 里就地扩了 5 列 |

**第 3 行和第 11 行是本设计的两个直接动因**：现有闸门体系在结构上**装不下主观结论**（不是没做，是设计上就不该装进去），而现有模型通道**看不见画面**（这决定了 P2 的证据分层必须诚实）。

---

## 2. 三层分工：谁在什么位置说哪句话

现在已有的三层，加上本文新增的一层：

| 层 | 名字 | 谁做 | 判什么 | 结论能否拦住发布 |
| --- | --- | --- | --- | --- |
| **L0** | 客观硬闸 | Java + 外部工具（ffprobe / ffmpeg / hyperframes check / edge-tts） | 事实、渲染、音画对齐、资产哈希、AIGC 合规 | **能**，且是唯一能拦的 |
| **L1** | 文本审稿 | `EditorialReviewer`（已有） | 文案有没有把已验证的事实讲对（`coreFacts`） | **能**（`editorial_review` PASS 是自动发布的前置） |
| **L2** | 策划契约 | `DatasetStoryContract` + `story/1`（上一轮） | 有没有故事、故事是否被兑现、事实与假设是否分栏 | **能**（story 闸） |
| **L3（本轮新增）** | **成片审稿** | `QualityReviewer`（新增） | 这条片子好不好看：开场、推进、高潮、画面配合、节奏、结尾 | **不能**。只产出报告与建议 |

**L3 为什么不能拦**：一旦主观结论能拦住发布，就会出现「模型说好看就放行」，事实与数据闸就形同虚设。L3 的价值是**给人和编辑提供一份有依据的判断**，以及**为定向修复指出该改哪一段**。发布与否仍然是人或既有 L1/L2 的事。

L3 的落点也不能是 `checks`（`validateChecks` 白名单挡着，且 `measured` 只许数值），所以走 `heron_video_version` 上的一列新字段，与 `editorial_review` 完全同构。

---

## 3. P2-A：判据清单（九条）

每条给出：**怎么判断** / **证据来自哪里** / **可机器复核的部分**。判据不是给分，是给「判断 + 依据 + 置信度」。

标注约定：

- `[机]` 该条的主要判据可以由机器复核
- `[混]` 该条一部分机器可判，一部分依赖模型读文本
- `[人]` 该条只能由人看画面判断（模型当前无视觉通道）

---

### 3.1 `hook_3s` —— 开场前三秒有没有把事情说清楚

| 项 | 内容 |
| --- | --- |
| **怎么判断** | 前三秒对应的第一屏（`scene1StartMs` → `min(scene1EndMs, scene1StartMs+3000)`）内：① 观众是否拿到了可验证的具体信息（是否引用 `facts`）；② 是否把准备过程当内容（是否命中 `META_OPENING` 词表）；③ 首句是否与标题逐字重复 |
| **证据来自哪里** | `script.scenes[0].lead/body`；`dataset.facts`；`subtitles` 闸 `measured.scene1StartMs/scene1EndMs`；`DatasetScriptContract.META_OPENING`（`DatasetScriptContract.java:35-37`） |
| **机器可判** | ①`[机]` 有无 `{{facts.*}}` 占位符（`PLACEHOLDER` 正则）；②`[机]` 是否命中 `META_OPENING`；③`[机]` 首屏 lead 是否包含标题原文 |
| **模型判** | `[混]` 「反差/悬念是否真的立得住」——模型读标题与首屏原文判断，不能读画面 |
| **注意** | ①③ 已被 `DatasetScriptContract.narrative()` 覆盖（前两屏 facts、标题复读），L3 **不重复实现**，只引用其结论并把它标为已客观确认 |

### 3.2 `early_payoff` —— 前段是否快速兑现价值

| 项 | 内容 |
| --- | --- |
| **怎么判断** | 前两屏（前两段的实测区间）是否各带一个可验证信息点；观众是否在 15 秒内拿到了「这条为什么值得看」的答案 |
| **证据来自哪里** | `script.scenes[0..1]`；`subtitles` 闸 `scene1StartMs/scene1EndMs/scene2StartMs/scene2EndMs`；`DatasetScriptContract.narrative()` 的 `earlyFact` 规则（`DatasetScriptContract.java:123-132`） |
| **机器可判** | `[机]` 前两屏合计是否含 facts 占位符（已有规则，直接引用） |
| **模型判** | `[混]` 「兑现的是不是观众要的那个价值」——模型读首屏原文与标题判断 |
| **注意** | 「价值兑现得够不够快」的时间维度只能粗判到「前两屏」这个粒度；**做不到词级**（`NewsNarrationTiming` 是 cue 级），不要假装能算到第几秒说了哪个词 |

### 3.3 `mid_progression` —— 中段是否在推进，而不是平铺

| 项 | 内容 |
| --- | --- |
| **怎么判断** | 相邻两段是否在说同一件事；每段是否带来新信息（新的 facts 路径 / 新的对比维度）；有没有一段是纯过渡 |
| **证据来自哪里** | `script.scenes[*].lead/body` 与其中的 `{{facts.*}}` 路径集合；`dataset.facts` 的可用路径 |
| **机器可判** | `[机]` 相邻两段的 facts 路径集合是否完全相同（= 平铺）；`[机]` 有 facts 引用的段数（已有 `MIN_FACT_SCENES = 2` 规则） |
| **模型判** | `[混]` 「有没有信息增量」——模型读相邻两段文本判断 |
| **注意** | 「纯过渡段」是语言学判断，本层不给它独立的客观结论；它只出现在模型判断里，并被标为 `subjective` |

### 3.4 `peak_focus` —— 有没有清晰的高潮与重点

| 项 | 内容 |
| --- | --- |
| **怎么判断** | 全文是否有一个「最该被记住的点」；这个点是不是被画面重点呈现了（focus mode 是否落在那一段）；重点是否被稀释（多个段都在抢同一个重点） |
| **证据来自哪里** | `DatasetVisualFocus.forScene(scene, snapshot)` 每段的 `Focus(mode, refs, labels)`；`script.scenes[*]` 的 facts 路径集合；`subtitles` 闸各段区间 |
| **机器可判** | `[机]` 每段的 focus `mode` 与 `refs`（refs 必须落在该段引用的 facts 路径上，否则是焦点错配）；`[机]` 各段 facts 路径的分散度（有没有一段承载了唯一的最强信号，如 `extreme`/`leader`/`min`/`max`） |
| **模型判** | `[混]` 「重点是不是被讲清楚了」——模型读原文判断 |
| **注意** | 「最强信号」由 `DatasetSignal` 的既有计算（`zscore`/`extreme`）给出，是客观量；**「这条数据值不值得当高潮」是选题层判断，不在 L3** |

### 3.5 `visual_sync` —— 画面与旁白是否在讲同一件事

| 项 | 内容 |
| --- | --- |
| **怎么判断** | 每段画面聚焦的数据点，是否就是该段旁白在讲的数据点；有没有画面在讲 A、口播在讲 B |
| **证据来自哪里** | `DatasetVisualFocus.Focus.refs` × `script.scenes[i]` 的 facts 路径集合；模板侧 `chart` 与 `DatasetScriptContract.chartFor(shape)` |
| **机器可判** | `[机]` 焦点 refs 与该段旁白引用路径是否一致（不一致即错配）；`[机]` `chart` 是否是快照形态的可渲染类型 |
| **模型判** | 无（这一条几乎全是客观的） |
| **注意** | 「画面好不好看」不在这条里，这只判「配不配得上」 |

### 3.6 `style_consistency` —— 视觉风格是否统一

| 项 | 内容 |
| --- | --- |
| **怎么判断** | 同一片子内是否只有一套视觉语言（模板、字体、配色、版式）；版式检查是否全部无 error |
| **证据来自哪里** | `hyperframes check --json` 的 `craft` / `layout` / `contrast` 段（`errorCount`/`warningCount`/`findings`，见 `NewsProductionPipeline.java:544-561`）；`visual` 闸 `measured.blankFrames`；模板 `template.json` 的 `style.key`；冻结快照 `assets.templateKey/fontSha256` |
| **机器可判** | `[机]` `check` 段计数（已有 `visual` 闸）；`[机]` 联络表逐格非空白（已有 `blankCells`）；`[机]` 资产哈希与 `LOCK.json` 一致（已有 `assets` 闸） |
| **模型判** | 无（这一条也几乎全是客观的） |
| **注意** | 「风格是否适合这个题材」是**人工选题判断**，不进 L3；L3 只判「这一条片子内部是否统一」 |

### 3.7 `pacing_variation` —— 节奏有没有变化

| 项 | 内容 |
| --- | --- |
| **怎么判断** | 段落时长是否平均得没有节奏；最长段与最短段的比例；有没有超长段（>12s）或超短段（<4s）；字幕 cue 密度是否均匀 |
| **证据来自哪里** | `subtitles` 闸 `measured` 各段 `StartMs/EndMs` 与 `audioMs`；`subtitles` 产物（cue 列表）；`script.scenes[*].duration_s`（声明值）与实测值的差 |
| **机器可判** | `[机]` 各段实测时长、极差、声明与实测的偏差；`[机]` `cues` 与 `audioMs` 推出的平均 cue 间隔 |
| **模型判** | `[混]` 「节奏起伏是否有意义」——模型读分镜原文判断哪一段需要放慢 |
| **注意** | 声明 `duration_s` 合计被 `DatasetScriptContract` 限在 30–75s，但**单段时长没有上下限**——这是现有契约的一个真实缺口，L3 报「节奏」问题时可以指出，但修复是否要动契约属于 L2 的改动，不在 L3 范围 |

### 3.8 `no_redundancy` —— 有没有冗余画面

| 项 | 内容 |
| --- | --- |
| **怎么判断** | 同一份数据点是否在多段重复呈现；相邻段的 focus mode 是否相同；有没有整段与上一段讲同一件事 |
| **证据来自哪里** | 各段 `Focus.mode` 与 `Focus.refs`；各段 facts 路径集合；`script.scenes[*]` 文本 |
| **机器可判** | `[机]` 相邻两段 focus mode 与 refs 完全相同（= 画面重复）；`[机]` 同一个 facts 路径在 3 段以上反复出现 |
| **模型判** | `[混]` 「虽然是不同数据点，但表达上是同一件事」——模型读文本判断 |
| **注意** | 与 3.3 的区别：3.3 判**叙述**冗余，3.8 判**画面**冗余 |

### 3.9 `closing_value` —— 结尾值不值得互动

| 项 | 内容 |
| --- | --- |
| **怎么判断** | 结尾是否给了一个可讨论的问题、一个判断、或一个提醒；是否命中空关注引导词表；结尾是否与片中主张一致（没有另起炉灶） |
| **证据来自哪里** | `script.closing`；`DatasetScriptContract.EMPTY_CLOSING`（`DatasetScriptContract.java:39-41`）；`subtitles` 闸 `closingStartMs/closingEndMs` |
| **机器可判** | `[机]` 是否命中 `EMPTY_CLOSING`；`[机]` 结尾区间长度 |
| **模型判** | `[混]` 「结尾提出的问题观众答得上、答得有劲」——模型读结尾原文判断 |
| **注意** | 「值得互动」不是「诱导点赞」。本条判的是**内容上有没有值得开口的点**，不是运营话术 |

---

## 4. P2-B：三类结论与报告字段结构

### 4.1 三类结论的划分标准

| 类 | 定义 | 判定依据充分性 | 谁能复核 | 置信度上限 |
| --- | --- | --- | --- | --- |
| `objective` 客观事实 | 由代码或外部工具算出，结论不经过模型 | 有直接证据（字段、工具读数） | **机器可复核**：同样的输入重跑得到同样的值 | `high` |
| `subjective` 模型主观判断 | 模型读了真实内容（脚本原文、字幕原文、焦点映射）后给出的判断，有直接证据但无客观刻度 | 有直接证据，但判据是语言性的 | **人可复核**；机器只能复核「引用是否属实」 | `medium` |
| `hypothesis` 模型推测 | 模型没有直接证据时的外推（「画面应该不乱」「这种题材观众会喜欢」） | **无直接证据** | 不可复核，只能被后续证据证伪 | `low` |

**分类规则**：证据不足以支撑结论的，一律降级为 `hypothesis`，不留在 `subjective`。举例：模型说「这段画面应该是干净的」——模型没看过画面，这是 `hypothesis`；模型说「这段旁白和旁白提到的极值对得上」——它读了焦点映射和脚本原文，这是 `subjective`。

### 4.2 报告 JSON 结构

落库位置：`heron_video_version.quality_report`（TEXT，NULL，与 `editorial_review` 同构的**就地扩列**）。

```json
{
  "schemaVersion": "quality-review/1",
  "versionId": 1900000000000000001,
  "mode": "final",
  "reviewedAt": "2026-11-19T10:20:30",
  "promptVersion": "quality-review-v1",
  "modelId": "<provider/model>",
  "criteria": [
    {
      "id": "hook_3s",
      "verdict": "pass",
      "class": "objective",
      "confidence": "high",
      "machineVerifiable": true,
      "severity": "none",
      "repairable": false,
      "evidence": [
        {"locator": "script.scenes[0].lead", "quote": "金价收于 2381 美元"},
        {"locator": "checks[subtitles].measured.scene1EndMs", "quote": "3120"}
      ],
      "reason": "首屏引用 facts.change，第一句即给出可验证结论；未命中 META_OPENING 词表；与标题无逐字重复。"
    },
    {
      "id": "pacing_variation",
      "verdict": "minor",
      "class": "subjective",
      "confidence": "medium",
      "machineVerifiable": false,
      "severity": "minor",
      "repairable": true,
      "evidence": [
        {"locator": "checks[subtitles].measured.scene2StartMs", "quote": "3120"},
        {"locator": "checks[subtitles].measured.scene2EndMs", "quote": "10980"}
      ],
      "reason": "第二段实测 7.9 秒，是全文最长段，但内容只是过渡；建议把结论提前到该段首句。"
    }
  ],
  "summary": {
    "pass": 5, "minor": 2, "fail": 1, "unknown": 1,
    "objectiveFail": 0, "subjectiveFail": 1, "hypothesisFail": 0,
    "objectiveVerified": 4, "subjectiveVerified": 3
  },
  "repair": {
    "attempted": 0, "max": 1, "changedSegments": [], "rechecked": false
  },
  "screenshot": {
    "level": "contact_sheet", "frames": 6, "extraBrowserRender": false, "reason": "模板与脚本均未变更，复用成片联络表"
  },
  "notes": []
}
```

**字段语义与不变量**：

| 字段 | 约束 |
| --- | --- |
| `verdict` | `pass` / `minor` / `fail` / `unknown`。**`unknown` 是合法值**，表示证据不足以判断，不得省略 |
| `class` | `objective` / `subjective` / `hypothesis` 三选一，**必填**，不允许省略成「判断」 |
| `confidence` | `high` / `medium` / `low`。与 `class` 有强约束：`objective` 不得为 `medium`/`low`；`hypothesis` 只能是 `low`；`subjective` 只能是 `medium` 或 `low` |
| `machineVerifiable` | `objective` 必为 `true`；`subjective`/`hypothesis` 必为 `false`（复核者是人或后续证据，不是机器） |
| `severity` | `none` / `minor` / `major` / `blocker`。**只有 `objective` 类允许 `blocker`** —— 主观意见不得升级成阻断 |
| `repairable` | 只有 `true` 的条目才允许进入定向修复白名单，见 §8 |
| `evidence[].locator` | 必须是**真实存在的定位符**：`script.*` / `dataset.*` / `checks[gate].measured.*` / `focus[sceneN].*` / `subtitles.cue[i]` 之一。**校验器必须逐条验证 locator 能解析**，解析不到 → 整条判 `unknown`，不是报错 |
| `evidence[].quote` | 原文片段，便于人一眼核对 |
| `summary` | 计数必须与 `criteria` 一致，**只分类计数，不合成单一分数**（见 §5） |
| `repair` | `max` 固定 1；`attempted` 不超过 `max`；`rechecked` 表示修复后是否重跑了 §8.3 的技术检查 |
| `screenshot` | 记录本次用了哪一级证据，不记录图片本身 |

### 4.3 报告落在哪、什么时候写

| 项 | 决定 |
| --- | --- |
| 存储 | `heron_video_version.quality_report`（TEXT NULL），就地扩列，**不新建表**（照 `027-editorial-review.sql` 的样子） |
| 写入时机 | 与 `complete()` 同一批写入，**一次写入、不可覆盖**（`complete()` 已有 isNull 守卫与 `changed != 1 → completion_conflict`） |
| 是否参与 `validateChecks` | **不参与**。`checks` 报告结构不变（白名单五字段） |
| 是否参与发布门禁 | **不参与**。自动发布仍然只认 `editorial_review` 的 PASS（`checkEditorialPass`） |
| 缺失时 | 与 `editorial_review` 不同——**`quality_report` 缺失不阻塞任何流程**，只是「这条片子没做过 L3 审稿」。这是有意的：L3 是辅助层，不是闸 |
| 重审怎么办 | 不覆盖。重新评审**产生新版本**（新 `version_no`），旧报告留在旧版本上。这样「当时怎么评的」永远可回溯 |

---

## 5. P2-C：明确禁止的四件事

### 5.1 禁止把主观分包装成客观指标

**具体禁令**：

1. 报告里**不允许出现单一「质量分」**。不许把九条判据加权成一个 0–100 的数字。`summary` 只允许分类型计数。
2. UI 上**不允许把 `objective` 与 `subjective` 混排在同一列或同一个排序里**。客观结论与主观判断必须分区呈现，各自有自己的标签。
3. 不许给 `subjective` 条目写「客观上…」这种句式；`class` 字段就是给这句话做校验的锚点。
4. 归因页引用 L3 结论时（例如「这条片子的风格统一性得分较高」），必须带 `class` 与 `confidence`，不得只引一个结论。

**为什么**：一旦主观分和客观指标混在一个数字里，读者无法再区分「这是算出来的」和「这是有人（或模型）说的」。这类混淆一旦发生，主观意见就会借着客观指标的信誉获得它不该有的权重。

### 5.2 禁止宣称分数代表爆款概率

**具体禁令**：

1. 报告与界面上**禁止出现任何形式的预测性表述**：「爆款概率」「预计播放量」「大概能到 X 万」「留存预估」。L3 输出的是「这条片子满足了哪些判据」，不是「这条片子会怎样」。
2. L3 的结论**不得进入表现数据的归因分析**，也不得与 `heron_news_metric` 的字段并列成一张表的两列（那会把「模型觉得好看」和「观众真的看了」混成一条证据链）。
3. P3 的数据结论也遵守同一条：归因表里的任何一行都不得表述为因果（「采用这种风格会提升完播」），只能是相关（「在已有样本里，这种风格的完播中位数更高」）。

### 5.3 禁止让模型判断绕过客观硬门禁

**具体禁令**：

1. L3 的任何结论**不得修改任何一道闸的 `passed`**。`checks` 报告在 L3 运行前后必须逐字节相同。
2. L3 的 `objective` 条目**不得由模型生成**。客观条目由 Java 计算，模型只产出 `subjective` 与 `hypothesis`。
3. **模型不得给客观条目打分或改判**。若模型认为某条客观判据算错了，它只能提交一条 `hypothesis`（置信度 low），不能直接改 `objective` 的结论。
4. L3 的 `severity: blocker` 只能出现在 `objective` 类条目上，且它**不触发任何自动阻断**——因为客观层的阻断已经由 L0 闸做了，L3 只是把同一件事复述一遍。
5. 定向修复（第 8 节）**不得放宽**任何一道 L0 闸：`dataset` / `evidence` / `schema` / `aigc_*` 的判据在修复前后必须完全一致。

### 5.4 追加禁止：不许用 L3 结论替代人的发布决定

`heron_video_version` 的 `review_status`（`pending`/`approved`/`rejected`）与人工审片流程**不变**。L3 报告挂在版本上供人参考，但**不预填、不建议、不自动**任何审片决议。理由与 5.3 同源：主观层一旦开始替人做决定，它的意见就会被人当成事实而不是意见。

---

## 6. P2-D：审稿 Agent 的输入输出契约

### 6.1 输入（读什么）

按证据强度从高到低：

| # | 输入 | 来源 | 必需性 |
| --- | --- | --- | --- |
| 1 | **客观判据结果** | Java 计算（`QualityCriteria`），每条含 `verdict` / `evidence` / `locator` | 必需 |
| 2 | 脚本 JSON | `heron_video_version.script` | 必需 |
| 3 | 事实集 | 生产期 `dataset.facts`（数据看板线）／`evidence` 片段（解说线） | 必需 |
| 4 | 视觉焦点映射 | `DatasetVisualFocus.forScene(scene, snapshot)` 的 `Focus(mode, refs, labels)` | 数据看板线必需 |
| 5 | 场景时间轴 | `NewsNarrationTiming.Span` 列表 + `subtitles` 闸 `measured` | 必需 |
| 6 | 闸门结果摘要 | `checks` JSON（每闸 `gate`/`passed`/`reason`/`measured`） | 必需 |
| 7 | 关键帧 | 见第 7 节的分级；**当前只有结构化描述可给模型** | 可选 |
| 8 | 已有 L1 审稿结论 | `editorial_review`（`issues[]` / `minorSuggestions[]`） | 可选：避免重复提同一条问题 |

**输入裁剪原则**：给模型的 prompt **只放它判不了客观、但需要读的内容**——即脚本原文、字幕分段文本、焦点映射、以及每条客观判据的**结论摘要**（不是原始 fields 堆砌）。把 `dataset` 整个快照塞进 prompt 会让模型试图自己算数，而 `dataset` 的算术责任在 L0 的 `data` 闸。

### 6.2 输出（写什么）

单个 JSON 对象，结构见 §4.2。硬约束：

1. **只输出 JSON**，不带 Markdown 围栏、不带解释文字（与 `DatasetScriptContract.md` 的「输出形态」一致）。
2. **九条判据一条都不能少**。缺一条 → 该条 `verdict: unknown`，`reason` 写明「模型未给出」。
3. `class` / `confidence` / `machineVerifiable` 三个字段**必填**。`objective` 条目由 Java 预填，模型不得改写（若模型输出了 `objective` 条目，解析器**丢弃**该条目并记 `notes`）。
4. `evidence[].locator` 必须来自 §6.1 的合法定位符集合，解析器逐条验证；验证失败 → 该条转 `unknown`。
5. `severity` 只能取 `none`/`minor`/`major`（`blocker` 不允许模型给，见 5.3）。
6. `repairable` 只能对落在 §8.2 白名单里的问题给 `true`；其他一律 `false`。
7. **禁止输出任何预测性数字或播放量估计**（5.2）。

### 6.3 失败时怎么办

| 失败形态 | 处理 |
| --- | --- |
| 模型返回不是 JSON / 不是对象 | 整份主观部分作废。**客观部分照常落库**（它不依赖模型），`summary` 里主观计数为 0，`notes` 记 `model_output_invalid`。**不重试** |
| 字段缺失 / 枚举非法（如 `verdict: good`） | 同上，整份主观部分作废。不做「尽力修复解析」 |
| 某条 locator 解析不到 | **只把该条**降级为 `unknown`，其余条目保留 |
| 模型给了 `objective` 条目或 `blocker` | 该条目**丢弃**并记 `notes`；其余保留 |
| 模型调用超时 / `model_failed` | 同「不是 JSON」。`OpenAiModelClient` 无自动重试（超时 300s），**不静默重试** |
| 整体 L3 未运行 | `quality_report` 为 NULL，版本照常完成、照常可发布（L3 不是闸） |
| 判定器解析后校验失败 | 抛 `409 quality_report_invalid`，**不落库半成品** |

**贯穿原则**：**L3 失败永远不能把一条好片子变成发不出去**。这是它不是闸的直接后果，也是它可以放心用模型的代价。

---

## 7. P2-E：分镜截图审查方案（有预算控制）

### 7.1 先说硬约束：模型现在看不见画面

`OpenAiModelClient.payload()` 只构造 `messages:[{role:"system",content:String},{role:"user",content:String}]`（`OpenAiModelClient.java:136-142`），**没有 image part**；仓库里也没有第二个支持视觉的客户端。

因此本设计的结论是：

> **在视觉通道建成之前，L3 的画面类判据只给「结构化画面证据」，不给「模型看了画面」的主观结论。**

这不是保守，是因为「模型说画面如何」如果模型没看画面，就是编的——那属于 §4.1 的 `hypothesis`，而 `hypothesis` 不能被当作审稿意见使用。

### 7.2 三级证据（按成本递增）

| 级 | 名称 | 产出方式 | 成本 | 什么时候用 |
| --- | --- | --- | --- | --- |
| **L-0** | 结构化画面证据 | `Focus(mode, refs, labels)` × 段落；`check --json` 的 `craft/layout/contrast` findings；`visual` 闸 `blankFrames` | **零额外成本**（生产时已经在算） | **默认Always**。所有正式生产任务 |
| **L-1** | 成片抽帧 | 从**已存在的成片 MP4** 按 `scene{1..N}StartMs/EndMs` 与 `closingStartMs/EndMs`，用 ffmpeg 每段中点抽 1 帧 | 一次 ffmpeg 调用，秒级，**不需要浏览器** | 抽样评审、争议复看、模板或分镜改动后的回归 |
| **L-2** | 浏览器重渲染截帧 | `hyperframes keyframes --shot <out>`（composition 级 onion 截帧；`--samples` 默认 9，支持 `--from/--to/--selector` 收窄） | **分钟级**，需要起浏览器 | **仅草稿/测试环境**、模板或 `template.json` `style.key` 改动后 |

**关键点**：L-1 不需要重新渲染。因为 `subtitles` 闸已经把每段在成片里的实测起止毫秒记下来了（`scene1StartMs` … `closingEndMs`），从成片按这些时间点抽帧就是「真实成片的真实画面」，比重新渲染更可信（渲染的是同一份 mp4，但时间是渲染过程的时间轴）。

**L-2 的门槛必须写死**：只有当**渲染输入的指纹变了**才允许跑 L-2。指纹 = `assets.templateKey` + `assets.fontSha256` + `assets.assetManifestSha256`（三个都在冻结快照里，`NewsProductionDomain.lockedAssets`）+ `template.json` 的 `style.key`。脚本改了、旁白改了、数据变了，**都不需要** L-2——那些只需要 L-1。

### 7.3 抽样规则

| 项 | 决定 |
| --- | --- |
| 什么时候抽样 | ① 默认**不抽样**（正式生产任务只跑 L-0）；② 人工在作品页/审片页发起「补充评审」→ 跑 L-1；③ 模板或视觉风格改动后的**回归抽查** → 跑 L-1，抽 3 条；④ 草稿/测试环境 → 允许 L-2 |
| 抽几张 | L-1：**每段 1 帧 + 结尾 1 帧**，上限 8 帧（段数上限 6 + 结尾）。L-2：`--shot` 默认 9 个等距 onion 样本，可 `--from/--to` 收窄到争议段 |
| 谁决定抽哪几条 | **人**（在版本列表勾选）。系统不做自动抽样——自动抽样会变成「随机给生产加成本」，且抽样结果没人看 |
| 截图存哪 | **不作为产物入库**。抽帧写到工作目录供当场评审，会话结束即弃；确需留证时用现有 `ProductionArtifactService.keepEvidence`（`.keep` 机制） |
| 截图能否作为 L3 报告的证据 | L-1 的帧**只能给人和未来的视觉通道**；当前 L3 报告里，画面类证据仍然写 L-0 的结构化证据，**并把 `screenshot.level` 如实记成 `contact_sheet` 或 `frames_extracted`** |
| 开销上限 | 单次评审会话：额外浏览器渲染 **最多 1 次**；L-1 抽帧**最多 1 次 ffmpeg 调用**。两者都记 `heron_usage_record`（`stage` 名 `quality-screenshot` / `quality-review`，长度 ≤ 32） |

### 7.4 为什么不给模型喂截图

即便将来接上视觉通道，本设计也**不默认**每条都喂图：

1. 成本与延迟：每条额外一次多模态调用 × 每条片子。
2. **模型看图会覆盖结构证据**：模型看到「画面挺好看」就不再看 `Focus.refs` 是否与旁白对得上。结构证据是确定性判据，不该被感受覆盖。
3. 本设计要的判据里，只有 `visual_sync`（配不配得上）需要看画面，而它**已经能由 `Focus.refs` × 旁白路径机器判定**。真正需要「人眼看」的是「好不好看」，而那是审片环节的事。

**结论**：截图审查的产出是**给人的**，不是给模型的。L3 的模型部分在当前架构下完全不需要图像。

---

## 8. P2-F：定向修复的安全边界

### 8.1 修几次

| 项 | 决定 | 依据 |
| --- | --- | --- |
| L3 定向修复上限 | **最多 1 次** | 与 `NewsProductionPipeline.reviewScript` 的「修一轮」对齐；`DatasetScriptAuthor.MAX_REPAIRS = 2` 是**写稿契约**修复，两者不叠加 |
| 触发条件 | 仅在 `mode = draft` / `mode = sample`（人工发起的补充评审）下允许自动修复 | **正式生产任务（`mode = final`）不允许 L3 自动改稿** |
| 正式任务要改怎么办 | 走既有路径：人工改稿 → 重新生产 → 新版本 | 改稿就是改稿，不该由审稿层偷偷做 |
| 修复预算记账 | 记 `heron_usage_record`（`stage = quality-repair`），与 `productionUsageService` 现有记账一致 | |

### 8.2 只允许修哪些问题（可枚举白名单）

**允许**（且仅当 `repairable = true`）：

| 类别 | 现象 | 修复动作 | 判定依据 |
| --- | --- | --- | --- |
| R1 | 首屏未兑现标题承诺 | 重写 `scenes[0].lead` | `objective`（标题复读 / 前两屏无 facts 已有规则）+ `subjective`（承诺不兑现） |
| R2 | 结尾空泛（命中 `EMPTY_CLOSING`） | 重写 `closing` | `objective` |
| R3 | 相邻两段文字重复 | 重写后一段 `body` | `objective`（facts 路径集合相同）+ `subjective` |
| R4 | 某段与标题/前文逐字重复 | 重写该段 | `objective`（`DatasetScriptContract.scenes` 的重复检查） |
| R5 | 段落声明时长与实测明显不符 | 调整 `duration_s` | `objective`（实测区间差） |

**明确不允许**（模型不得触碰）：

| 类别 | 为什么不允许 |
| --- | --- |
| 数据、哈希、`source_key`、`chart` | 由系统按快照覆盖（`DatasetScriptAuthor.withEvidence`），模型自述一律不信 |
| 事实引用的路径与取数 | 事实责任在 `data` 闸与 `DatasetContract`；改稿不能改变事实，只能改变「引用哪个事实」——后者要走 L2 的 story 信封 |
| 证据出处（`source_url` / `license`） | 由抓取覆盖 |
| 段落数量、总时长区间、字段闭集 | `DatasetScriptContract.FIELDS` / `SCENE_FIELDS` 是硬闭集 |
| 视觉、版式、字体、配色、动效 | 属渲染层与模板层，不在稿子上修；版式问题由 `check` 段与 `visual` 闸处理 |
| AIGC 角标与元数据 | `aigc_metadata` / `aigc_visible` 是合规项，**任何情况下不由 L3 触碰** |
| 段落顺序调换 | 会改变叙事结构，属 L2 的 story 契约范围 |

### 8.3 修复后必须重跑哪些真实技术检查

修稿不是「改个 JSON 就完事」。**任何一次修复之后，下面这一整套必须完整重跑**，一条都不能省：

| 序 | 检查 | 调用 |
| --- | --- | --- |
| 1 | 脚本契约 | `DatasetScriptContract.violations(script, dataset)` |
| 2 | 旁白数字与占位符 | `DatasetContract.narrationViolations(snapshot, narrationText)` |
| 3 | 区间语义 | `DatasetScriptContract.periodMeaning` |
| 4 | 视觉焦点重算 | `DatasetVisualFocus.forScene(scene, snapshot)` —— 引用路径变了，焦点必须重算 |
| 5 | 标题合法 | `DatasetTitleAuthor.valid(title, snapshot)` + 仍须是候选之一 |
| 6 | 全部 11 道 `DATA_GATES` | schema / evidence / data / audio / subtitles / render / decode / visual / aigc_metadata / aigc_visible / assets |
| 7 | **重新渲染 + 重新配音 + 重新出字幕** | 改一句旁白 → 音轨、cue 边界、画面全部变 |
| 8 | 重新出联络表 | `contact_sheet` 是成片的产物，成片变了它必须重出 |
| 9 | 场景对齐重算 | `NewsNarrationTiming.align` + `subtitles` 闸 `measured` |

**第 7 条是最容易被省掉也最不能省的一条**：只改脚本 JSON 而不重渲，产出的报告结论会与实际成片脱节——这比不修复更糟。

### 8.4 模型主观意见不得放宽事实 / 数据 / 证据 / AIGC 门禁

写成硬规则：

1. **修复指令里不允许出现「放宽」「豁免」「这次不算」这类语义**。修复提示词的模板里只允许列出「要改哪一段、改成什么、保留什么」。
2. 修复前后，`data` / `evidence` / `schema` / `aigc_metadata` / `aigc_visible` 五道闸的**判据与结论必须逐字节一致**。任何一道在修复后结论不同 → **本次修复作废**，整个修复流程回滚到修复前的稿子，并记 `notes`。
3. 修复提示词里**不得包含**「这是模型判断出来的」这类理由——模型的主观意见不能作为修改客观事实层依据的理由。
4. `subjective` 与 `hypothesis` 类的结论**不得触发**任何涉及事实层、证据层、合规层的改动；它们只能触发 §8.2 白名单里的 R1–R5。
5. 修复次数用尽后仍不通过 → **不继续修**，报告落 `verdict: unknown` / `repair.attempted = 1`，把问题交人。

---

## 9. P3-A：需要关联的内容属性（十二项）

**原则**：每项属性都必须能指到**生产链路上已有的字段 / 快照节点 / 实测值**。指不到的，写明「未记录」，**不允许另建一套平行数据**。

| # | 属性 | 取值 | 从哪个已有节点取 | 现在能不能取到 |
| --- | --- | --- | --- | --- |
| 1 | **选题类型** | `research` / `hotlist` / `data_board` | `heron_news_candidate.source_kind`（`043-candidate-source.sql` 加的列），经 `heron_video_version.work_id → heron_news_work.candidate_id` 关联；数据看板线看快照 `$.kind == "data_board"` | 解说线可取；数据看板线的候选是 `placeholderCandidate` 建的占位行（`NewsWorkServiceImpl.java:214-237`），`source_kind` 为 NULL → **必须按 `$.kind` 分流，否则会被算成热榜**（见 §11.6） |
| 2 | **数据机会类型** | `zscore` / `change`（`DatasetSignal.signalType`），另带 `extreme` / `coverage` / `flags` | 冻结快照 `$.selection.opportunityId` → `heron_data_opportunity.opportunity_id` → `signals` JSON（`signal_type`/`extreme`/`coverage`/`flags`） | 可取（走机会的片子）；非机会来源 → 未记录 |
| 3 | **栏目** | `tech` / `market` / `business` / `trend` / `atlas` / `other` | `Column.forSource(selection.dataSourceKey)`（`service/impl/Column.java`），**纯函数**，从快照 `$.selection.dataSourceKey` 推导 | 可取。**注意 `Column` 在 `service/impl` 不是 `domain`**，归因时需在 Java 侧映射 |
| 4 | **故事角度** | 自由文本 | 冻结快照 `$.editorial.storyAngle` 或 `$.selection.storyAngle` | 可取（`performanceBreakdown` 的 `angle` 维度已在用） |
| 5 | **标题钩子类型** | `数字反差` / `疑问悬念` / `利益相关` / `时间紧迫` / `反常识` | `prompt/data-board/title-contract.md` 的五类 kind。**当前 `DatasetTitleAuthor.parse()` 只留标题文本、丢弃 kind**（`DatasetTitleAuthor.java:39-58`） | **取不到 → 需要在生产期落一处**（见 §10.2） |
| 6 | **开头结构** | `data_first` / `question_first` / `claim_first` / `context_first` | 派生自 `script.scenes[0]`：含 facts 引用 → `data_first`；首句含问号 → `question_first`；其余按 LLM 归类。**脚本里没有这个字段** | **取不到 → 生产期落一处**（见 §10.2） |
| 7 | **视频时长** | 毫秒 | `heron_video_version.duration_ms`（`complete()` 写入的生产期实测值） | 可取 |
| 8 | **视觉风格** | `data-board` / `news-broadcast` / `data-investigation` | 冻结快照 `$.selection.templateId` → `data/templates/<id>/template.json` 的 `style.key`；或 `$.assets.templateKey` → LOCK.json | 可取。**这是确定性映射，不需要存** |
| 9 | **图表类型** | `line` / `bars` / `race` | `script.chart`（由 `DatasetScriptContract.chartFor(shape)` 覆盖写入） | 可取 |
| 10 | **旁白节奏** | 字/秒、段长分布 | `script` 全文字数 ÷ `checks[subtitles].measured.audioMs`；各段时长来自 `measured.sceneNStartMs/EndMs` | 可取（派生，不需存） |
| 11 | **配音音色** | `voiceKey` / `voiceVersion` | 冻结快照 `$.assets.voiceKey` / `$.assets.voiceVersion`（`LOCK.json`） | 可取 |
| 12 | **发布时间** | 时间戳 | `heron_video_version.publish_at`（人工发布回执写入） | 可取；未发布的片子为空 |

### 9.1 十二项里只有两项真的缺

| 属性 | 缺的原因 | 结论 |
| --- | --- | --- |
| 标题钩子类型 | 标题是**生产期**才从五个候选里选出来的（`DatasetScriptAuthor.author` 先 `titleAuthor.candidates()` 再让模型选），而 `parse()` 把 kind 校验完就丢了。冻结快照里没有它，因为冻结发生在写稿之前 | **需要在生产期落一处** |
| 开头结构 | 脚本 JSON 的字段是硬闭集（7 个顶层 + 3 个段落字段），加字段要动五处 | **需要在生产期落一处** |

其余十项都能从**冻结快照 + 现有表 + 生产期实测**取到或确定性派生。

---

## 10. P3-B：存储设计

### 10.1 不新建表

| 需求 | 落在哪 | 说明 |
| --- | --- | --- |
| 表现数据 | **`heron_news_metric`（已有）** | 一版本多采样、取最新一条的规则不变 |
| 内容属性（可取到的十项） | **不落库**，从 `heron_video_version.input_snapshot`（JSON）取 | 与 `performanceBreakdown` 现有的 `json_extract` 做法一致 |
| 内容属性（缺的两项） | **`heron_video_version.content_profile`（JSON，NULL，新建列）** | 就地扩列，照 `027` 的样子 |
| 版本 ↔ 作品 ↔ 候选 | **已有三表关系** | `heron_video_version.work_id → heron_news_work.candidate_id → heron_news_candidate` |
| 数据机会 | **`heron_data_opportunity`（已有）** | 经 `$.selection.opportunityId` 关联；`used_version_id` 列已存在但当前无人写入（`HeronDataOpportunity.java:40`），归因用 `opportunity_id` 正向查即可，不必依赖它 |
| 人工覆盖记录 | **`heron_auto_slot` 就地扩列**（照 `026-editorial-run.sql` 的做法） | 见 §12.3 |
| L3 质量报告 | **`heron_video_version.quality_report`（TEXT，NULL，新建列）** | 见 §4.3 |

**新增列清单（本次不执行，仅设计）**：

```
heron_video_version.content_profile  JSON  NULL   -- 内容属性补充（hookType / opening / schemaVersion）
heron_video_version.quality_report   TEXT  NULL   -- 成片级审稿报告 JSON
heron_auto_slot.strategy_override    JSON  NULL   -- 人工策略覆盖记录
```

迁移文件按仓库约定放 `ruoyi-admin/src/main/resources/db/migration/V49__news_quality_and_content_profile.sql`（下一个可用序号；`V47`/`V48` 已在工作区未提交，实施时需先确认序号）。

### 10.2 `content_profile` 的结构

```json
{
  "schemaVersion": "content-profile/1",
  "hookType": "数字反差",
  "opening": {
    "shape": "data_first",
    "hasFact": true,
    "hasQuestion": false
  },
  "writtenAt": "2026-11-19T10:20:30"
}
```

不变量：

- `hookType` 必须是 `title-contract.md` 的五类之一；取不到（不是数据看板线 / 老版本）→ **字段缺失，不写**。
- `opening.hasFact` / `hasQuestion` 由 Java 从 `script.scenes[0]` 算；`opening.shape` 是归类，取不到 → 整个 `opening` 不写。
- `content_profile` 为 NULL 或字段缺失时，归因一律归「未记录」，**不猜、不补**。
- 该列**不是闸**：`complete()` 写不写它都不影响版本完成。

### 10.3 「未记录」的统一表达

现有 `performanceBreakdown` 已经用 `coalesce(..., '未记录')` 统一处理缺失（`HeronNewsMetricMapper.xml`）。本设计**沿用同一口径**，不发明第二套：

- 分组桶名 = `未记录`；
- **不参与**该维度的排序比较；
- 在 UI 上以灰标（前端已有 `.is-unknown` 样式）呈现。

### 10.4 不新建平行数据的自查

每加一个字段都要问一句：**这个值是不是已经能从冻结快照 / 现有表 / 生产期实测拿到？**

- 能 → **不存**，归因时现取。
- 不能，且**生产期才知道**（标题钩子、开头结构）→ 存在版本行的一个 JSON 列里（`content_profile`）。
- 不能，且**根本不会被知道**（老版本没有钩子类型）→ **不写**，归「未记录」。

**明确不做**：不建「内容属性表」（`heron_content_profile`）、不建「归因中间表」、不建「爆款预测表」。

---

## 11. P3-C：样本量与置信度约束

### 11.1 现状的问题

现有 UI 只有一档：`samples >= 3 → 可参考`，否则 `样本不足`（`heron-ui/src/views/heron/analytics/index.vue:63`）。3 条样本在统计上就是噪声，这个阈值太松；而 3 条以下连「方向」都说不准，太松和太紧同时存在。

### 11.2 分级门槛（本设计的口径）

| 档 | 样本量 n | 允许得出的结论 | 允许做什么 | 禁止做什么 |
| --- | --- | --- | --- | --- |
| **L0 无样本** | n = 0 | 无 | 只显示「暂无数据」 | 不显示任何统计量 |
| **L1 观察** | 1 ≤ n < 3 | 只有**原始样本**（每条视频的播放/完播），没有任何聚合结论 | 逐条查看 | 不显示均值/中位数作为结论；不参与排序 |
| **L2 弱信号** | 3 ≤ n < 10 | 描述性观察：「这一组已有 5 条，完播中位数 42%」 | 显示中位数与区间，标「样本不足，仅供参考」 | 不进入策略建议；不作为「哪种更好」的依据 |
| **L3 可用观察** | 10 ≤ n < 30 | 分组层面的**相关**观察 | 可进入人工采纳的候选建议（需人工确认） | 不表述为因果；不改自动策略 |
| **L4 数据结论** | n ≥ 30 且**跨 ≥ 2 个自然周** | 可作为「有证据的数据结论」 | 可进入策略建议 | 仍不得表述为因果 |

**跨周要求**为什么必要：n ≥ 30 但全在同一天/同一周，很可能是那一天的账号状态或平台推荐波动造成的，不是内容属性的效果。

### 11.3 怎么表达「样本不足」

1. **不编造数字**。样本不足时**不显示**平均值/中位数，只显示 n 与原始样本列表。
2. 明确写清**缺的是什么**：是「样本条数不足」还是「时间跨度不足」。两者是不同的缺口——`n=40 但全在同一天` 属于 L2 而不是 L4。
3. 前端已有 `samples >= 3 ? '可参考' : '样本不足'` 的标签，**要升级为五档**（`heron-ui/src/views/heron/analytics/index.vue:63`），并保留旧档位的措辞以免改动既有文案含义。
4. 归因表里 n 与**档位**必须同屏，不能只显示百分比。

### 11.4 怎么避免单条爆款带偏整套策略

四条具体机制：

| 机制 | 做法 | 依据 |
| --- | --- | --- |
| **用中位数，不用均值** | 分组统计主指标改为 **p50（中位数）**，均值只作为附属。现有 SQL 用 `avg(m.plays)`（`HeronNewsMetricMapper.xml`），需要补中位数 | 均值被单条极值拉动；中位数不会 |
| **离群比可见** | 同时给出 `max / p50` 的比值。**比值 > 5** 时该组标注「含极端离群样本」，并单独列出该条样本 | 让「一条爆款撑起整组平均」这件事**看得见**，而不是被平均值藏起来 |
| **留一稳定性检验** | 对每条分组结论，做一次「去掉最高的那条后结论方向是否改变」的检验。**方向改变 → 该结论降一档**，并标 `unstable` | 一条爆款决定方向的结论，本质上就是一条爆款的结论 |
| **结论要能表述为区间** | 分组结论一律写成「n 条，中位数 X，四分位区间 [A, B]」，**不写「提升 Y%」** | 区间让「其实差别没那么大」自己显形 |

**离群样本本身不删**。删掉爆款会掩盖「这类内容能爆」这个事实——那是选题情报，不是噪声。处理方式是**标记**而非**剔除**。

### 11.5 中位数的实现

MySQL 8 没有 `median()`。实现路径：**在 SQL 里用窗口函数排出每组有序行，取中位行**；或者**SQL 取明细，Java 侧算分位数**。

推荐后者：`performanceBreakdown` 增加一个取明细的查询（每组最多 N 条明细 + 组内计数），分位数在 Java 侧用 `heron` 已有的集合工具算。理由：分位数在 Java 侧可单测，且不把窗口函数塞进一个已经用 `<choose>` 分了四个维度的 SQL 里。

**注意**：现有 `NewsMetricServiceImpl.GROUP_BY_KEYS` 是白名单（`{template, line, angle, source}`），因为维度值会进 XML 的 `<choose>` 分支。**新增维度必须同时改白名单与 XML 分支**，且未知维度必须继续 400（`metric_dimension_invalid`）。

### 11.6 新增归因维度需要改的联动点

| 位置 | 改动 |
| --- | --- |
| `HeronNewsMetricMapper.xml` `performanceBreakdown` | 新增 `<when>` 分支（每个新维度一个分支） |
| `NewsMetricServiceImpl.GROUP_BY_KEYS` | 加入新维度键 |
| `heron-ui/src/api/heron/metrics/index.ts` | `MetricDimension` union 加成员 |
| `heron-ui/src/views/heron/analytics/index.vue` | `tabs` 数组加 tab + `bucket` 标签 |

**先修既有错误，再谈新维度**：`source` 维度当前的 `case when c.source_kind = 'research' then 'research' when c.candidate_id is null then '未记录' else 'hotlist' end` 分桶，对数据看板线是错的——该线的候选是 `NewsWorkServiceImpl.java:214-237` `placeholderCandidate` 建的占位行，`source_kind` 为 NULL 而 `candidate_id` 非空，于是落进 `else 'hotlist'`。**当前形态的数据看板片在归因里被算成「热榜」**（证据见 §1、§9 第 1 行）。

修法（已落地）：读侧分桶移到 Java（`MetricInsightAnalyzer.sourceBucket`），先看快照 `$.kind`（trend / data_board），再看候选来源，取不到归「未记录」——比原设计的 SQL case-when 多覆盖了 trend 子线；写侧 `placeholderCandidate` 现在写入 `source_kind = 'data_board'`（常量 `HeronNewsCandidate.SOURCE_DATA_BOARD`），存量占位行由 `047-candidate-source-data-board.sql` / `V53` 回填（迁移未执行，需库变更授权）。测试：`MetricInsightAnalyzerTest`（读侧）、`NewsPlaceholderSourceKindTest`（写侧）。

`$.kind` 是冻结快照的既有字段（`FrozenContext` 节点之一，`SCHEMA = "1.0"`），不需要新增任何数据。**V53 回填执行前，任何按 `source` 做的历史表现结论仍不可用**（§11.4 的样本量与置信度约束以分桶正确为前提；新数据读侧已按快照分流，不受回填影响）。

**新增维度建议**（按 §9 的十二项属性，且都能稳定分桶）：

| 维度 | 分桶来源 | 说明 |
| --- | --- | --- |
| `column` | `$.selection.dataSourceKey` → Java 侧 `Column.forSource` 映射 | **两段式**：SQL 先按 `sourceKey` 分桶出明细，Java 侧再映射成栏目并二次聚合 |
| `style` | `$.selection.templateId` → `template.json` 的 `style.key` | 同样两段式 |
| `chart` | `script.chart` | `heron_video_version.script` 是 JSON 列，MySQL 可 `json_extract` |
| `hook` | `content_profile.hookType` | 需要新列（§10.2） |
| `opening` | `content_profile.opening.shape` | 需要新列（§10.2） |
| `duration` | `duration_ms` 分箱（如 <35s / 35–50s / >50s） | 分箱规则要写死并记录在文档里 |
| `voice` | `$.assets.voiceKey` | |
| `signal` | `heron_data_opportunity.signals.signal_type`（需 join 机会表） | |
| `publishHour` | `publish_at` 的小时分箱 | 发布时间对表现的影响众所周知，但本设计只做**相关**归因 |

**不加的维度**：任何需要 LLM 重新判断的属性（如「这条片子的叙事好不好」）——那是 L3 的报告，不是归因维度。见 §5.2 第 2 条。

---

## 12. P3-D：编辑策略如何参考历史表现（且人能覆盖）

### 12.1 三条硬边界

1. **表现数据不能自动改变策略**。现有分析页明确写着「还不能做的：让编辑策略自动参考历史表现。样本量不足时自动参考等于放大偶然」——**这个判断是对的，本设计不改它**，只定义「什么时候可以开」以及「开了之后人怎么管」。
2. **样本量门槛先于一切**。§11.2 的档位没到，任何分组结论不得进入策略建议。
3. **人的覆盖必须是显式记录**，不是隐式改写。

### 12.2 策略参考的形态：参考卡，不是自动开关

不做「自动把表现数据塞进 `daily-editor.md` 提示词让模型自己判断」。做**参考卡**：

| 项 | 决定 |
| --- | --- |
| 形态 | 编辑决策页显示一张「历史表现参考」卡：按当前候选的维度（栏目 / 故事角度 / 风格 / 图表类型）列出历史分组的中位数与样本量，**附档位标签** |
| 数据来源 | §11.6 的归因查询（`heron_news_metric` + 快照 join） |
| 只显示达到 L2 以上的档位 | L1 的组只列原始样本，不给聚合值 |
| **不进提示词** | 不把表现数据写进 `prompt/editorial/daily-editor.md` 的输入。模型看到「这个风格历史上更好」就等于让它有了策略倾向，而 L3 刚禁止了「模型用印象代替证据」——同一个道理，编辑决策也一样 |
| 决策人 | 总编辑（`EditorialDecisionService`）**看到卡，自己决定用不用**。用不用都要写进 `reason` |

**为什么不自动进提示词**：总编辑的决策会被记进 `heron_auto_slot.decision_reason`（`026` 已加的列），是「当时为什么这么选」的审计依据。如果它的理由里混进了一条 n=3 的历史分组，这条理由以后就说不清了。

### 12.3 人工覆盖：三种动作，显式记录

沿用 `heron_content_decision` 的成熟模式（`status` + `decided_by` + `decided_at` + `decision_note` + 各类 `override_reason`）。落到 `heron_auto_slot.strategy_override`（JSON）：

```json
{
  "schemaVersion": "strategy-override/1",
  "action": "lock",
  "scope": "column:market",
  "value": "全球市场栏目本周不做",
  "reason": "上周该栏目三条完播都低于 30%，且与商业观察室重复",
  "decidedBy": 1900000000000000001,
  "decidedAt": "2026-11-19T09:00:00",
  "scopeUntil": "2026-11-26T00:00:00",
  "source": "manual"
}
```

| 动作 | `action` | 含义 | 作用域 |
| --- | --- | --- | --- |
| **锁定** | `lock` | 某维度在某段时间内不得被自动/模型建议改变 | `scope` + `scopeUntil` |
| **否决** | `veto` | 否决某一条具体的建议 | 建议 ID |
| **改写** | `override` | 用人的判断替代模型/系统的建议 | 建议 ID + 新值 |

**不变量**：

1. 覆盖**不改写**原决策记录。`heron_auto_slot.decision_reason` / `selected_line` / `editorial_intent` 保持原样；覆盖是**追加**的一层。
2. 覆盖必须**有理由**（`reason` 非空），理由长度上限与 `decision_reason` 一致（500）。
3. 覆盖必须**有操作人与时间**（`decidedBy` / `decidedAt`）。
4. `lock` 必须有 `scopeUntil`——没有期限的锁会永久改变策略且没人记得。
5. 覆盖生效期间，参考卡上必须显示「本维度已被人工锁定」及锁定理由，**而不是把参考卡藏起来**。人要看得见自己锁了什么。
6. 覆盖记录**只追加不删除**。要解除，就再写一条 `action: "unlock"` 的记录。

### 12.4 覆盖如何影响决策链

```
总编辑决策（EditorialDecisionService）
   │
   ├─► 读参考卡（表现数据，档位 ≥ L2）
   │      └─ 被 strategy_override.lock 命中的维度：显示「已锁定」+ 理由，不给聚合结论
   │
   ├─► 总编辑决定（可用可不用参考卡）→ decision / selected_line / reason / editorialIntent
   │
   └─► 落槽位：heron_auto_slot
          ├─ decision_reason / selected_line / editorial_intent   ← 原决策，不改
          └─ strategy_override                                    ← 追加的覆盖层
```

**被锁维度被总编辑"违反"时怎么办**：不自动拦。锁是给**自动建议**用的（§12.2 不做自动建议，实际是给未来的自动策略用的）。人可以在有理由的情况下违反锁，**但必须在 `reason` 里说明**——这一条写进 `daily-editor.md` 的提示词增量。

---

## 13. P3-E：四类结论在产品里怎么分别呈现

这是本节的核心：**四类结论不能混**。

| 类 | 是什么 | 视觉与语义 | 数据来源 | 允许的行动 |
| --- | --- | --- | --- | --- |
| **① 有证据的数据结论** | 分组表现的事实描述 | 「结论」区，**正常色**，带 n、档位、四分位区间、离群比 | `heron_news_metric` + 快照 join | 可进策略建议；可作为否决某条建议的依据 |
| **② 样本不足的观察** | 分组表现但样本不够 | **同一张表的弱化行**，灰标 + 明确写「n=2，样本不足」+ 原始样本列表 | 同上 | **不可**作为任何决策依据；不可排序 |
| **③ 模型提出的策略假设** | 模型给的策略猜测（「这种风格可能更受欢迎」） | **独立的「假设」区**，带来源（哪个提示词版本、哪次决策）、**明确标注「假设，未验证」**、带可证伪条件 | 模型输出（未来从 `heron_content_decision` 那类记录出） | 只能作为待验证项；**不得**呈现为结论 |
| **④ 尚未验证的创作建议** | 创作层面的建议（「这条适合加一个对比」「下一条试试换个角度」） | **独立的「建议」区**，带来源（人 / L3 报告）、标注「未验证」 | 人工输入 / L3 `subjective` 与 `hypothesis` 条目 | 采纳与否是人的事；采纳后**必须显式记录**（§12.3） |

### 13.1 呈现规则（硬性）

1. **不合成总分**。四类各自呈现，**不出现**「内容质量综合分」「历史表现分」这种东西（呼应 §5.1）。
2. **① 与 ② 同表不同档**（同一个归因表，按 n 分档显示）；**③ 与 ④ 各占独立区**，不与 ①② 混排。
3. ③ 区每一条必须写清**「怎么验证」**：需要多少样本、看哪个指标。写不清可证伪条件的假设不该进这个区。
4. ④ 区的每一条必须标**来源**（人写的 / L3 模型的 `subjective` / `hypothesis`），且 `hypothesis` 来源的必须再标一次「这是推测」。
5. **②不能被折叠掉**：样本不足的行必须默认可见并明确写「样本不足」，不能折叠进「展开更多」。
6. 任何一区的内容都**不得**用「预计」「将会」「能提升」这类预测性措辞（呼应 §5.2）。
7. L3 报告（④ 区的来源之一）在归因页出现时，**必须带 `class` 与 `confidence`**，不得只显示结论文字（呼应 §5.1 第 4 条）。

### 13.2 与现有前端的关系

现有 `heron-ui/src/views/heron/analytics/index.vue` 已有三块结构：工具栏 + 归因表 + 「这套数据现在能回答什么」说明板。本设计在此基础上：

| 现有 | 改为 |
| --- | --- |
| 归因表，单一「可信度」列（`可参考`/`样本不足`） | 归因表，**可信度列升级为五档**（§11.2），并补 p50、四分位区间、离群比；样本不足的行显示原始样本 |
| 说明板的三条（能回答 / 不能回答 / 还不能做的） | 保留，并补一条「本区结论的四种类型」，把 ③④ 两种非数据结论的呈现规则写给使用者看 |
| （无） | 新增「模型假设区」与「创作建议区」两个独立卡片 |

---

## 14. 实施清单（后续用，本次不动代码）

### 14.1 新增

| 文件 | 用途 |
| --- | --- |
| `service/impl/QualityCriteria.java` | 九条判据的**客观**计算（纯函数，可单测） |
| `service/impl/QualityReviewer.java` | 模型主观部分（stage `quality-review`）+ 解析 + 校验 |
| `service/impl/QualityReportContract.java` | 报告结构校验（三类结论约束、locator 可解析、summary 计数一致） |
| `service/impl/QualityRepairPlanner.java` | §8.2 白名单判定 + 修复指令构造 + 修复后重跑清单 |
| `service/impl/ContentProfileWriter.java` | 生产期落 `hookType` / `opening`（§10.2） |
| `service/impl/MetricBreakdownStats.java` | p50 / 四分位 / 离群比 / 留一稳定性（Java 侧算分位数） |
| `service/impl/StrategyReferenceService.java` | 参考卡组装（归因查询 + 覆盖过滤） |
| `resources/prompt/review/quality-review.md` | L3 审稿提示词（只判主观与假设） |
| `resources/prompt/review/quality-repair.md` | 定向修复提示词（R1–R5 白名单） |
| `domain/vo/NewsQualityReportVo.java` | 报告 VO |
| 迁移 `V49__news_quality_and_content_profile.sql` | 三列扩列 |
| 测试 | `QualityCriteriaTest`、`QualityReportContractTest`、`QualityRepairPlannerTest`、`MetricBreakdownStatsTest`、`StrategyReferenceServiceTest`、`ContentProfileWriterTest` |

### 14.2 修改

| 文件 | 改动 |
| --- | --- |
| `NewsProductionDomain.java` | `complete()` 增写 `quality_report` / `content_profile` 两列（isNull 守卫，与其余列同批） |
| `NewsProductionPipeline.java` | 渲染与闸门**之后**跑 L3（客观部分）；把客观判据结果与素材交给 `QualityReviewer` |
| `DatasetTitleAuthor.java` | `parse()` 保留 `kind`（返回 `title` + `kind`），供 `ContentProfileWriter` 落 `hookType`；**不改**标题契约本身 |
| `DatasetScriptAuthor.java` | 把选中的标题与其 kind 传给下游 |
| `HeronNewsMetricMapper.xml` | `performanceBreakdown` 补新维度分支 + 取明细（明细走 Java 侧算分位数）；**先修 `source` 分桶**：按快照 `$.kind == 'data_board'` 分流，占位候选不再被算成热榜（§11.6） |
| `NewsMetricServiceImpl.java` | `GROUP_BY_KEYS` 扩白名单；新增明细查询方法；档位判定 |
| `NewsMetricController.java` | 新增参考卡查询接口（`heron:newsWork:list`） |
| `EditorialDecisionService.java` | `Pools` 增加参考卡载荷（**只给人看，不进提示词**；`daily-editor.md` 增一条「被锁维度需在 reason 中说明」） |
| `heron_auto_slot` | 就地扩 `strategy_override` 列 |
| `heron-ui/.../metrics/index.ts` | `MetricDimension` 扩 union；补 p50/分位/档位类型 |
| `heron-ui/.../analytics/index.vue` | 五档可信度、新增「模型假设区」「创作建议区」 |

### 14.3 明确不改

- `DatasetScriptContract.FIELDS` / `SCENE_FIELDS`（7 + 3 硬闭集）；`NewsProductionDomain.DATA_KINDS` / `DATA_GATES`（7 类产物 / 11 道闸）；
- `FrozenContext.SCHEMA`（仍 `"1.0"`）；
- `checks` 报告结构（`validateChecks` 五字段白名单与 `measured` 只许数值/布尔）；
- `heron_news_metric` 表结构；`heron_data_opportunity` 表结构；
- 自动发布门禁（仍只认 `editorial_review` PASS）；
- `prompt/data-board/script-contract.md` 与 `title-contract.md` 的契约本身。

---

## 15. 风险与取舍

| 风险 | 说明 | 处置 |
| --- | --- | --- |
| 主观层被当成客观层用 | 最要命的风险。一旦 UI 把 `subjective` 和 `objective` 混排，读者无法区分 | 报告 `class` 必填 + 校验器强制置信度对应关系 + UI 分区 + 不许合成总分（§5.1） |
| 模型开始「看画面」后编造 | 现在模型看不见画面，所以画面类结论只能是结构化的；将来接了视觉通道，风险反而上升 | 视觉结论仍须 `class: subjective`，且不得覆盖 `Focus.refs` 的机器判定（§7.4） |
| 归因维度膨胀 | 每加一个维度，SQL `<choose>`、白名单、前端 union、tab 四处都要改 | 维度必须先回答「它稳定吗、能分桶吗、与别的维度重合吗」；§11.6 列出建议集合但**不要求一次全做** |
| 中位数需要重写 SQL | 现有 SQL 用 `avg`，改成分位数是实打实的改动 | 推荐「SQL 取明细 + Java 算分位数」，不动现有 `<choose>` 结构（§11.5） |
| 样本量永远达不到 L4 | 表现数据靠人工回填，样本增长极慢 | 这是**如实反映现状**，不是缺陷。分级门槛的设计目的就是让「不够」被诚实标出，而不是逼出一个假的结论 |
| 修复后不重渲染 | 最容易犯也最伤：报告与成片脱节 | §8.3 第 7 条写死；修复流程必须整链重跑；未重跑则 `repair.rechecked = false` 并降级报告可信度 |
| 人工覆盖变成隐形改写 | 覆盖若无记录，等于策略被悄悄改过 | 覆盖只追加不改写原记录；必须有理由、操作人、时间、期限（§12.3） |
| 内容属性两处缺失 | 标题钩子与开头结构现在取不到 | 明确标为「需要在生产期落一处」，不假装能取到；老版本归「未记录」 |

---

## 16. 明确不做

- 不让 L3 的结论参与发布放行或阻断（L3 不是闸）。
- 不把主观结论写进 `checks`（结构上不允许，也不应该）。
- 不给单一「质量分」，不做「爆款概率」预测。
- 不建新的内容属性表、归因中间表、预测表。
- 不在样本量不达标时自动改编辑策略。
- 不把表现数据写进 `daily-editor.md` 提示词（人看卡，模型不自动用）。
- 不让 L3 触碰事实层、证据层、合规层与渲染层。
- 不实现自动采集平台表现（凭据未到位前不做；`heron-ui` 现有说明保持）。
- 不重写 `2026-11-19-data-board-story-plan.md`；L3 与 story/1 是**上下两层**：story 判「有没有故事、故事是否兑现」，L3 判「这条片子好不好看」。

---

## 17. 未验证项

本文是**纯设计**，以下均未验证：

1. 未跑任何构建或测试（`mvn -pl ruoyi-admin -am package` 未执行）。
2. 未启动应用、未连 MySQL/Redis、未做端到端出片。
3. 未验证 `hyperframes keyframes --shot` 在本仓库的三个模板上实际产出什么帧、耗时多少——§7.2 的 L-2 成本估计（分钟级）是基于 CLI 参数与实现的推断，**未实测**。
4. 未验证从成片按 `sceneNStartMs` 抽帧的可行性（ffmpeg seek 到毫秒级精度是否够用）——**未实测**。
5. 未验证 `heron_video_version.script` 作为 JSON 列在 MySQL 8 上的 `json_extract` 行为与索引可用性（`chart` 维度的归因依赖它）。
6. 未确认下一可用迁移序号（工作区已有未提交的 `V47`/`V48`）。
7. 未与编辑/运营方确认 §11.2 的档位阈值（3/10/30、跨 2 周）——这些是**设计假设**，需要用真实样本量校准后固化。
8. 未确认 `Column` 从 `service/impl` 迁到可被归因层复用的位置是否合适（当前是枚举在 `service/impl`，两段式映射在 Java 侧做，不影响正确性，但位置不理想）。
