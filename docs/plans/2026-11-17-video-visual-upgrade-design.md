# 视频视觉改造设计（模板生成器）

> 2026-11-17。目标：让新闻视频线的成片更吸引人、更好看，同时不破坏现有的确定性渲染与哈希冻结契约。

## 1. 背景

现有 13 个版式是手写的单文件 HyperFrames composition，每个约 330–880 行。视觉问题在通读后非常具体：

| 问题 | 证据 |
| - | - |
| 字体单一且没有对比 | 13 个模板全部只用 `Noto Sans SC`，字重 300/400，画面里层次靠字号硬撑 |
| 配色散乱 | 20+ 个六位 hex 散落在各模板，每个模板一套，没有体系 |
| 没有纵深 | `news-minimal` 就是纯文字列在纯色背景上，无背景装饰层、无环境动效 |

这三条正是 `docs/reference/video-craft/` 里明确反对的做法。

## 2. 现状诊断（决定架构的关键事实）

动手前先量了一遍，结论比预期更有利：

| 事实 | 数字 |
| - | - |
| 模板数 / 场景数 | 13 / 78 |
| 全部场景的标记结构 | **78 个 `<p class="lead">` + 78 个 `<p class="body">`，零例外** |
| 有图片槽的模板 | 1 个（`news-9x16`） |
| 有场景类型 CSS 变体的模板 | 2 个（`quote`、`rank`，靠 `[data-scene-type]` 属性选择器 + 运行时赋值） |
| 有图表槽的模板 | 1 个（`news-data-9x16`） |
| 当前在跑的模板 | **1 个**（只有 `news-data-9x16` 声明 `DATA_BOARD`；其余 12 个全是 `NEWS_EXPLAINER`，而该线 `article-enabled: false`） |

**13 个模板不是 13 套实现，是一个骨架穿了 13 件衣服。** 差异只有三样：整体配色、场景排版 CSS、以及三个能力开关。

另有一处声明不实：11 个模板声明 `supportedSceneTypes: [headline, text]`，但没有任何场景类型实现。`sceneTypeGate` 把 `supportedSceneTypes` 当作承诺放行，实际渲染一律退化为普通正文。**声明不实比能力不足更危险**，本次一并收窄。

## 3. 决策

| 决策 | 选择 | 理由 |
| - | - | - |
| 改造范围 | 13 个模板全铺 | 骨架统一后边际成本极低 |
| 共享方式 | 共享源 + 构建期生成 | 避免 13 份重复漂移 |
| 风格组织 | 按业务线走两套（`data-board` / `article`） | 模板管版式、风格管皮；直接解决「每期长得不一样」 |
| 组件取材 | 装 registry 的 **component**，不装 **block** | block 是固定画布（`data-chart` 声明 1920×1080），装不进 1080×1920 竖屏；component 是片段，继承宿主尺寸 |
| 实现形态 | **模板生成器**（构建期） | 模板间 95% 相同，生成器把 13 份手工维护变成 13 份配置 |
| 字体 | **必做**：换成 Noto Sans SC 可变 woff2 | 现在给单字重的 otf 声明 `font-weight: 100 900`，浏览器在合成字重——这是「看不出对比」的根因；顺带把 ~220MB 瘦身 |

**生成必须在构建期，不能在渲染期。** `LOCK.json` 的意义是「这条片子用的是哪份确定的画面」。渲染期现生成会让 `templateVersion`、灰度准入、失败回溯全部失去锚点，推翻项目原有的确定性设计。

## 4. 架构

### 4.1 唯一骨架

```
#root[data-composition-id=main][data-width=1080][data-height=1920]
        [data-duration][data-composition-variables]
  ├── #aigc-badge
  ├── header#title-card   → #kicker / #title / #rule
  ├── (section#chart-slot)                    ← capability: chart
  ├── section#scene-N × N  (data-start/duration/track-index/scene-index)
  │     ├── (figure.scene-figure)             ← capability: image
  │     ├── p.lead[data-var-text=sceneN_lead]
  │     └── p.body[data-var-text=sceneN_body]
  ├── section#closing-slot
  └── #bottom-bar → #progress / #source-line
```

三个能力开关互不依赖：

| 开关 | 产出 |
| - | - |
| `chart` | `#chart-slot` + KPI 头部 + 图表脚本 |
| `image` | 每个场景一个 `<figure>` 槽 |
| `sceneTypes` | `sceneN_type` 变量 + `[data-scene-type]` CSS 钩子 + 运行时赋值 |

### 4.2 模板配置

每个模板从 330–880 行 HTML 收敛为一份配置：

```json
{
  "key": "news-data-9x16",
  "name": "数据看板",
  "line": "DATA_BOARD",
  "style": "data-board",
  "duration": 45,
  "scenes": 6,
  "capabilities": ["chart"],
  "layout": "data-board"
}
```

### 4.3 生成器部件

| 部件 | 职责 | 复用度 |
| - | - | - |
| `_gen/skeleton.html` | 唯一骨架 | 13/13 |
| `_gen/behaviors/fill.js` | 变量注入、`data-scene-type` 赋值、变量声明块生成 | 13/13 |
| `_gen/behaviors/chart.js` | 图表绘制（**从现有模板原样搬入，不重写**） | 1 |
| `_gen/motion.js` | 时间线组装、入场、进度条、环境动效 | 13/13 |
| `_gen/components/` | registry component 片段（已适配 9:16） | 按需 |
| `_shared/tokens/<style>.css` | 设计令牌 | 2 |
| `_shared/base.css` | 画布基础 + 三条硬规则 | 13/13 |
| `layouts/<style>/<key>.css` | 每模板的排版差异 | 13 |

**最关键的防漂移点：变量声明与填充逻辑同源。** 现在 `data-composition-variables` 那一大块 JSON 和 JS 里 `declared["sceneN_lead"]` 的读取是两份手工维护的平行清单，改一处漏一处会静默失配。生成器让两者从同一份配置派生：

```js
const vars = [
  { id: 'title', type: 'string', label: '标题', maxLength: 15 },
  ...range(scenes).flatMap(i => [
    { id: `scene${i}_lead`, type: 'string', label: `第 ${i} 屏领句`, maxLength: 40 },
    { id: `scene${i}_body`, type: 'string', label: `第 ${i} 屏正文`, maxLength: 600 },
    ...(sceneTypes ? [{ id: `scene${i}_type`, type: 'string', label: `第 ${i} 屏类型` }] : []),
  ]),
];
```

### 4.4 设计令牌

按业务线两套，用 CSS 自定义属性；`motion.js` 同步一份 `TOKENS` 常量供 GSAP 读（GSAP 读不到 CSS 变量）。

| 线 | 定位 | 起手预设 |
| - | - | - |
| `data-board` | 深底、高对比、等宽数字、网格装饰 | `cobalt-grid`、`blue-professional` |
| `article` | 亮底、衬线标题、大留白 | `broadside`、`editorial-forest` |

四组令牌：色（`--bg` `--fg` `--muted` `--accent` `--rule`，每套只准一个强调色）、字（`--font-display` `--font-body` `--font-num` + 五档字号）、尺度（`--pad-frame` `--gap-block` `--radius` `--rule-width`）、动效（`--ease-out` `--ease-in` `--dur-entrance` `--dur-swap`）。

三条硬规则进 `base.css`：字号不低于 24px（竖屏 32px）、装饰透明度不低于 10%、强调色在焦点元素上满饱和。

### 4.5 动效库

只做四个动作，不做框架：

```js
enter(el, { dir, delay, stagger })   // 入场
countUp(el, { to, dur, suffix })     // 数字滚动
ambient(scope)                        // 装饰元素环境动效
swap(next)                            // 场景切换，按 --dur-swap 速度匹配
```

现有 13 个模板的入场动画各写各的，但没有一处需要第五种能力。

### 4.6 组件（来自 registry）

先小批验证再扩，**是候选不是承诺**：

| 用途 | 候选组件 | 覆盖 |
| - | - | - |
| 数字滚动 | `count-up`、`number-wheel` | 数据线 6 个 |
| 图表 | `animated-bar-chart`、`decline-chart` | data / board / compare / rank |
| 字幕 | `caption-pill-karaoke` | 全部 13 个 |
| 质感 | `grain-overlay` | 全部 13 个（背景层） |

每个装配前在 1080×1920 实渲一帧，确认不糊、不裁、不溢出。不合格退回手写，`registry.lock.json` 记下拒绝原因。

**不装 block**：block 是带自己画布尺寸的子 composition，`data-chart` 声明 1920×1080，装进竖屏模板要裁要重排。component 是片段，继承宿主尺寸与时长。

## 5. 目录结构

```
data/templates/
  _gen/                          ← 生成器输入，不进任何模板
    skeleton.html
    behaviors/fill.js
    behaviors/chart.js
    motion.js
    components/
  _shared/
    base.css
    tokens/data-board.css
    tokens/article.css
    fonts/NotoSansSC-VF.woff2
  layouts/
    data-board/news-data-9x16.css
    article/news-minimal-9x16.css
    ...                          ← 13 份
  news-data-9x16/
    template.config.json         ← 唯一手写源
    index.html                   ← 生成产物（提交）
    template.json LOCK.json assets-manifest.json fonts/ gsap.min.js narration.mp3

data/tools/
  build-templates.mjs            ← 生成器
  registry.lock.json             ← 组件来源台账
```

**模板 HTML 不再手改。** 改视觉必须改 `layouts/*.css` 或令牌后重新生成——不存在两份真相。

产物仍是单个自包含 `index.html`，渲染链路、哈希校验、`LOCK.json` 全部不动。

## 6. 构建流水线

```
node data/tools/build-templates.mjs [--template <key>] [--check]

1. 读配置   → 校验 key/目录一致、capabilities 合法、layout 存在
2. 装配骨架 → 按 scenes 展开、按 capabilities 插槽、按 sceneTypes 生成钩子
3. 注入样式 → base.css + tokens/<style>.css + layouts/<style>/<key>.css
4. 注入脚本 → fill.js + [chart.js] + motion.js + 变量声明块（与 fill.js 同源）
5. 落盘     → 写 index.html，重算 assets-manifest.json，回写 LOCK.json 两个 sha
```

幂等；`--check` 不写盘，只报告产物是否与源一致。

**先生成到临时目录，过 `hyperframes check` 才落盘**，避免把不过闸的产物写进 git。

## 7. 失败面

| 场景 | 行为 |
| - | - |
| 配置缺字段 / `key` 与目录名不一致 | 失败，报模板名 + 缺失字段 |
| `capabilities` 含未知值 | 失败，列出合法值 |
| `layouts/<style>/<key>.css` 不存在 | 失败，报期望路径 |
| `_gen/` 片段缺失 | 失败，报片段名 |
| 产物与源不一致（`--check`） | 退出码 1，列出漂移模板 |
| 生成后 `check` 报 error | 失败，不写盘 |

## 8. 迁移路径

配置 → 生成 → 比对，每模板独立验证，可增量：

1. **先做 `news-data-9x16`**——883 行、有图表，能过就说明骨架装得下。
2. 生成 `index.generated.html`，与现有 `index.html` **逐帧比对**（联络表截图对照），确认无视觉退化。
3. 确认后替换，重算 manifest/LOCK，提交。
4. 其余 12 个同理。

**回滚粒度是 1 个模板**：每个模板目录自包含，出问题单独 revert。

**图表脚本不重写**：`behaviors/chart.js` 从现有模板原样抽取搬入。生成器负责骨架、变量、样式、动效的组装，绘图逻辑是既有已验证资产。为「架构统一」重写一段在跑的绘图代码是净损失。

## 9. 质量闸

**构建期**：配置 schema → 临时目录生成 → `check` 通过才落盘 → `--check` 防手改。

**断言期**（`index.motion.json`，随 `check` 跑）：

| 断言 | 查什么 |
| - | - |
| `appearsBy` 装饰层 ≤ 2s | 背景层不是入场后才出现 |
| `keepsMoving` 静态上限 4s | 从 6s 收紧，逼出环境动效 |
| `staysInFrame` AIGC 角标 | 保留现有 |
| `appearsBy` 标题 ≤ 1.5s | 保留现有 |

**人眼期**：渲染联络表逐帧对照 [../reference/video-craft/quality-gate.md](../reference/video-craft/quality-gate.md)——两个焦点、强调色满饱和、数字与配图同时落定、竖屏字幕 600–700px。

**端到端**：真实选题跑一次 `news-data-9x16`，确认变量注入、`dataset/1` 快照取数、发布闸均正常。

**迁移基线**：迁移前给 13 个模板各渲一份基准联络表，存 `data/templates/_baseline/`。新产物与基准对比，**允许变好看，不允许变坏**——字号变小、元素丢失、对比度下降都算退化。

**字体专项**：woff2 离线加载；字重 300 与 900 渲染帧上肉眼可分；中文字形无缺字；渲染时长不因字体解析变慢。

## 10. 落地顺序

| 批 | 内容 | 完成标志 |
| - | - | - |
| 1 | 生成器 + 骨架 + 令牌，只接 `news-data-9x16` | 单模板端到端跑通，产物过全部闸 |
| 2 | 字体换可变 woff2，13 个模板统一 | 字重 300/900 在渲染帧上肉眼可分 |
| 3 | 剩余 12 个模板接入，风格按线分组，声明收窄 | 13 个产物全绿 |
| 4 | 装 4 个 component 并适配 | `registry.lock.json` 有据可查 |

## 11. 不做的事

- 渲染期生成（会破坏 `LOCK.json` 的确定性契约）
- 装 block（画布尺寸不匹配）
- 引入 Lemo、Motion Canvas、Manim 的运行时——它们的价值已作为知识进入 `docs/reference/video-craft/`
- 改 `NewsTemplatePicker`、改渲染链路、给 Java 侧加依赖
- 风格 × 版式矩阵（当前两套线风格已够）
- 重写图表绘制逻辑

## 12. 风险

| 风险 | 应对 |
| - | - |
| 生成器成为新的单点 | 骨架与组件是纯文本模板，失败可读；`--check` 防手改；单模板回滚 |
| 一次令牌改动产生 ~39 个文件 diff | 令牌改动单独提交，版式改动按模板提交，diff 可读 |
| 组件适配不达标 | 先装 4 个，实渲验证，不合格退回手写并留档 |
| 可变字体在离线渲染出问题 | 第 2 批单独验证，不通过则保留原 otf 但补齐真字重文件 |
| 迁移引入视觉退化 | 基准联络表逐帧比对 |

## 13. 验收标准

1. `news-data-9x16` 用配置生成，产物与手写版逐帧比对无退化。
2. 13 个模板全部可生成，`--check` 全绿。
3. 字重对比在渲染帧上肉眼可分。
4. 仓库体积显著下降（字体替换）。
5. 任意一个模板回滚不影响其他模板。
6. 渲染时长与改造前相比无显著上升。
