# ADR · Heron 视频导演系统（V2）

> 2026-11-26 定案并整理架构，**取代同日早间的《视频线 Agent 自主创作架构（V1）》**（否决链见 §九）。
> 决策过程为对抗式评审（grilling），八轮裁决 + Q7 精化 + harness 边界确认 + 工程细节对齐，共识逐条落在本 ADR。

## 一、定位：从「模板填充」到「AI 视频导演」

旧架构（模板中心）是**先有画面，再把内容塞进去**：选模板 → 模板决定场景结构 → 内容填格。
新架构是**先理解内容，再决定画面**：Agent 理解素材与观点 → 设计叙事与视觉表达 → 逐镜头现场创作画面代码 → 确定性渲染执行 → 三层质检与镜头级返工。

Heron 是全自动产线（1 条/天，无本地独显），不是人工交互式剪辑工具。因此导演系统必须同时满足：
**创作自由度给 Agent，流程控制、数据真值、审计、预算、发布闸全部留在 Java。**

---

## 二、总架构：三层与端到端主链路

### 2.1 三层职责划分

| 层 | 谁负责 | 内容 |
| --- | --- | --- |
| 创作层 Creative | LLM / AgentScope Agent | 叙事结构、视觉方向、分镜构思、单镜 HTML/GSAP 代码创作、VLM 审美裁决与返工修正 |
| 能力层 Capability | Java Tools + Node.js (≥22) | 数据快照物化、TTS 与词级对齐、单镜渲染（hyperframes）、`hyperframes lint`、帧级量化检测、BGM/SFX 规划与混音、字幕烧录、封面抽帧、registry 组件库 |
| 执行层 Execution | Java + MySQL + hyperframes + FFmpeg | 选题定线编排、契约校验闸、Profile 版本锚定、任务状态机、JEV/ToolCallLedger 留痕、预算闸、成片拼装、草稿池发布闸 |

### 2.2 端到端五段十步流水线（TTS 前置 + 单镜独立渲染）

```
[1. 入口与真值冻结]
  OpportunityFacts/Gate/Score + EditorialLineEditor（去模板化路由）+ DatasetStoryPlanner
  ──▶ 冻结素材快照 + AssetManifest（assets/dataset.json + 本地字体 + 媒体）

[2. 纯推理阶段 · 单次结构化 LLM 调用，不套 ReAct]
  ├── NarrativeTreatment （沿 StoryPlanContract v2：讲什么、情绪/节奏、证据出处）
  ├── VisualTreatment    （锚定 VisualDesignProfile + VisualMode 2D/3D 闸：怎么看）
  └── Storyboard         （≤5 镜起步：storyPurpose、visualIntent、techniques、narrationText、syncMode）

[3. 声音测量阶段 · 在画图之前！]
  TTS 合成 + 词级时间戳对齐（subs.vtt / audio-timing.json）
  ──▶ 锁定每镜精确实测时长（measuredDurSec）与词级锚点（wordAnchors）

[4. 创作执行阶段 · 逐镜 per-session ReAct 循环 + 三层 QA 返工]
  ShotDirectorAgentFactory（按 shot/attempt 建会话，跑完即弃）
  ──▶ 产出 standalone HTML：data/render/<jobId>/shots/<shotId>.html
  ──▶ hyperframes 单镜渲染 ──▶ shots/<shotId>.mp4
  ──▶ 三层 QA 闸：① 静态检查（含 hyperframes lint）→ ② 程序化帧级量化 → ③ VLM 抽帧审美
      └── 不通过（verdict=rework）：带 reworkBrief 续跑该镜 sessionId（改码→重渲→复检）
      └── 超 maxIters / 成本闸（verdict=giveup）：整片转人工

[5. 成片合成与发布闸 · Java + FFmpeg 确定性收口]
  EditDecisionList (edl.json)
  ──▶ FFmpeg 按 EDL 拼接各镜 mp4（cut/fade/push）
  ──▶ BgmCatalog 选曲 + SoundCue/SoundCueMixer 音效床（−3/−6 dB 避让）
  ──▶ SubtitleBurn 烧录 ASS 字幕 + NewsCover 首屏运动封面 + AIGC 元数据
  ──▶ 草稿池人工验收（业务规格 §3.10）
```

> **为什么 TTS 必须前置到逐镜创作之前**：如果先按分镜估算时长渲染单镜并跑「最长静止 ≤3s / 末拍稳定 ≥30 帧」量化闸，第 5 步混音时 TTS 实测时长只要偏差 1 秒就会破坏画面节奏或重新触发静止超限，还会让 `syncMode: "line"` 词级对齐落空。先测量声音，再按精确时长创作画面，单镜一次过闸且返工不重跑 TTS。

---

## 三、Agent 拓扑与 Harness 边界

**Agent 拓扑：两形态分阶段。**

1. **纯推理阶段**（`NarrativeTreatment`、`VisualTreatment`、`Storyboard`）：单次结构化 LLM 调用，Java 校验产物后放行。不套 ReAct 循环——一次性 JSON 输出不需要工具机，token 与故障面白付。
2. **创作执行阶段**（镜头创作 + 返工）：AgentScope `ReActAgent` / `HarnessAgent` 循环。Agent 持白名单工具（读素材清单 / 写 `<shotId>.html` / 跑静态检查与单镜渲染 / 跑量化检查 / 读 QA 报告），`maxIters` + 成本闸即返工预算。「Visual Director」「Shot Composer」是阶段角色名，**不是常驻进程**；每片每镜按 `sessionId` 建会话（factory-per-session，沿 `ResearchAgentFactory` 模式），跑完即弃，仅同镜返工续跑同一 `sessionId`。

**harness 边界（一步到位，不开全家桶）：**
直接使用 `agentscope-harness`（pom 已依赖，调研线已验证）。
- **用**：`HarnessAgent` 会话、Hook/JEV 留痕、`Toolkit` 白名单注册、Permission 三态闸。
- **不开**：动态 skill 加载、默认工作区技能、Memory 长期记忆、Sandbox——持久化归六契约 + MySQL + 产物落盘；2.1 Durable 未发布，不碰。
- 纯推理阶段将来需要工具时（如查历史表现），升为 harness agent 只是加一个 factory。

**禁止**：多 Agent 自由聊天共同决定整片完成时机——流程难以调试、重试、审计。

---

## 四、中间产物：六契约 + 物化资产边界

| 契约 | 阶段 | 来源 | 回答的问题 |
| --- | --- | --- | --- |
| 机会/洞察 + 选线 | 入口 | **复用** `OpportunityFacts/Gate/Score` + `EditorialLineEditor`（剥离选模板） | 讲什么、走哪条内容线、为什么值得讲 |
| `NarrativeTreatment` | 推理 | **复用** `StoryPlanContract` v2 演进 | 故事结构、观点、钩子、节奏、事实出处 |
| `VisualTreatment` | 推理 | 新建 | 全片视觉方向、镜头语言、Profile 引用、`VisualMode`（V1 固定 `2d`） |
| `Storyboard` | 推理 | 新建（取代旧 `StoryboardContract`） | 每镜叙事目的、视觉手法、`techniques`、旁白文本与 `syncMode`（≤5 镜起步） |
| `EditDecisionList` | 执行 | 新建 | 镜头时序、实测旁白锚点、BGM/SFX 轨道、转场与字幕引用 |
| `RenderReport` | 执行 | 新建 | 三层 QA 结果、返工记录、成本核算、最终裁决 |

**两条硬规则：**
1. **叙事与视觉分离**：`NarrativeTreatment` 回答「讲什么」，`VisualTreatment` 回答「怎么让观众看到」。换视觉方向不重写叙事，反之亦然。
2. **数据真值不靠提示词硬背**：`data_board` 等需要精确数字的镜头，由 Java 在 `data/render/<jobId>/assets/dataset.json`（及 `AssetManifest`）物化只读快照；生成代码从数据载体读取数值，禁止在提示词转写时编造或篡改数字。

镜头生成代码（HTML/GSAP）**不走结构化契约**：直接落盘 `data/render/<jobId>/shots/<shotId>.html`（standalone HyperFrames composition），由三层 QA 管它——校验生成代码 AST 没有意义，校验 `lint` + 渲染结果 + 关键帧才有。

---

## 五、一致性：VisualDesignProfile 锚定

模板没了，片间一致性由 Profile 保证：

- 每条视觉线挂一个 `VisualDesignProfile`：色板、字体、间距、字号阶梯、动效纪律、字幕样式、骨架规则（字幕带/安全区/keepsMoving）。
- **真源**：`data/templates/_shared/tokens/*.css`（5 份线级令牌，已验证）迁入 `data/profiles/<profileId>/tokens.css`，通用字体（`NotoSansSC-Regular.otf`）与前端库（`gsap.min.js`）迁入 `data/profiles/_shared/`。
- **Java 锚定**：开工前按业务线锁定 Profile 版本（沿旧锚点机制的模式）；校验归属业务线。
- **生成代码约束**：必须 `@import` Profile 令牌与 `@font-face` 本地字体，禁止硬编码色值/字号/间距；骨架规则升级为对生成代码的静态检查 + 帧级 QA 判据。
- `registry` 组件、`_gen/behaviors` 动效实现进入镜头创作时必须适配 Profile 令牌，不许自带皮肤。

---

## 六、质量闸：三层 + 镜头级返工

| 层 | 时机 | 手段 | 成本 |
| --- | --- | --- | --- |
| ① 静态检查 | 渲染前 | `npx hyperframes lint`（CSS/GSAP 冲突、未本地化字体、`repeat: -1` 等确定性违规）+ Heron 静态闸（令牌 import、禁硬编码、字幕带/安全区、`__HERON_SHOT__` 声明、真值数据引用） | 0（纯代码/CLI） |
| ② 程序化帧级量化 | 单镜渲染后 | `data/tools/shot-motion-check.mjs` + `VideoSignalCheck`：最长静止 ≤3s、末拍稳定 ≥30 帧、动效存在性、闪烁 ≤1 处、无致命黑场（anything2explainer 判据本地原生实现） | 0 LLM（FFmpeg 抽帧/信号检测） |
| ③ VLM 抽帧审美 | 量化通过后 | 每镜 4~6 关键帧 + 对应 `dataset.json` 事实对照：文字溢出、元素重叠、构图层次、数据标注与结论一致性 | 每镜 1 次 VLM 调用 |

**返工只返问题镜头**：QA 报告（含具体 `reworkBrief`）回灌该镜的 ReAct 循环（读报告 → 改码 → 重渲 → 复检），重试预算 = `maxIters` + 成本闸，超限进人工。整片失败归因必须能答「哪条闸 + 哪镜 + 哪段输入」。

---

## 七、知识送达、弹药库与演进闭环

不引 AgentScope Skills（结论沿 V1 ADR §四，触发条件不变）。知识与弹药三种形态：

1. **`creative-knowledge/` 目录树**（Java 按阶段过滤注入 prompt）：
   - `directing/`（叙事/视觉/声音）、`styles/`、`techniques/`（数据可视化/动态文字/运镜/转场）、`quality/`（三类审查清单）。
   - 素材源：`data/reference-cards/`（Apache-2.0 `video-shotcraft` 125 卡 + MIT `huashu-art-motion` 57 风格卡 & 9 解说语法）+ `diagram-design`（MIT，信息图审美与「反 Mermaid/反 AI 渐变」禁忌清单）+ anything2explainer 视觉纪律（方法层已合法消化）。
2. **`hyperframes-registry` 31 件**（hyperframes 官方，Apache-2.0）：优先采纳 `components/`（继承宿主竖屏画布）作为镜头组件弹药库。
3. **`_gen/behaviors` 动效实现**（chart/race/compare/globe/focus/headline/mood 等）：已过闸的动效手法，镜头代码生成的参照与直接弹药。

**后续演进闭环（切片验证通过后接通）：**
- **参考片逆向沉淀**：将现有 `TrendVideoDecomposer` / `TrendLearningAgent`（rea-video 思路）接入，从同领域高表现参考片拆解镜头节奏与转场，经人工审核入 `creative-knowledge/`。
- **发布表现反哺**：打通 `MetricInsightAnalyzer`（播放/3 秒留存/完播/分享）回流到 `NarrativeTreatment` 与 `creative-knowledge/` 选型建议，不自动改代码或 Profile。

**许可红线**：OpenMontage / OpenFilm / Hypit / Stickman 视为**架构参考**（读思想、不抄文件），借鉴其任何文件前必须过 license 审计（沿 talkcraft 教训）。talkcraft / anything2explainer 实现层维持禁收。

---

## 八、V1 能力与技术边界

- **画面技术**：**GSAP + 原生 SVG/Canvas/CSS**。保留 `VisualMode`（仅 `shape=geo` 解锁 3D）作机器闸门，V1 强约束 2D；Three.js 等确有三维叙事需求再加（每加一项 = QA 判据、静态检查、能力声明三处跟进）。
- **渲染与合成**：hyperframes（0.9.6+，Node ≥ 22）逐镜确定性渲染为 `shots/<shotId>.mp4`，FFmpeg 按 `edl.json` 拼接与混音（续用 `BgmCatalog`、`SoundCue`/`SoundCueMixer`、`SubtitleBurn`、`NewsCover`）。V1 字幕统一由 `SubtitleBurn` ASS 烧录，暂不混用 HTML 字幕组件。
- **发布**：业务规格 §3.10 不动——产物先进草稿池人工验收，自动模式灰度另议。
- **配套文档**：
  - 契约与 Profile schema：`docs/plans/2026-11-26-video-director-contracts.md`
  - 拆除与改造清单：`docs/plans/2026-11-26-video-director-demolition.md`
  - 垂直切片任务书：`docs/plans/2026-11-26-video-director-slice.md`

---

## 九、否决链

| 被否决的方案 | 否决理由 |
| --- | --- |
| V1 ADR《视频线 Agent 自主创作架构》的「视觉=配方词汇表选卡 + 渲染层不组件化 + 片级锚定模板」 | 模板中心 = 先有画面再塞内容，画面上限被模板锁死；grilling Q1 裁决推翻（2026-11-26） |
| VisualRecipe 配方卡字段设计与首批 30 张建卡 | 前提（模板场景实现存在）随模板拆除消失，文档归档为已否决 |
| 双轨并行 / 模板兜底 | 开发阶段，Q2 裁决：模板产线整体删除，不留保底 |
| AgentScope 2.1 Durable、Service 部署、动态 Skills、Memory/Sandbox | 未发布 / 无必要 / ADR V1 §四结论维持 |
| 多 Agent 常驻会话自由聊天 | 调试、重试、审计不可控（两形态分阶段取代） |
| 全阶段 ReAct | 纯推理阶段无工具需求，白付 token 与故障面（Q7 精化裁决） |
| 先估算时长渲单镜、最后再跑 TTS | TTS 实测时长与估算偏差会导致静止超限/硬切、废掉词级对齐（TTS 前置取代） |
| talkcraft / anything2explainer 实现层文件 | PolyForm NC，商业场景禁收（2026-11-26 许可审计，维持） |

---

## 十、一句话契约

**叙事与视觉由 Agent 分阶段自主创作（推理单次调用、TTS 先测音再创作、镜头创作 ReAct 迭代），真值由快照物化，一致性由 Profile 令牌锚定，质量由三层闸 + 镜头级返工把守，流程控制与审计归 Java，渲染由 hyperframes 逐镜确定性执行 + FFmpeg 按 EDL 合成，知识由 creative-knowledge 注入，经验由参考片拆解与表现数据反哺。**
