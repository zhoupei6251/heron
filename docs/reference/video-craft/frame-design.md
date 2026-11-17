# 画面设计：视频不是网页

来源：`hyperframes-creative/references/house-style.md`、`video-composition.md`、`typography.md`；`palettes/*.md`。

把网页的设计习惯直接搬到视频里，是「看起来像生成的」第一大成因。这一篇是纠偏清单。

## 尺寸：网页尺寸在视频里是隐形的

| 元素 | 网页 | 视频 |
| - | - | - |
| 标题 | 32–48px | 64–120px |
| 正文 | 14–16px | 28–42px |
| 标签 | 12px | 18–24px |
| 装饰透明度 | 3–8% | 12–25% |
| 边框 | 1px | 2–4px |
| 内边距 | 16–32px | 60–140px |

两条硬线：**视频里字号低于 24px 要能说清理由**；**装饰透明度低于 10% 等于没画**。

竖屏信息流（抖音）还要再放大一档：正文 ≥32px、标题 ≥90px、数据标签 ≥24px。视频在信息流里是个小窗，观众手指还在滑。

## 密度：给画面分三层

每个场景至少要有前景/中景/背景三层，否则入场动画期间画面是空的：

- **背景层**：径向光晕、超大幽灵字（3–8% 不透明度）、细线、噪点、网格、主题装饰（数据场景用网格线）
- **中景层**：真正的信息——卡片、数字、图表、代码块
- **前景层**：分隔线、标签、数据条、角标记、等宽字体元数据

「每个场景 2–5 个装饰元素」，指的是元素个数；如果设计规范说「每场景一个环境动效」，那是指这些装饰共享**一个**呼吸/漂移循环，不是只有一个元素。

## 色彩：克制可以，扁平不行

- 每个场景至少有一个颜色把眼睛拽过去。
- 品牌强调色必须**看得见**：氛围用 15–25% 不透明度，焦点元素用满饱和。5% 的光晕在压缩后就是没有。
- **暗底禁止全屏线性渐变**——H.264 压缩会压出色带。改用径向渐变、纯色填充、或纯色 + 局部光晕。
- 中性色向主色偏一点。死灰等于没设计。
- 亮底和暗底不是一个做法：暗底靠光晕出效果；亮底要靠更重的边框（2px+）、更强的结构元素（分隔线、色块）和满饱和强调色，并且**加质感**（细噪点、纹理），否则就是「空白幻灯片」。不要因此改回暗色——把亮底做出电影感。

## 构图

- **至少两个焦点**。眼睛需要移动的路径。
- **填满画面**：主标题占画面宽度 60–80%。
- **锚定到边缘**：内容钉在左/上或右/下。居中悬浮是网页排版习惯。
- **分屏**：左数据面板、右内容；或顶部信息条 + 下方通栏。用分区布局，别用居中堆叠。
- **结构元素**：分隔线、边框面板——它们既造视觉路径，又天然好做动画（`scaleX: 0 → 1`）。

## 动效强度

30fps 下「克制」读起来就是「静止」。

- 每个装饰元素都要有环境动效：呼吸、漂移、脉冲、环绕。静止的装饰看起来是死的。
- 场景之间换动效，别重复同一个环境模式。
- 入场至少用 3 种不同的缓动和方向。全部 `y: 30, opacity: 0` 进场，等于没有编排。

## 字体

渲染器**预置 18 个字族**，写上去就离线确定性渲染、无警告、无联网风险：
Inter、Roboto、Open Sans、Lato、Nunito、Montserrat、Poppins、Outfit、Oswald、League Gothic、Archivo Black、Playfair Display、EB Garamond、Space Mono、IBM Plex Mono、JetBrains Mono、Source Code Pro、Noto Sans JP。

- **League Gothic 与 Archivo Black 只有 400 字重**，别请求 700/900。
- 别名可用：`Helvetica Neue`/`Arial` → Inter，`Futura`/`DIN Alternate`/`Arial Black` → Montserrat，`Bebas Neue` → League Gothic，`Segoe UI` → Roboto，`Courier New` → JetBrains Mono，`Garamond` → EB Garamond。
- 非内置的真实 Google 字体仍会构建时抓取并内嵌，但会触发 `font_family_without_font_face` lint 警告，且云端渲染断网时**直接报错**而不是降级。要确定性，就用内置的，或自己 `@font-face`。
- 中文：内置只有 `Noto Sans JP`。**中文必须自带 `@font-face` 或确认本机字体被内嵌**，否则云端渲染会失败。

**内置且有辨识度**（内置但不在禁用名单）的安全选择：Montserrat、Oswald、League Gothic、Archivo Black、Space Mono、IBM Plex Mono、JetBrains Mono、Source Code Pro。

### 禁用（AI 味最重的字族）

Inter、Roboto、Open Sans、Noto Sans、Arimo、Lato、Source Sans、PT Sans、Nunito、Poppins、Outfit、Sora、Playfair Display、Cormorant Garamond、Bodoni Moda、EB Garamond、Cinzel、Prata、Syne。

其中 **Syne** 是最被滥用的「想显得有辨识度」的展示字体，一眼 AI。

### 排版规则

- **不要两个无衬线配对**。跨界：衬线 + 无衬线，或无衬线 + 等宽。
- 一个场景只用**一个**有个性的字体，另一个可以退让。
- 字重对比要极端：视频里是 300 vs 900，不是 400 vs 700——在运动中看一眼就得分辨出来。
- 展示字号字距收紧到 `-0.03em ~ -0.05em`；暗底上正文用 350 而不是 400，行高 +0.05~0.1。
- **时间就是层级**：视频里「先出现的」最重，顺序取代了页面上的位置。
- 固定阅读时间：屏幕上停 3 秒 = 必须 2 秒读完。字更少、更大。

### 数据的 OpenType

```css
.stat-value, .timer, .data-column { font-variant-numeric: tabular-nums; }
.abbreviation, .unit { font-variant-caps: all-small-caps; }
```

**数字纵向排列时 `tabular-nums` 是必需的**，否则数字等宽不等，列会跳。这一条在数字滚动组件里是硬约束。

## AI 设计套路清单

这些是每个模型都会先伸手去够的默认答案。用之前先问：是我为**这个内容**选的，还是我在偷懒？

- 渐变文字（`background-clip: text`）
- 卡片/注释块左侧的强调竖条
- 暗底青色 / 紫到蓝渐变 / 霓虹强调
- 纯 `#000` 或 `#fff`（向强调色偏一点）
- 等大等距的卡片网格
- 全部居中、权重相同（没有引导视线）
- 上面那份禁用字体表

真需要就用——目标是**有意识**，不是回避。

## 配色

写 HTML 前先声明一个背景色、一个前景色、一个强调色，不要逐元素临时发明颜色。

| 类别 | 用于 | 文件 |
| - | - | - |
| Bold / Energetic | 发布、社媒、公告 | `palettes/bold-energetic.md` |
| Warm / Editorial | 叙事、纪录、案例 | `palettes/warm-editorial.md` |
| Dark / Premium | 科技、金融、奢侈品、电影感 | `palettes/dark-premium.md` |
| Clean / Corporate | 解释型、教程、演示 | `palettes/clean-corporate.md` |
| Nature / Earth | 可持续、户外、有机 | `palettes/nature-earth.md` |
| Neon / Electric | 游戏、夜生活 | `palettes/neon-electric.md` |
| Pastel / Soft | 时尚、美妆、生活方式 | `palettes/pastel-soft.md` |
| Jewel / Rich | 奢华、活动 | `palettes/jewel-rich.md` |
| Monochrome | 戏剧性、字体主导 | `palettes/monochrome.md` |

配色的文件在 `~/.claude/skills/hyperframes-creative/palettes/`。

## 设计规范是品牌，不是版式

`frame.md` / `design.md` 定义的是品牌长什么样：颜色、字体、性格、禁区。它**不定义**画面怎么构。

- **严格执行**：色值（含背景）、字族、字重关系、Do/Don't。
- **按视频适配**：字号、间距、装饰透明度、边框粗细、组件处理。网页里 `border: 1px solid #e2e3e6` + `0 2px 4px` 的阴影，在视频里根本看不见。

品牌色是神圣的，具体用法是你的。
