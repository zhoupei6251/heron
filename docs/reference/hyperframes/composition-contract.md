# 项目结构与 Composition 契约

> 上游：<https://hyperframes.heygen.com/concepts/index> · <https://hyperframes.heygen.com/reference/html-schema> · <https://hyperframes.heygen.com/concepts/compositions> · 快照：`_upstream/concepts-index.md`、`reference-html-schema.md`、`concepts-compositions.md` · 版本：hyperframes CLI（npm `^0.9.6`）· 抓取 2026-11-13

一个 HyperFrames 项目就是一个文件夹，HTML 本身就是视频：场景、时间、运动、媒体、可复用部件全部写在里面。agent、浏览器编辑器、CLI、SDK、Player 读写的是**同一份文件**，中间没有导出步骤，也没有第二份工程副本。

```
你的项目文件夹（HTML、CSS、媒体）
    ├── Agent（故事与结构）
    ├── 浏览器编辑器（能点选的部分）
    ├── CLI（check、preview、render）
    └── SDK / Player（嵌进自己的产品）
              ↓
        renders/final.mp4
```

| 想改什么 | 从哪下手 |
| - | - |
| 故事、结构、多个场景 | 让 agent 改 |
| 能看见的文字、版式、时间、动画 | 浏览器编辑器 |
| 预览、检查、快照、渲染、自动化 | CLI |
| 把 composition 嵌进自己的产品 | SDK 或 Player |

## 项目文件

```
project/
├── BRIEF.md              what the video must communicate
├── STORYBOARD.md         the planned sequence and review state
├── SCRIPT.md             locked narration, when the video has it
├── frame.md              visual direction, when the project needs one
├── index.html            the main composition
├── hyperframes.json      project settings
├── compositions/         scenes and reusable visual parts
├── assets/               images, video, audio, and fonts
└── renders/              finished files
```

只有源码是必需的。规划文件的作用是把决策跨评审、跨 agent 会话保存下来：一张标题卡可能只要 `index.html` 加一个素材；一条有旁白的片子则受益于 brief、storyboard、script 和拆开的场景文件。

## Composition 是什么

composition 是项目里一段**有限、可 seek** 的 HTML。`index.html` 是顶层 composition，它可以把别的 composition 嵌进来。没有特殊的「根类型」——任何 composition 都能被任何其它 composition 引入。

一个完整的视频可以只有这么多：

```html
<div id="root" data-composition-id="root"
     data-start="0" data-width="1920" data-height="1080">
  <img class="clip" src="assets/logo.png"
       data-start="0" data-duration="2" data-track-index="0" />
  <video class="clip" src="assets/video.mp4"
         data-start="2" data-duration="3" data-track-index="0"></video>
</div>
```

两条规则让它工作：

1. 外层元素必须有 `data-composition-id`；
2. 每个带时间的元素要有 `class="clip"`，运行时据此在时间窗之外把它隐藏。

上例总长 5 秒，默认 30fps 即 150 帧（0–149）。渲染时没有任何东西实时播放：渲染器要第 0 帧、再要第 1 帧，每次返回一张静止画面——这正是确定性渲染的基础。

能上时间轴的东西：`<video>`、`<img>`、`<audio>`、`<div data-composition-id="...">`（嵌套 composition）。

## Composition 根属性契约

| 属性 | 必填 | 含义 |
| - | - | - |
| `data-composition-id` | 是 | 唯一 composition ID，必须与时间轴注册键一致 |
| `data-start="0"` | 顶层根必填 | composition 起始时间 |
| `data-width` / `data-height` | 是 | 作者视角的帧尺寸（像素） |
| `data-duration` | 通常需要 | 总渲染时长（秒） |
| `data-no-timeline` | 仅无时间轴的 composition | 告诉运行时不要等时间轴。不加的话，一个从不注册时间轴的 composition 每次渲染都会白等 `player-ready-timeout`（默认 45 秒）；`hyperframes lint` 报 `missing_data_no_timeline` |

> **根 `data-duration` 就是渲染长度。编译器在 composition 脚本运行之前就读它**，所以脚本或变量覆盖都改不了同一次渲染的这个值。

只有 HyperFrames 能从已注册的动画运行时或定时媒体推断出有限时长时，根才可以省略 `data-duration`。Three.js、无界动画、无时间轴的 composition 必须显式给时长。

## 时间元素属性契约

| 属性 | 必填 | 含义 |
| - | - | - |
| `id` | 是 | 时间、编辑、动画都依赖的稳定标识 |
| `data-start` | 是 | 起始秒数，或相对时间表达式 |
| `data-duration` | DOM 与嵌套 composition 必填；image 默认 3 秒，video/audio 默认取源长度 | 可见槽位长度（秒） |
| `data-track-index` | 否 | **仅用于浏览器编辑器的时间轴分道显示**，渲染不读，也不阻止重叠 |
| `class="clip"` | 作者手写的定时 DOM 与图片建议加 | 布局与工具约定：可见性由 `data-start` 决定，但共享的 `.clip` 规则提供满帧盒子 |

**`data-track-index` 不控制绘制顺序**，前后层次用 CSS `z-index`。同一 track 上两个 clip 可以在时间上重叠——lane 只是编辑器的显示行，不是调度约束。

负 `z-index` 是合法 CSS，但如果父级没有创建层叠上下文，不透明的祖先背景会盖住它。给父级加 `isolation: isolate` 即可让负层位于父背景之上、前景兄弟之下。`negative_z_index` lint 警告基于 CSS 文本判断，对已隔离的层也会报，以实际渲染结果为准。

视频的可见性由媒体运行时管理，不需要 `class="clip"`；音频没有视觉生命周期。

## 媒体属性

```html
<video
  id="demo"
  src="./assets/demo.mp4"
  data-start="0"
  data-duration="8"
  data-track-index="0"
  muted
  playsinline
></video>
```

| 属性 | 适用 | 含义 |
| - | - | - |
| `data-media-start` / `data-playback-start` | video、audio、嵌套 composition | 源文件内的偏移，trim/split 依赖它。**两组读取方不一致**：只读 `data-media-start` 的有时间编译器、HTML 解析器、`hyperframes validate`（只检查 `<audio>`）、引擎音频混音器（喂给 ffmpeg `-ss`）；先读 `data-playback-start`、回退 `data-media-start` 的有运行时播放器、浏览器编辑器（也会写它）、`hyperframes snapshot`。因为混音器只读 `data-media-start`，一个只写了 `data-playback-start` 的 `<video>` 会出现**画面被裁剪、声音没被裁剪**。按元素类型选名：`<video>` / `<audio>` → `data-media-start`；嵌套 composition → `data-playback-start`（这是编辑器写入并规范化的名字，也是子时间轴偏移） |
| `data-playback-rate` | video、audio、嵌套 composition | 播放倍率，`0.1` 到 `10`。`data-automation` 里的 `rate` lane 会覆盖它 |
| `data-volume` | video、audio | 静态增益。`1` 是 0 dB，`0` 静音，大于 `1` 最高 `3.98`（+12 dB）。与 volume lane 相乘，且作用于 clip 音频效果之前 |
| `data-fade-in` / `data-fade-out` | video、audio | clip 边缘淡入淡出（秒）：前 `data-fade-in` 秒 0→1，最后 `data-fade-out` 秒 1→0。线性，锚定 clip 自身边缘（淡出跟随之后的 trim），并乘在 `data-volume` 与 volume lane 之上。两者之和超过 clip 长度时会被等比压缩到 clip 内 |
| `data-has-audio="true"` | video | 声明该 video 贡献声音 |

video 与 audio 在内在时长已知、且应完整播放剩余源时，可以省略 `data-duration`。

> **不要命令式调用 `play()`、`pause()` 或设置 `currentTime`**——媒体播放与 seek 归 HyperFrames。有声 `<video>` 声明 `data-has-audio="true"` 且不 muted；无声素材用 `muted`。每个定时 `<video>` 必须二选一（lint `video_missing_muted`）。

媒体可以出现在嵌套 composition 里；**全工程元素 ID 必须唯一**，因为渲染期注入帧是按 ID 定位的。

## 相对时间

非数字的 `data-start` 表示「在另一个 clip 结束之后」：

```html
<video id="intro" data-start="0" data-duration="4" data-track-index="0"
       src="./intro.mp4" muted playsinline></video>

<section id="result" class="clip" data-start="intro + 0.5" data-duration="3" data-track-index="0">
  Result
</section>
```

支持 `clip-id`、`clip-id + seconds`、`clip-id - seconds`。引用只在同一 composition 内解析，被引用 clip 必须有已知时长，引用链不能成环。有意重叠请放到不同 track。

## 嵌套 composition

宿主声明固定时间窗：

```html
<div
  id="pricing-scene"
  data-composition-id="pricing"
  data-composition-src="./compositions/pricing.html"
  data-start="4"
  data-duration="6"
  data-track-index="1"
  data-width="1920"
  data-height="1080"
></div>
```

- 宿主的 `data-composition-id` 必须与源文件内部 ID、时间轴注册键一致。
- 宿主 `data-duration` 决定嵌套 composition 可见多久：内层时间轴更短则保持末状态，宿主更短则被截断。
- 路径**从项目根解析**，不是从引用它的文件解析：一层深的嵌套也写 `compositions/foo.html`，绝不写 `../compositions/foo.html`。
- 嵌套文件用 `<template>` 承载实时标记，样式与脚本必须放在 template 内：

```html
<template>
  <style>
    #root { position: absolute; inset: 0; }
  </style>

  <div id="root" data-composition-id="pricing" data-width="1920" data-height="1080">
    <!-- scene content -->
  </div>

  <script>
    window.__timelines = window.__timelines || {};
    const timeline = gsap.timeline({ paused: true });
    // scene animation
    window.__timelines.pricing = timeline;
  </script>
</template>
```

- 子 composition 内部的时间是**局部**的：宿主从 10 开始、子视频从 2 开始，则根时间轴上它在 12 出现。老工程若按根时间写过媒体起点，可用 `data-hf-media-start-basis="global"` 显式保留，新工程一律用局部时间。
- `data-playback-start` 决定子时间轴从哪一刻开始显示，默认 `0`；从左侧 trim/split 会按「经过时间 × `data-playback-rate`」推进该偏移，嵌套动画因此继续而不是跳回开头。
- **HyperFrames 独立 seek 嵌套时间轴，不要手动把子时间轴加进父 GSAP 时间轴。**

也可以直接内联写进父级（一次性场景更简单）：不给 `<template>`、不给 `data-composition-src`，直接写一个带 `data-composition-id` 的子 `div` 并注册时间轴。

## HTML 管时间，脚本管运动

HTML 通过 `data-*` 说明「播什么、什么时候、哪条轨道」；脚本负责创意部分：效果、转场、canvas、SVG、GSAP 动画。

> **绝不要用脚本去 play/pause/seek 媒体，也不要按时间显隐 clip。** 框架已经按 data 属性做这些事，脚本再做一遍只会和框架打架。

## 从脚本加载 composition 自己的文件

composition 挂载时，指向它旁边文件的 `src`/`href` 会被重写到实际位置，所以 `<img src="assets/logo.png">` 仍然有效。但**脚本拼出来的路径是普通字符串，永远不会被重写**，这时用 `window.__hyperframes.assetUrl()`：传入相对该 composition 自身文件的路径，返回可加载 URL。

```html
<div data-composition-id="globe" data-width="1920" data-height="1080">
  <script>
    const texture = new THREE.TextureLoader().load(
      __hyperframes.assetUrl("assets/earth.jpg"),
    );
  </script>
</div>
```

反之，**从项目根写起的路径**（如 `compositions/intro.html` 引用项目 `assets/` 下的图）本来就能解析，保持普通字符串即可——用 `assetUrl()` 反而会去 composition 旁边找而落空。

独立脚本文件（`<script src="...">`）没有自己的 composition 可解析，应在 composition 的内联脚本里解析好 URL 再交给它。

## 颜色分级与媒体效果

浏览器编辑器和 CLI 把调色、LUT、媒体效果存在图片/视频的 `data-color-grading` 上：

```html
<video
  id="demo"
  src="./assets/demo.mp4"
  data-color-grading='{
    "preset": "clean-studio",
    "intensity": 0.8,
    "adjust": { "highlights": -0.08, "shadows": 0.06 },
    "details": { "grain": 0.12, "vignette": 0.08 },
    "effects": { "bloom": 0.12 },
    "colorSpace": "rec709"
  }'
></video>
```

效果族会变，别硬编码旧列表，用 `npx hyperframes media-treatment --capabilities --json` 查当前机器可读的能力面。分级作用于真实 `<img>` / `<video>` 元素，**不作用于整个 HTML 场景**。

## 校验

```bash
npx hyperframes lint
npx hyperframes check
```

`lint` 查静态契约；`check` 在浏览器里跑运行时，检查行为、布局、运动和对比度。最后再人工看代表性快照和成片。

另：`npx hyperframes compositions` 可列出项目里所有 composition。

## Heron 用在哪

Heron 的新闻视频模板（`data/templates/news-*-9x16/`）就是这套契约的实例：每个模板目录下有 `index.html` 加 `.hyperframes/`，生产线的 `NewsProductionPipeline` 用变量注入内容、在磁盘上补丁时间轴，再调 `hyperframes render`。模板里用 `data-var-src` 声明的变量必须和 `templateVariables()` 输出的键一一对应，见 [variables.md](variables.md)。
