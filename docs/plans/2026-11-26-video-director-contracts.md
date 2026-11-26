# 视频导演系统 · 六契约与 VisualDesignProfile Schema（V2）

> 2026-11-26。上游：`docs/architecture/heron-video-director-adr.md`（架构定案）。
> 定义中间契约 schema、TTS 前置测音接缝、只读数据真值物化、`VisualDesignProfile` 资产形态、单镜落盘与 FFmpeg EDL 拼装规范。
> 复用契约（机会/洞察、`NarrativeTreatment`）不在此文重造，只写接入点。

---

## 〇、复用契约与真值物化接入点

| 契约 / 产物 | 现有真源 | V2 接入点 |
| --- | --- | --- |
| 机会/洞察与选线 | `OpportunityFacts` / `OpportunityGate` / `OpportunityScore` / `OpportunityScorer` + `EditorialLineEditor` | 剥离 `NewsTemplatePicker` 后作为导演流水线入口，产出机会上下文与 `bizLine` 喂给叙事阶段 |
| `NarrativeTreatment` | `StoryPlanContract` v2（`story-plan/1`：hookType / coreThesis / scenes[purpose, evidenceRefs, beat, emotion, rhythm, expectedVisual]） | **演进不重造**：`scenes` 对位保留；`expectedVisual.sceneType`（模板词汇）退役，改由 `VisualTreatment`/`Storyboard` 接管画面决策；叙事词汇沿 `DatasetStoryContract.NARRATIVES` 六项不变 |
| `AssetManifest`（只读真值快照） | `DatasetPlaceholders` / `MediaAsset` | 推理前由 Java 物化至 `data/render/<jobId>/assets/`（含 `dataset.json`、本地字体、媒体文件与 `manifest.json`），供单镜 HTML 读取，**禁止 LLM 在写代码时凭记忆转写数字或外链资源** |

---

## 一、VisualTreatment（视觉方向，推理阶段产物）

一次结构化 LLM 调用产出，Java 校验后锁定。文件 `data/render/<jobId>/visual-treatment.json`。

```json
{
  "schemaVersion": "visual-treatment/1",
  "profileId": "data-board",
  "profileVersion": 3,
  "visualMode": "2d",
  "visualStatement": "深底高对比数据解读，等宽数字是主角，网格与光晕给环境层",
  "lensLanguage": ["数据特写", "网格环境", "光晕聚焦"],
  "motionDiscipline": "数据驱动生长为主，单镜重音 ≤1 处，末拍必稳定",
  "shotLanguageNotes": "数字先于图形出现；对比镜头禁止同时高亮两侧",
  "profileRefusalReason": null
}
```

| 字段 | 校验 |
| --- | --- |
| `schemaVersion` | 固定 `visual-treatment/1` |
| `profileId` / `profileVersion` | 必须存在且归属本业务线；Java 锚定，Agent 不得改写 |
| `visualMode` | ∈ `2d` / `3d`，由 `VisualMode` 机器护栏判定（**V1 强制仅允许 `2d`**） |
| `visualStatement` | ≤80 字，一句话视觉主张 |
| `lensLanguage` | 数组 ≤6 项，自由词汇（不设枚举——这是创作层，不是能力查询） |
| `motionDiscipline` / `shotLanguageNotes` | 自由文本 ≤200 字 |
| `profileRefusalReason` | Agent 认为 Profile 与叙事冲突时可拒绝并说明（Java 转人工）；正常为 `null` |

---

## 二、Storyboard（镜头清单，推理阶段产物）

`data/render/<jobId>/storyboard.json`。与 `NarrativeTreatment` 的 `scenes` 一一对应（数量一致、顺序一致）。

```json
{
  "schemaVersion": "storyboard/1",
  "narrativeRef": "time_change",
  "shots": [
    {
      "shotId": "s1",
      "storyPurpose": "用增长斜率突变制造悬念",
      "visualIntent": "折线一笔画出到 2024 拐点骤停，末帧数字放大定格",
      "techniques": ["svg-line-draw", "count-up"],
      "durationBudgetSec": { "min": 4, "max": 8 },
      "inputs": ["data", "text"],
      "componentHints": ["chart-line"],
      "narrationText": "过去两年它只涨了百分之十——",
      "syncMode": "line"
    }
  ]
}
```

| 字段 | 校验 |
| --- | --- |
| `schemaVersion` | 固定 `storyboard/1` |
| `narrativeRef` | ∈ `DatasetStoryContract.NARRATIVES`（叙事词汇沿用） |
| `shots[].shotId` | `s1..sN`，全局唯一，≤5 镜起步（闸：首期限 5，验证后放开） |
| `shots[].storyPurpose` | 必填 ≤60 字；必须能追溯到 `NarrativeTreatment` 对位 `scene` 的 `purpose` |
| `shots[].visualIntent` | 必填 ≤100 字，画面主张 |
| `shots[].techniques` | ∈ 技术词汇表（`data/profiles/techniques.json`，首版与 Profile 同建：svg-line-draw / count-up / stagger-in / map-pan / text-reveal / bar-race 等起步 10~14 词，来源 `_gen/behaviors` 已实现手法 + registry 组件） |
| `shots[].durationBudgetSec` | 预估旁白预算窗：`min ≥ 3`、`max ≤ 15`，用于防稿件过长；**实际渲染时长由下一步 TTS 实测决定** |
| `shots[].inputs` | ⊆ 本片 `AssetManifest` 可用集（`data`/`text`/`image`/`screenshot`/`globe`） |
| `shots[].componentHints` | ⊆ registry 组件清单（可选，`null` 即手写） |
| `shots[].narrationText` | 本镜旁白文本（TTS 直接输入） |
| `shots[].syncMode` | `line`（词级锚点）/ `beat`（节拍）/ `none` |

---

## 二·五、AudioTiming（声音实测接缝，TTS 前置产物）

`Storyboard` 过闸后、进入 `Shot Composer` 之前，由 Java 能力层立即执行 TTS + `subs.vtt` 词级对齐，输出 `data/render/<jobId>/audio-timing.json`（及 `narration.wav`、`subs.vtt`）：

```json
{
  "schemaVersion": "audio-timing/1",
  "totalDurSec": 26.40,
  "shots": [
    {
      "shotId": "s1",
      "startSec": 0.00,
      "measuredDurSec": 6.48,
      "padTailSec": 0.30,
      "targetShotDurSec": 6.78,
      "wordAnchors": [
        { "text": "百分之十", "offsetSec": 4.12 }
      ]
    }
  ]
}
```

- **硬约束**：`Shot Composer` 必须使用 `targetShotDurSec` 作为该镜 `data-duration` 与 `__HERON_SHOT__.durationSec`，并在 `syncMode == "line"` 时把关键视觉变化锚定到 `wordAnchors` 时刻，保证音画同步与末拍 ≥30 帧稳定期一次性成立。

---

## 三、EditDecisionList（成片时序与混音编排，执行阶段产物）

`data/render/<jobId>/edl.json`。所有镜头单镜渲染通过三层 QA 后，Java 依 `AudioTiming` + `Storyboard` + `RenderReport` 生成，并经 `EdlGate` 校验后交 FFmpeg 拼接成片。

```json
{
  "schemaVersion": "edl/1",
  "fps": 30,
  "canvas": { "width": 1080, "height": 1920 },
  "tracks": [
    {
      "type": "video",
      "clips": [
        { "shotId": "s1", "shotFile": "shots/s1.mp4", "startSec": 0.0, "durSec": 6.78, "transitionIn": "cut", "transitionOut": "cut" }
      ]
    },
    {
      "type": "audio",
      "clips": [
        { "source": "tts://narration.wav", "startSec": 0.0, "durSec": 26.40, "duckDb": 0 },
        { "source": "bgm://data-board-main", "startSec": 0.0, "durSec": 26.70, "gainDb": -18 },
        { "source": "sfx://sfx-bed.wav", "startSec": 0.0, "durSec": 26.70, "gainDb": -3 }
      ]
    }
  ],
  "captions": { "vttRef": "subs.vtt", "burnIn": true, "engine": "ffmpeg-ass", "style": "profile:tokens" }
}
```

| 字段 | 校验 |
| --- | --- |
| `fps` / `canvas` | 与单镜渲染参数一致；`canvas` 必须匹配 Profile 声明的画幅 |
| `tracks[video].clips[].shotId` | 必须存在于 `Storyboard` 且 `RenderReport` 最终裁决为 `pass`，`shotFile` 存在且实测时长匹配 `durSec`（偏差 ≤1 帧） |
| `clips` 时序 | 单调不重叠；总时长 ≤ 闸值；音画对齐来自 `AudioTiming` 实测 |
| `transitionIn/Out` | ∈ `cut` / `fade` / `push`（V1 三词，由 FFmpeg 拼接执行；`black-slam` 类重转场 V1 不开） |
| `captions` | **V1 固定 `engine: "ffmpeg-ass"`**：复用 `SubtitleBurn` 读取 Profile `tokens.css` 烧录 ASS 字幕，不在 V1 混用 HTML `caption-pill-karaoke` 组件 |

---

## 四、RenderReport（质检与返工记录，执行阶段产物）

`data/render/<jobId>/render-report.json`。三层闸每镜一行，返工追加 attempt 记录不覆盖。

```json
{
  "schemaVersion": "render-report/1",
  "profileId": "data-board",
  "profileVersion": 3,
  "shots": [
    {
      "shotId": "s1",
      "attempts": [
        {
          "attempt": 1,
          "static": { "pass": true, "hyperframesLintFindings": 0, "violations": [] },
          "quantified": { "pass": false, "metrics": { "maxStillSec": 4.2, "endStableFrames": 31, "motionPresent": true, "flashCount": 0, "blockingBlack": false } },
          "vlm": { "pass": null, "frames": 0, "issues": [] },
          "verdict": "rework",
          "reworkBrief": "中段 4.2s 完全静止，折线 draw-in 后缺持续动效；末拍已稳定，无需改结尾"
        }
      ],
      "finalVerdict": "rework",
      "finalAttempt": 1
    }
  ],
  "budget": { "maxIters": 3, "costCents": 41, "costCapCents": 200 }
}
```

| 字段 | 校验 |
| --- | --- |
| `attempts[].static` | 第 ① 层静态检查结果：含 `npx hyperframes lint --json` 零 error 与 Heron 静态规则零违例 |
| `attempts[].quantified.metrics` | 第 ② 层硬编码判据：`maxStillSec ≤ 3.0`、`endStableFrames ≥ 30`、`motionPresent == true`、`flashCount ≤ 1`、`blockingBlack == false` |
| `attempts[].vlm` | 第 ③ 层仅在 ①② 通过后执行（省无效 VLM 费用）；抽 4~6 关键帧并附 `dataset.json` 真值对照；`issues` 回灌 ReAct 循环 |
| `attempts[].verdict` | `pass` / `rework` / `giveup`；`giveup` = 预算耗尽，整片进人工 |
| `reworkBrief` | `verdict == "rework"` 时必填——给镜头 Agent 的具体修改指令，禁止抽象警告 |
| `budget` | `maxIters` 与成本闸由 Java 注入，Agent 不可改 |

---

## 五、镜头生成代码落盘与 HyperFrames 契约（非契约，QA 管辖）

- **路径与形态**：`data/render/<jobId>/shots/<shotId>.html`，作为 **standalone HyperFrames composition**（顶层 `<div data-composition-id="<shotId>" data-width="1080" data-height="1920" data-duration="<targetShotDurSec>">`，**禁止包 `<template>`**）。
- **离线自包含**：仅允许引用 `../../profiles/<profileId>/tokens.css`、`../../profiles/_shared/fonts/`、`../../profiles/_shared/vendor/gsap.min.js` 及本任务 `../assets/`（如 `dataset.json`），**禁止外联网络**。
- **HyperFrames 硬性契约**（由第 ① 层 `npx hyperframes lint` 兜底）：
  - 注册唯一暂停时间轴 `window.__timelines["<shotId>"] = tl`（`paused: true`）；
  - 禁止 `repeat: -1`（必须有限次数）；
  - 禁止 CSS 初始 `transform` 与 GSAP 同属性冲突（`gsap_css_transform_conflict`）；
  - 所有声明的 `font-family` 必须带本地 `@font-face`（`font_family_without_font_face`）。
- **Heron 声明契约**：必须声明 `window.__HERON_SHOT__ = { shotId, durationSec, keepsMoving: [...], dataRefs: [...] }`；必须 `@import` Profile `tokens.css`，禁止硬编码色值/字号/间距。
- **单镜执行链**：第 ① 层静态检查（含 `hyperframes lint`） → `npx hyperframes render shots/<shotId>.html --quality looks --output shots/<shotId>.mp4` → 第 ② 层 `shot-motion-check.mjs` + `VideoSignalCheck` → 第 ③ 层抽 4~6 帧送 `VlmFrameReviewer` → 写 `RenderReport`。

---

## 六、VisualDesignProfile 资产形态

位置 `data/profiles/<profileId>/`，Java 加载、版本锁定、按业务线归属校验；公共字体与第三方脚本放在 `data/profiles/_shared/`。

```
data/profiles/
├── _shared/
│   ├── fonts/NotoSansSC-Regular.otf
│   └── vendor/gsap.min.js
└── data-board/
    ├── profile.json          # 元数据：id、version、业务线归属、画幅声明、字幕引用
    ├── tokens.css            # 真源：现 _shared/tokens/data-board.css 迁入，去模板专属选择器
    ├── caption.css           # 字幕风格声明（与 SubtitleBurn ASS 映射）
    └── skeleton-rules.json   # 骨架规则：安全区 px、字幕带 y 区间、keepsMoving 最低项——静态闸、量化闸、VLM 三处共用
```

```json
{
  "profileId": "data-board",
  "version": 3,
  "bizLine": "data_board",
  "canvas": { "width": 1080, "height": 1920 },
  "fonts": ["Noto Sans SC"],
  "motionDiscipline": { "maxStillSec": 3.0, "endStableFrames": 30, "maxFlashPerShot": 1 },
  "caption": "caption.css"
}
```

要点：
- **令牌语义升级**：现 `tokens/*.css` 的 `--bg`/`--fg`/`--accent`/`--fs-*` 等变量全部保留（已验证），删除的只有引用模板场景类的选择器部分；迁移时 diff 审查。
- **静态闸三源合一**：`skeleton-rules.json` 是静态检查、帧级量化、VLM prompt 三处共用的判据真源。
- **版本锁定**：改令牌 = bump `version` + `RenderReport` 里的 `profileVersion` 联动，锁定片不受后续修改影响。

---

## 七、Java 侧组件边界（对应 ADR 三层）

| 组件 | 职责 | 形态 |
| --- | --- | --- |
| `DirectorPipeline` | 取代 `NewsProductionPipeline`：五段十步编排、TTS 前置测音、FFmpeg EDL 合成、预算控制 | 沿现管线骨架重构 |
| `EditorialLineEditor` | 四线路由、候选题与「没故事就不做」门禁（剥离 `NewsTemplatePicker`） | **B 类保留重构** |
| `VisualTreatmentGate` / `StoryboardGate` / `AudioTimingGate` / `EdlGate` / `RenderReportGate` | 契约校验器（纯函数 `violations()` 风格，沿 `StoryPlanContract` 模式） | 新建（`StoryboardGate` 取代旧 `StoryboardContract`） |
| `ProfileRegistry` | Profile 加载、版本锁定、业务线校验 | 新建（取代 `TemplateCatalogService`） |
| `ShotStaticCheck` + `ShotMotionCheck` | 第 ① 层静态检查（含 `hyperframes lint`）+ 第 ② 层 `shot-motion-check.mjs` / `VideoSignalCheck` 封装 | 新建 |
| `ShotRenderTools` | AgentScope Toolkit：读 `AssetManifest` / 写 `<shotId>.html` / 静检+单镜渲染+量化 / 读 `RenderReport` | 新建（白名单，沿 `HeronResearchTools` 模式） |
| `ShotDirectorAgentFactory` | per-session ReAct / Harness Agent 构造（factory-per-session、Hook 留痕、`maxIters`） | 新建（沿 `ResearchAgentFactory` 模式） |
| `VlmFrameReviewer` | 抽帧 + 对照 `dataset.json` 与 `skeleton-rules.json` 的 VLM 审美裁决 | 新建 |
| `SubtitleBurn` / `NewsCover` / `BgmCatalog` / `SoundCue` / `SoundCueMixer` | 字幕烧录、封面抽帧、BGM 选曲、SFX 规划混音 | **B 类保留去模板耦合** |
