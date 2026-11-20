# P0 事实与音画核查（只读取证，未改生产代码与测试）

- 仓库：`D:\zhoupei\heron`，分支 `dev`，HEAD `a079d7fcb`
- 时间：2026-11-19
- 结论口径：**通过 / 不通过 + file:line 证据 + 置信度**；代码事实与本地实测分栏标注

---

## 1. 数据机会的原始快照与正式生产快照是否一致 —— 不通过

**代码证据**

| 环节 | 事实 | 位置 |
| --- | --- | --- |
| 机会分析取数 | `datasets.snapshot(sourceKey, 120)`，窗口 `WINDOW_POINTS = 120` | `DataOpportunityService.java:45,71,86` |
| 机会入库快照 | `snapshotForStore` 只留尾部 120 点 + 上游 `data_sha256` | `DataOpportunityService.java:266-291` |
| 生产取数 | `datasets.fetch(run, route.sourceKey(), input.path("limit").asInt(30))` —— 固定 **30** 点 | `NewsProductionPipeline.java:133` |
| 冻结输入内容 | 只冻结 `selection`（`dataSourceKey`/`digest`/`opportunityId` 等），**没有 limit、没有 sha** | `NewsProductionDomain.java:109-126`、`EditorialSelection.java:13-18` |
| 机会 sha 落库后谁读 | `heron_data_opportunity.snapshot_json` 只被研究 Agent 展示链路读，**生产链路不读、不比对** | `AgentscopeResearchAgent.java:264-270`、`HeronResearchTools.java:262`（对比：`NewsProductionPipeline` 全文无 opportunity 读取） |

**推断（高置信）**：`data_sha256` 的唯一实现对 `columns+metric+rows+shape` 归约（`DatasetFacts.java:43-64`）。机会是 120 行、生产是 30 行 → rows 不同 → **sha 必然不等**，且系统内没有任何一处比较这两个 sha。因此「片子用的数据 == 选题当时看到的数据」这一性质不成立，且不成立时无提示。

**同时通过的部分（高置信）**：单份快照的内部一致性是成立的——适配器产出 sha，闸用 `DatasetFacts.sha256` 重算比对（`DatasetContract.java:107-112`），生产与机会共用同一条 `fetched()` 校验链（`DatasetFetchService.java:80-110`）。

---

## 2. 旁白事实是否只引用最终冻结快照 —— 通过

**代码证据（高置信）**

- `contractGate(script, dataset, dataBoard)` → `DatasetScriptContract.violations(script, dataset)`（`NewsProductionPipeline.java:147,281-289`）。
- 稿内 `dataset_sha256` 必须等于本次生产快照的 sha（`DatasetScriptContract.java:51-54`）；`source_key`、`chart` 同样对齐（`:55-62`）。
- 旁白占位符必须解析到快照里的 facts 标量，且正文不得出现裸阿拉伯数字（`DatasetScriptContract.java:110-120` → `DatasetContract.java:125-140`，`resolves` 要求 `facts.` 前缀 `:316-320`）。
- 渲染侧解析用同一份 dataset 对象（`NewsProductionPipeline.java:156`、`DatasetPlaceholders.java:38-51`）；审稿依据是同一份 `dataset.facts`（`NewsProductionPipeline.java:138`）。
- 区间变化不得被表述成单日涨跌（`DatasetScriptContract.java:123-139`）。

**说明**：这里的「最终快照」指本次生产实际抓取并落成 `dataset.json` 的那一份。旁白不引用别的版本——这一点成立。但它与第 1 项的机会快照不是同一份，见上。

---

## 3. 事实引用 → DatasetVisualFocus 映射是否正确、有无静默降级 —— 通过

**代码证据（高置信）**

- 解析：`\{\{(facts\.[^{}]+)}}`（`DatasetVisualFocus.java:14,23`）；取不到值或不是标量即抛 `409 dataset_focus_invalid`（`:26-29`）——**不存在「引用解析不到却静默降级」**。
- 顺序保障：契约闸先跑（`NewsProductionPipeline.java:147`），focus 之后才算（`:892-900`）；非 `facts.` 前缀占位符先被 `DatasetContract.narrationViolations` 拦下。
- mode 判定与事实结构对齐（`DatasetVisualFocus.java:47-61`）：`change/change_pct` → change；`spread` 或同时有 `first.*`+`last.*` → comparison；`min.*/max.*` → range；`last.*/first.*` → latest；`leader.*` → leader；否则有 labels → item，否则 overview。`&&` 优先级高于 `||`，`spread || (first && last)` 语义正确。
- 实体锚点：`leader.*` → `facts.leader`；`items.N.*`/`series.N.*` → 对应元素；标签取 `label/name/location`（`:33-45`）——排序后不把源行号当画面行号。
- 模板消费一致：`data/templates/news-data-9x16/index.html:882-916` 按同一组 mode 分支取图元。
- 覆盖测试：`DatasetVisualFocusTest.java` 覆盖 latest/change/range/item/leader/comparison/overview 与「不存在的引用拒绝生成聚焦」。

---

## 4. NewsNarrationTiming 场景对齐可靠性 —— 有条件通过

**代码证据（高置信）**

- 实现是 **cue 级**，不是词级：edge-tts 的 WebVTT cue 边界 + 字符偏移插值（`NewsNarrationTiming.java:39-83`）。全仓无词级时间戳来源。
- 插值只允许在 **短 cue（≤1000ms）** 内进行，跨越长 cue 直接抛 `news_audio_alignment`（`NewsNarrationTiming.java:13,70-73`）。
- 插值出来的边界标记 `cueInterpolated=true`（`:19,70,81`），并在字幕闸的 measured 里统计 `interpolatedBoundaries`（`NewsProductionPipeline.java:470`）——不冒充词级精度。
- 静音默降级：字幕必须与口播稿归一化后**逐字相等**，否则抛错（`NewsNarrationTiming.java:58-62`）；字幕时间倒退/重叠过长/超出配音直接抛错（`:46-49`）；相邻分镜起点不递增直接抛错（`:77-79`）。
- 场景切换点 = 下一句起点（`end = 下一段 start`，`:87`）；首屏固定从 0 起（`:74`），即画面**可能早于**第一句发声（edge-tts 起始延迟量级，几十至几百 ms）。

**残余风险（中置信，属推理）**：cue 内插值按字符数比例估算，中文语速不均，短 cue 内可能有几百 ms 误差；长 cue 场景直接报错而非出片，属 fail-closed。字幕覆盖闸另允许 10% 未覆盖（`NewsProductionPipeline.java:461`）。

---

## 5. BGM 是否压过旁白；有无响度/峰值/削波检测（P0-6）—— 不通过

**代码证据**

- 固定增益：正文 `0.22`，片尾线性升到 `0.40`，`amix normalize=0`（不归一化，直接叠加）——`NewsProductionPipeline.java:696-711`。
- **全仓 heron 源码内没有任何响度/峰值/削波检测**：无 `loudnorm`/`volumedetect`/`astats`/`ebur128`/`peak` 关键字命中。`decodeGate` 只查流数、分辨率、帧率、时长（`NewsProductionPipeline.java:490-529`）；`voiceGate` 只查文件非空与时长 ≥30s（`:433-447`）。→ **P0-6 缺失**。

**本地实测（ffmpeg，本机真实产出，非模拟）**

样本：
- 成片 `data/videos/2117848667728781312/.../2ddd8e12-….mp4`（104s）
- 旁白 `data/it-render/f28041be-…/composition/narration.mp3`
- BGM `data/audio/bgm/*.mp3`（6 首）

| 测量 | 结果 |
| --- | --- |
| 旁白整段 | **−19.5 LUFS**，峰值 −2.8 dBFS |
| 成片（已混 BGM） | **−16.3 LUFS**，真实峰值 **−2.5 dBFS**（本次未削波） |
| BGM @0.22 | stereotype-news **−23.2**、city-run −25.9、city-sunshine −26.6、the-drama −27.5、ambient-bongos −29.9、horror-suspense −31.1 LUFS |
| 按 `bgmFilter` 参数复刻混音（整段） | stereotype-news **−18.5 LUFS**、city-run −19.0 LUFS（旁白 −19.5） |
| **片尾最后 1.2s** | 旁白 **−22.0**；混 stereotype-news **−20.7**（BGM 高 **1.3 dB**）；混 city-run −22.0（持平不压） |
| stereotype-news 在片尾目标增益 0.40 | **−18.1 LUFS**，高于旁白整段（−19.5） |

**结论（高置信）**：
1. 最响的那首 BGM（`stereotype-news`）在片尾**实测压过旁白 1.3 dB**；安静的 BGM 无问题。固定增益对不同响度的曲目没有自适应。
2. `normalize=0` 直接叠加，峰值越界可能发生；本次成片未削波属**运气，不是保障**——因为没有任何检测会拦住它。

---

## 回归实测

命令：`mvn -pl ruoyi-modules/ruoyi-heron -am test`

```
Tests run: 465, Failures: 1, Errors: 1, Skipped: 5
BUILD FAILURE
```

- **失败 1（Failure）**：`org.dromara.heron.agentscope.AgentRuntimeConfigTest.buildsAgentWhenEnabled:67`
- **错误 1（Error）**：`org.dromara.heron.agentscope.AgentRuntimeConfigTest.rejectsBlankSessionId:87`
- **原因（同一个）**：`AgentRuntimeConfig.heronResearchAgent` 新增依赖 `HeronResearchSnapshotMapper`（`AgentRuntimeConfig.java:100`，由 `dfa552be2` 2026-11-17 引入），而 `AgentRuntimeConfigTest` 的 `ApplicationContextRunner` 没有 mock 这个 mapper（`AgentRuntimeConfigTest.java:35-53`）→ 上下文启动失败 `NoSuchBeanDefinitionException`。**属测试夹具未跟随新增依赖更新，不是生产逻辑缺陷**；按要求未修改测试。
- **跳过 5**：均为「受控进程：参数边界与 Linux 监护」——Linux 专用用例，Windows 环境跳过。
- 其余 458 项通过，含本次核查相关的全部用例：数据看板源准入/占位符/选源/脚本作者/脚本契约/分镜 focus、数据看板实链、新闻口播对齐、新闻流水线闸报告。

> 提示：`ruoyi-modules/ruoyi-heron/target/surefire-reports/` 里还有若干 11-12/11-16 的**历史残留失败报告**（`NewsPublishAccessTest`、`NewsMetricMapperXmlTest`、`HeronMenuSeedTest`、`Production*Test` 等）。按报告文件时间戳判断，它们**不属于本次运行结果**，勿据此判断当前状态。

---

## 证据分级说明

- **代码证据**：直接读到的实现与调用链，含 file:line，可复现。
- **本地实测**：ffmpeg 对本机真实产出文件的测量值（响度 LUFS / 真实峰值 dBFS），随样本变化；采样与命令已写明，可复算。
- **推断**：由上述两者推导、未直接观测的部分（第 1 项 sha 必然不等、第 4 项插值误差量级），已单独标注置信度。
- **未验证**：未启动完整应用做端到端出片；未验证线上 MySQL/Redis 环境；未检查 `heron-ui` 前端；未验证 6 首 BGM 之外曲目的响度分布。
