# 编辑决策链与发布安全链

**日期**：2026-11-12
**范围**：`heron` 后端。前端只改编辑策划页展示（消费新快照字段），不新增页面。
**前置**：`2026-11-11-heron-console-redesign.md`（已交付的控制台重构）、`2026-11-11-data-editorial-engine-design.md`（v2 分层决策设计）
**第二阶段**：场景化视频表达引擎，本文不含，见 §7 边界。

---

## 0. 一句话

两个问题，一个病根：**决策没有变成生产输入**，**审稿没有变成发布依据**。

不是四个独立缺陷（选题选错、模板选错、缺审稿、审稿契约有洞），是同一条链路上决策被丢弃、结论不落盘。

---

## 1. 现状取证

以下全部在 2026-11-12 的 `dev` 上核实，不是推断。

| 断言 | 位置 | 事实 |
|---|---|---|
| 预选候选先于总编辑 | `NewsAutoService.tick()` | `pickCandidate()` 先出最终候选，`editorial.decide()` 后调 |
| 候选池分了两线但分完没用 | `NewsAutoService.pools()` | 分了 `hotHeadlines` / `articleCandidates`，认领时仍用 `candidateId.get()` |
| 自动线不用模板选择器 | `NewsAutoService.templateFor()` | `findFirst()` 取第一个前缀匹配；`NewsTemplatePicker` 只服务手工路径 |
| Snapshot 不知道决策 | `NewsProductionDomain:80-91` | 由 `work + candidate + assets` 拼出 |
| `kind` 靠模板名反推 | `NewsProductionDomain:89` | `templateKey.startsWith("news-data-")` |
| 解说线没有审稿 | `NewsProductionPipeline:116/118` | `reviewScript()` 只在 `if (dataBoard)` 分支 |
| 审稿结论不落盘 | `EditorialReviewer.Verdict` | 只在 Pipeline 内存里活一次，无持久化 |
| 自动发布不查审稿 | `NewsWorkServiceImpl.confirmAs()` | 直接 `reviewAs(..., "approved")` 后发布 |
| FAIL 契约有洞 | `EditorialReviewer:84` | `FAIL` 只要求 issue 非空，不要求 BLOCKER/MAJOR |
| 场景契约无场景类型 | `NewsScriptContract.SCENE_FIELDS` | 只有 `lead / body / duration_s` |
| 音频资产未接 | `NewsProductionPipeline` | `data/audio/bgm` 6 首、`sfx` 3 个，代码零引用 |

**最严重的一条**：`confirmForAuto` 不检查审稿是否发生过。任何一版只要渲染成功，自动确认就会发布——数据线调了审稿也一样，因为结论不进版本记录。

---

## 2. 决策链：让决策成为生产输入

### 2.1 新的调用顺序

```
tick(slotTime)
 ├ 1 建池    EditorialCandidatePoolBuilder
 │            ├ DataBoardPool ：热榜候选 + probe=ok 且 7 天未用的数据集
 │            └ ArticlePool   ：仅「有正文」的 RSS 候选（含正文片段与图片）
 ├ 2 总编辑  editorial.decide(pools) → Decision(line, intent, reason)
 ├ 3 线内编辑 lineEditor.edit(line, pools, intent)
 │            → Selection(candidateId, evidenceIds, datasetId, templateId, storyAngle)
 │            ※ 数据线返回 NO_FIT 时回退解说线再调一次（+1 次调用）
 ├ 4 认领    newsWork.claimForAuto(Selection)
 └ 5 提交    generateForAuto(workId, requestKey, Decision, Selection)
              └ reserve() 一次性冻结 Snapshot
```

**强制要求：`Decision + Selection` 必须从 `tick` 一路传到 `reserve`。**

这是上一轮评审补的关键环节。`claimForAuto(Selection)` 收到 Selection 不等于 `reserve()` 拿得到——中间隔着 `ProductionSubmitBo`。所以：

```
tick → claimForAuto(Selection) → generateForAuto(..., Decision, Selection)
     → ProductionSubmitBo 携带上下文 → reserve() 冻结
```

不新建决策表存临时的 Decision/Selection，也不给 `heron_news_work` 加一套重复字段。

### 2.2 三处改动

| 位置 | 动作 | 验收 |
|---|---|---|
| `NewsAutoService.tick()` | 删预选候选；总编辑后调线内编辑 | 选择结果来自对应生产线候选池 |
| `NewsWorkServiceImpl.claimForAuto` | 入参从 `(candidateId, actor, templateKey)` 改为 `(Selection)` | 候选/模板/数据集/角度来自同一 Selection |
| 生产提交命令 | `ProductionSubmitBo` 携带 Decision + Selection | 上下文不丢失 |
| `NewsProductionDomain.reserve()` | 构造带 `schemaVersion` 的快照 | `kind` 显式来自 `selectedLine` |
| `NewsAutoService.templateFor()` | **删除** | 自动路径不再二次选模板 |

**`templateFor()` 直接删，不留兜底。** 它本身就是「决策被丢弃后重新决策」的证据；留作 fallback 等于给下一次退化留门。已核实无单测覆盖（`NewsTemplatePickerTest` 只有两条纯规则测试，与它无关）。

### 2.3 快照结构

```json
{
  "schemaVersion": "1.0",
  "workId": "...",
  "candidateId": "...",
  "kind": "data_board",
  "versionNo": 1,
  "revision": 2,
  "title": "...",
  "draft": "...",
  "evidence": { "items": [] },
  "assets": { "templateKey": "...", "voiceKey": "...", "fontSha256": "...", "assetManifestSha256": "..." },
  "editorial": {
    "runKey": "2026-11-12#0 7 * * *",
    "line": "NEWS_EXPLAINER",
    "storyAngle": "...",
    "editorialIntent": "...",
    "promptVersion": "daily-editor-v1",
    "modelMetadata": {}
  },
  "selection": {
    "candidateId": "...",
    "sourceEvidenceIds": [],
    "datasetId": null,
    "templateId": "news-9x16"
  }
}
```

设计原则：

1. **`editorial` 与 `selection` 分开**：前者是编辑判断，后者是具体选择结果。
2. **`assets` 不动**：认领时锁定的资产信息，决策不覆盖它。
3. **`evidence` 存实际证据快照**，不是证据 ID——不能依赖未来重新查询。
4. **`schemaVersion` 现在就写。** 一旦有第二个版本的快照结构，没有版本的快照就无法回放。

### 2.4 一致性约束

- `candidateId`、`selection.candidateId`、`work.candidateId` 三者必须一致。
- 数据线回退解说线时，**认领前**完成最终候选替换；不能 work 指向新候选、证据来自旧候选。
- `slot.candidate_id` 在最终 Selection 确定后才写，不是总编辑决策完就写。
- 已有 work 时不能无条件返回：要核对已有 work 的候选与模板是否与当前 Selection 一致。

### 2.5 `Decision` 与 `Selection` 值对象

| 类型 | 字段 | 必填规则 |
|---|---|---|
| `Decision` | `line / editorialIntent / reason` | `NO_PUBLISH` 时 `reason` 必填 |
| `Selection` | `candidateId / templateId / storyAngle` | 两线都必填 |
| | `datasetId` | 仅 `DATA_BOARD` 必填 |
| | `sourceEvidenceIds` | 仅 `NEWS_EXPLAINER` 必填，且非空 |

**不为了统一让所有字段都可空。** 两条线的必填规则不同，校验分开写。

---

## 3. 发布安全链：让审稿成为发布依据

### 3.1 五个环节

```
1 Pipeline 按线选审稿输入与提示词
2 执行审稿 + 一次定向修复 + 复审
3 校验 Verdict 契约
4 持久化 HeronVideoVersion.editorialReview
5 confirmForAuto() 校验持久化审稿结果 → 才允许发布
```

### 3.2 审稿记录持久化

`HeronVideoVersion` 增加 `editorialReview` 列（JSON）：

```json
{
  "schemaVersion": "1.0",
  "result": "PASS",
  "issues": [],
  "reviewedAt": "2026-11-12T09:00:00",
  "promptVersion": "news-review-v1",
  "repairAttempts": 1
}
```

`repairAttempts` 必须记，否则区分不了一次通过和修出来的通过。

**写最终结论，不写第一次 FAIL。** 修复循环之后才落库。

**IO 失败必须 fail closed**：写不进就不允许进入可发布状态。不捕获异常，让它冒泡——版本已经在写，任务失败回滚。

### 3.3 自动确认门禁

`confirmForAuto()` 增加前置校验：

| 条件 | 处理 |
|---|---|
| `editorialReview` 为空 | 阻断 `review_required` |
| 结构不合法 / 无法读取 | 阻断 |
| `result = FAIL` | 阻断 |
| `result = PASS` 且存在 BLOCKER/MAJOR | 阻断 |
| `result = PASS` 且只有 MINOR | 放行 |

**不能只判非空**——否则写个 `{}` 就能绕过。要验证结果值、结构、严重程度。

### 3.4 Verdict 契约收紧

| 结果 | Issues 条件 | 合法性 |
|---|---|---|
| PASS | 无问题 | 合法 |
| PASS | 只有 MINOR | 合法（已有 `:88` 拦 BLOCKER/MAJOR） |
| FAIL | 至少一条 BLOCKER/MAJOR | 合法 |
| FAIL | 只有 MINOR | **非法**（当前允许，要改） |
| FAIL | 没有 issues | 非法（已有 `:84`） |

未知 severity、未知 result 一律拒绝，不默认为 MINOR 或 PASS。

### 3.5 两线审稿输入

| | 数据看板线 | 新闻解说线 |
|---|---|---|
| 依据 | 快照 `facts` 节点 | 证据 `items[]` 的 `summary` + `sourceUrl` |
| 提示词 | `editorial-review.md`（已有） | 新建，规则是「文案有没有超出原文说过的范围」 |
| 边界 | 不重新计算数据 | **不判定新闻真假** |

**解说线的能力边界要写进提示词**：`summary + sourceUrl` 只能核对摘要证据，不等于核对原文全文。提示词必须明确：

- 只能依据提供的证据片段判定
- 不得把 URL 当作已读内容
- 证据不足时标记为无法核实
- 不允许凭常识补全新闻事实

修复循环复用现有 `reviewScript()`，只按 `line` 换输入来源与提示词，循环逻辑不动。

---

## 4. 历史 12 条待审版本：不豁免

线上 12 条 `pending` 版本都没有 `editorialReview`。`auto-confirm-enabled` 打开后：

1. 全部拒绝，返回 `review_required`
2. 继续留在待审队列，不自动批准、不自动发布
3. 走现有人工审片流程逐条处理
4. 新版本必须完成 AI 审稿并持久化 PASS 才能进自动确认

**不设历史豁免。** 没有审稿记录就不能证明通过了审稿；为清理积压而制造虚假通过，比积压本身更糟。

`review_required` 要作为**可识别的阻断原因**记录，不能让扫描器每次只留重复异常日志。

---

## 5. 测试

### 线内编辑（新增）

| # | 断言 |
|---|---|
| 1 | `DATA_BOARD` 只能选数据看板线允许的模板 |
| 2 | `NEWS_EXPLAINER` 只能选解说线允许的模板 |
| 3 | 模型返回候选集合外的模板 → 合法回退 |
| 4 | 模板集合为空 → 明确 NO_FIT，不静默选默认 |
| 5 | 线内编辑选的模板 == Snapshot 冻结的模板（端到端，跨 `tick → claim → reserve`） |

### 审稿与发布（新增）

| # | 断言 |
|---|---|
| 6 | 两条生产线都执行审稿 |
| 7 | 解说线用证据片段，数据线用 facts |
| 8 | `FAIL` + MINOR 被拒绝 |
| 9 | `FAIL` + MAJOR/BLOCKER 合法 |
| 10 | `PASS` + MAJOR/BLOCKER 被拒绝 |
| 11 | 审稿记录持久化失败 → 不能自动发布 |
| 12 | 缺审稿记录的历史版本 → 不能自动发布 |
| 13 | 有效 `PASS` 通过自动确认门禁 |
| 14 | 修复后复审结果正确覆盖最终结论 |

`templateFor()` 的删除不需要迁移测试（无覆盖）。

---

## 6. 实施顺序

| 步 | 内容 | 依赖 |
|---|---|---|
| 1 | `Decision` / `Selection` 值对象 + 线内编辑器（两线） | 无 |
| 2 | 重写 `tick()`：删预选、接线内编辑、删 `templateFor()` | 1 |
| 3 | `claimForAuto(Selection)` + `ProductionSubmitBo` 携带上下文 | 1 |
| 4 | `reserve()` 写 `schemaVersion` + `editorial` + `selection`，`kind` 显式 | 3 |
| 5 | `HeronVideoVersion.editorialReview` + 审稿持久化 | 无（可与 1–4 并行） |
| 6 | 两线审稿输入 + 解说线提示词 | 5 |
| 7 | Verdict 契约收紧 + `confirmForAuto` 门禁 | 5 |
| 8 | 测试 1–14 | 全部 |

第 5 步与 1–4 互不阻塞，可以先做——它独立解决「自动发布不查审稿」这个最严重的漏洞。

---

## 7. 不在本文范围

第二阶段（场景化视频表达引擎）单独设计，本文只记下它必须先解决的前提：

- `SCENE_FIELDS` 只有 `lead / body / duration_s`，**脚本契约本身没有场景类型概念**。场景系统不是加模板，是先换契约。
- `data/audio/bgm`（6 首）与 `sfx`（3 个）未被任何代码引用，音频要进统一时间轴，不是最后混一条。
- 素材来源已定：**抓取正文自带的 `<img>`**。线上证据 `items[].summary` 里确实带图（ithome 多张），零新增外部依赖。
- 旧快照必须继续可读：场景契约升级不能让历史作品失去预览、审计、下载能力。

---

## 8. 定稿决策表

| 事项 | 决定 |
|---|---|
| 决策对象存储 | `VideoVersion.inputSnapshot` |
| 决策 ID | 不新增 |
| 独立决策表 | 不创建 |
| `heron_auto_slot` | 保留现有 5 列，用于列表与运行回放 |
| 运行标识 | `YYYY-MM-DD#slotTime` 字符串 |
| 冻结时机 | 认领写入 Snapshot 之前 |
| 认领后重新决策 | 禁止 |
| 跨运行效果分析 | 后续做分析投影，不做第二份决策事实 |
| 审稿记录 | 落 `HeronVideoVersion.editorialReview` |
| 自动发布 | Fail closed，无历史豁免 |
| 快照兼容 | `schemaVersion` 必写，旧版本可读 |
