# 视频表现力调研（蒸馏版）

> 2026-11-17 调研。前提：Heron 主线是数据看板短视频，已用 HyperFrames + GSAP + FFmpeg。
> 目的不是再找一套一键生成系统，而是补视觉表现、叙事节奏、动态图表、素材质感、字幕表现。
> Star 数为查询时近似值，会变动。

## 一句话结论

保留现有 HyperFrames 链路，只学 4 个仓库；WhisperX 和 ComfyUI 已排除，不加技术栈。

## P0

### heygen-com/hyperframes（~85.3k · Apache-2.0）

已经在用，但没用满生态。官方约 450 个可编辑组件，覆盖转场、字幕、动效标题、数据图表、地图、3D、镜头运动，另有给 Codex 用的 Agent Skills。

学：`/hyperframes-registry`、`/media-use`、Showcase、Frame.md —— 这四个直接对应 Heron 的图表、字幕、转场、统一视觉风格。

坑：`/style-sampling` 属于 HyperFrames Pro，不在开源范围。

### lemomo-ai/lemo-opuscar（~17.3k）

99 种影片风格，每种配完整示例视频和风格说明。不是换滤镜，是用代码绘制整支片子，涵盖构图、动画、镜头、音乐、叙事。

先看这 5 类：Broadcast News Package（新闻包装）、Visual Investigation（调查报道）、Constructivist Montage（强节奏拼贴）、Data & Maps（数据与地图叙事）、Graphic & Type（图形与动态字体）。

对 Heron 的价值：同一条数据怎么做出更有表现力的视觉叙事。

坑：代码 MIT，但风格说明和提示词是 CC BY-NC 4.0，另有"仅限制作影片"的附加条款。未经单独授权不能把文档打包进商业产品。

## P1

### iart-ai/motion-skills（~1.8k · MIT）

56 个 Skills，专门教 AI 编程 Agent 做专业动效，覆盖 TikTok、Reels、数据动画、解释型视频、动态字体、3D、WebGL。

优先看三个子仓库：`tiktok-video-skills`（短视频节奏与竖屏）、`data-animation-skills`（动态图表）、`explainer-video-skills`（脚本、分镜、动画）。

最值得抄的是流程：渲染画面 → 截图 → 检查 → 修改。不是出完 MP4 就算结束。

## P2

### motion-canvas/motion-canvas（~20k · MIT · TypeScript）

信息型矢量动画框架 + 实时预览编辑器，动画可与旁白同步。

学：图表如何有节奏地出现、数字如何动态变化、多元素如何配合旁白、用运动表达抽象概念。

定位：不替换 HyperFrames。作动画技术参考，必要时独立渲染复杂场景，再交给 HyperFrames 合成。

## 参考（不引入）

- **ManimCommunity/manim**（~42.6k · MIT）：Python，擅长用动画解释数学、统计、复杂概念。以后做经济数据、金融趋势、增长模型时研究它怎么把抽象概念变成可视过程。不替换现有 TS 链路。
- **ComfyUI + Wan2.2**：节点式图像/视频生成，能给数据看板加 B-roll、动态背景、静态图动起来。需独立 GPU，不能放生产 CPU 服务器。**已排除。**
- **WhisperX**：词级时间戳对齐 + 说话人分离。能做逐词高亮、关键词同步放大、字幕与配音精准同步（现有字幕只到 cue 级）。含 `zh` 对齐配置，但要用 Edge TTS 中文音频实测。**已排除。**

## 架构建议

不叠加引擎，维持四段：

- 内容与选题：Heron + 数据机会分析 + 总编辑 + 叙事策划
- 视觉创作：HyperFrames + GSAP + 动效组件库 + 原创风格（表现手法从 Lemo 和 Motion Skills 学）
- 素材与声音：TTS（B-roll 生成已排除）
- 质检与反馈：逐帧画面检查 + 事实校验 + 技术检测 + 播放/完播数据回流

## 下一步三件事

1. **建视觉风格体系**：先打磨 3 套原创可复用风格——专业新闻包装、数据调查纪录片、动态数据解释型。不是每条随机换模板。
2. **建可复用动效组件库**：标题、数字滚动、数据对比、地图、时间轴、转场、人物介绍。
3. **建视频质量评估机制**：查前三秒吸引力、叙事节奏、画面丰富度、信息密度、结尾引导，而不是只查"能不能正常生成"。

## 免责

这些只改善观感、表现力和制作质量，不保证播放量。决定留存的是前三秒钩子、故事冲突、信息密度、节奏，以及发布后的数据反馈回流。
