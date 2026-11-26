# VisualRecipe 配方库字段设计初稿（V1）

> 2026-11-26。上游：`docs/architecture/heron-visual-agent-adr.md`（架构定案）。
> 词汇真源盘点 + 配方 JSON 字段设计 + 首批建工路径。参照 video-talkcraft 配方卡格式
> （`D:\zhoupei\repo-refs\video-talkcraft`，124 卡，格式已迭代三轮）。

## 一、heron 已有词汇（真源盘点，别重复发明）

| 词汇层 | 真源 | 现状 |
| --- | --- | --- |
| 场景类型 | `data/templates/*/template.config.json` → `supportedSceneTypes` | 6 个：headline / text / chart / number / compare / image；各模板持子集 |
| 片级叙事 | `DatasetStoryContract.NARRATIVES` | 6 个：counter_intuitive / numeric_contrast / trend_reversal / rank_competition / time_change / problem_evidence_reveal，**已带叙事→张力类型映射** |
| 风格→叙事归属 | `data/templates/_shared/styles.json` | 4 种 style 各自声明支持的 narratives |
| 能力 | `capabilities` | chart / globe / image |
| 运行时动效断言 | `index.motion.json` | appearsBy / staysInFrame / keepsMoving（闸门用，非创作词汇） |
| 片级令牌 | `template.config.json` → `defaults.accent` 等 | 模板级，锚定后锁定 |

**缺的就是镜头级配方层**：场景类型只说「能画 chart」，没说「什么内容的 chart、什么构图、什么动效、什么语感、有什么坑」。这就是 VisualRecipe 要补的。

## 二、配方 JSON 字段（V1）

位置 `data/recipes/<recipeId>.json`，与模板实现**同库同提交**（ADR §三）。

```json
{
  "recipeId": "data-chart-trend-draw",
  "version": 1,
  "sceneType": "chart",
  "composition": "fullscreen",
  "motion": "draw-in",
  "hint": "一条连续区间的整体走势：图形一笔画出，结论收在末拍，读「形状」不读「逐项」",
  "narratives": ["time_change", "trend_reversal"],
  "inputs": ["data", "text"],
  "constraints": { "minDuration": 3, "maxDuration": 12 },
  "rendererBindings": [
    { "templateId": "news-data-9x16", "sceneId": "chart" },
    { "templateId": "news-data-16x9", "sceneId": "chart" }
  ],
  "pitfalls": ["最高柱顶不留余量会压结论标签", "逐项对比内容误用本配方（应换 rank 类配方）"]
}
```

字段逐个说明：

| 字段 | 必填 | 校验 | 来源 |
| --- | --- | --- | --- |
| `recipeId` | ✓ | kebab-case，全局唯一 | 建工 |
| `version` | ✓ | 整数递增 | 建工 |
| `sceneType` | ✓ | **必须 ∈ 锚定模板的 supportedSceneTypes**（resolve 第一道检查） | 现有词汇 |
| `composition` | ✓ | ∈ `data/recipes/vocab.json` 的 compositions | 建工 |
| `motion` | ✓ | ∈ vocab.json 的 motions | 建工 |
| `hint` | ✓ | ≤60 字，一句话「什么内容、什么语感」——agent 选卡看的就是它 | 建工 |
| `narratives` | ✓ | 子集 of `DatasetStoryContract.NARRATIVES`——**配方与 heron 已有叙事词汇的桥**，Java 按本片叙事过滤 | 现有词汇 |
| `inputs` | ✓ | `data` / `text` / `image` / `screenshot` / `globe`——对应 MediaAsset 层素材形态（talkcraft 的输入类型索引是选卡第一道过滤，heron 对应物就是素材可用性） | 现有词汇 |
| `constraints` | ✓ | 秒；渲染时长闸读它 | 建工 |
| `rendererBindings` | ✓ | templateId 必须存在且模板内确有该 sceneType 的场景实现——**与模板同笔提交保证** | 建工 |
| `pitfalls` | ✗ | 已知坑；V1 主要给建工和 Playbook 素材，agent 侧由 Java 拼进提示 | 建工 |

不设的字段（YAGNI）：
- **`params`（参数表）**：talkcraft 的调节手感表很精彩，但 heron V1 的场景实现是模板 HTML，参数烤在代码里，配方层没有可调参数。等新场景把参数暴露出来（数据驱动柱数/错峰之类）再加。
- **`owns`/`notOwns`（动效范围）**：同上，是「写新卡」时的纪律，先进 Playbook 不进 schema。
- **句级语义**（talkcraft 的钩子/论点/例证/数据 22 词）：那是句子级节奏词汇，heron 片级叙事已覆盖选卡需要；等做镜头内节奏编排再议。

## 三、vocab.json（构图与动效词汇表）

`data/recipes/vocab.json`，配方引用的词汇在这里定义，同库同提交：

```json
{
  "compositions": {
    "fullscreen": { "label": "全屏主体", "def": "画面一个主体占满，无分栏" },
    "split-left-right": { "label": "左右分栏", "def": "A/B 并置或文图分栏" },
    "stack": { "label": "纵向堆叠", "def": "列表/排行/步骤自上而下" },
    "overlay-lower": { "label": "下带覆盖", "def": "主体在上，信息带压下三分之一（字幕带之上）" }
  },
  "motions": {
    "draw-in": { "label": "一笔画出", "def": "图形/线条沿数据方向生长，读作过程" },
    "rise-in": { "label": "自底升起", "def": "元素从基线长出（scaleY，origin bottom）" },
    "count-up": { "label": "数字滚动", "def": "数值从 0 或前值滚到目标值" },
    "stagger-in": { "label": "错峰入场", "def": "同类元素按序入场，整组 ≤0.4s" },
    "swap-highlight": { "label": "换色指认", "def": "指认某一项：换色/高亮，不做位移" },
    "static-hold": { "label": "静置", "def": "无动效，靠画面自身成立（数据结论定格用）" }
  }
}
```

V1 词量刻意小（构图 4、动效 6）：**词汇是给 7 套模板现有场景实现贴标签起步的，不是先设计理想词表再让模板追**。场景实现里已经存在的动效手法（基准盘点过：数字滚动、折线生长、排行换位、雷达展开等）映射进词表，覆盖不了的手法再补词。

## 四、首批建工路径（V1 落地工作清单）

1. **盘点建卡**：8 套模板 × 各自 supportedSceneTypes，每格一张配方卡（约 30 张）。sceneType/rendererBindings/constraints 是机械活；`hint`/`narratives`/`pitfalls` 需要看渲染样例逐格判定——这是「人建工」的本体，也是配方质量的根。
2. **vocab.json 定版**：按盘点结果收词，宁可先窄后宽。
3. **Java 侧**：`RecipeRegistry` 加载 + `resolve(recipeId, compositionId, compositionVersion)` 校验链（sceneType ∈ supportedSceneTypes → bindings 含锚定模板 → inputs ⊆ 本片可用素材 → constraints 过时长闸）；JEV 留痕。
4. **StoryPlanContract 演进**：锚点提议字段 + 逐镜头 recipeId 字段。

## 五、能抄 / 不能抄（读源码结论，回填 video_study.md 用）

### video-talkcraft（124 卡）
- **能抄**：卡 frontmatter 字段集（输入/语义/位置/props/优先级）；「已知坑」一节——每条坑都是具体翻车形态而非抽象警告；「动效范围」（属于/不属于本卡 + 迁移接口）——这是防配方漂移的关键结构；输入类型索引作为**选卡第一道过滤**（本设计 `inputs` 字段的直接来源）；两条通用规律：入场→hold→出场三段式（入场 0.2~0.8s 固定、出场 0.15~0.5s 固定、hold 可伸缩）、**同屏重音同一时刻只能有一个**；转场选型口诀（递进 push-through / 翻页 overexpose-flip / 并列 whip-pan / 最大反转 black-slam 全片限一次）。
- **不能抄**：108 卡本身——heron 场景长在模板里不是独立组件，搬卡没有实现载体；人物互动类整类（heron 无真人出镜）；demo HTML + tsx 双形态（heron 单一 HTML 实现）；卡内写死的 GSAP 代码（heron 动效在模板实现里）。

### anything2explainer
- **能抄**：八条硬性原则里的视觉纪律——「闪烁只给重点（每镜头 ≤1 处）」「持续动作 + 落位停留：入场即停和落位即切都是缺陷」「最长静止 ≤3s、末拍稳定期 ≥30 帧（有 `motion_check.py` 量化）」「镜头按画面单元分不按句分」「字幕带与进度条带禁放内容、入场轨迹不得穿过」。这批直接进 heron Playbook，其中量化判据可接进现有质量闸。
- **不能抄**：黑底视觉体系本体（heron 模板有自己的设计语言）；四个确认点的人工介入（heron 全自动，人只在闸口审核）；Remotion 模板工程结构（heron 走 hyperframes HTML）。

### bar-chart-growth 单卡（格式范本）
- 调节手感表（每参数给「大了怎样、小了怎样」）与「命门 N 条」写法，是建工时写 `pitfalls` 和未来 `params` 的格式范本。如：错峰 >0.1s 读作「七个动效」而非一次连续动作；数据卡用 `back.out` 回弹量 >2 读作漫画弹跳。
