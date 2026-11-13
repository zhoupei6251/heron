# HyperFrames CLI 参考

> 上游：<https://hyperframes.heygen.com/packages/cli> · <https://hyperframes.heygen.com/developers/cli> · 快照：`_upstream/packages-cli.md`、`developers-cli.md` · 版本：hyperframes CLI（npm `^0.9.6`）· 抓取 2026-11-13

本页只收「在有模板的项目里检查并渲染视频」这条链路用得到的命令。素材采集（`capture`、`transcribe`、`tts`、`remove-background`、`media-use`、`beats`、`audio`、`models`、`normalize-audio`）、云端渲染（`cloud`、`lambda`、`cloudrun`）、Studio 与账号相关（`auth`、`billing`、`templates`、`skills`、`usage`、`figma`、`telemetry`、`feedback`）不在范围内，需要时查上游。

**已安装的版本才是权威**：`npx hyperframes help <command>` 或 `npx hyperframes <command> --help`。命令或 flag 打错会以退出码 2 结束，并提示最接近的正确写法（`Did you mean 'hyperframes render'?`）。

```bash
# 免安装
npx hyperframes <command>

# 或装一次
npm install -g hyperframes
```

## 所有命令的公共约定

| 项 | 约定 |
| - | - |
| **项目目录** | 多数命令把目录作为第一个位置参数，默认当前目录：`hyperframes check ./my-video`。`add`、`transcribe`、`feedback`、`skills`、`history` 和 `timeline` 的编辑命令改用 `--dir` |
| **`--json`** | 多数命令支持；不支持的会以退出码 2 拒绝。返回体带 `_meta`（`version`、`latestVersion`、`updateAvailable`、`checkedAt`、`skillsOutdated`、`skillsMissing`、`updateNotice`），脚本可据此判断 CLI 是否过期，**`--json` 本身不发网络请求** |
| **`--json` 下的失败** | 一定向 stdout 打一个 JSON 对象：`{"ok":false,"error":{"code":"unknown_flag","message":"...","hint":"..."}}`。`lint` 会额外带 `findings`，`timeline` 被拒的编辑带 `reason` 与 `fix` |
| **退出码** | `0` 成功；`1` 执行了但失败；`2` 命令行本身有问题（未知命令/flag、缺值、非法值） |
| **不交互** | 缺必填 flag 立刻失败并给用法示例，不会等输入。只有 `init`、`catalog --human-friendly`、`share` 会在 TTY 上提问 |
| **无值守** | stdin/stdout 不是终端、在 CI 里、在 coding agent 里、或设了 `HYPERFRAMES_AGENT=1` 时视为无值守：本会请求确认的命令（`publish`、`upgrade`）直接退出 2 并指出要传 `--yes`；spinner 只在 stderr 打最后一行 |
| **`.env`** | 当前目录的 `.env` 只设 CLI 自身设置（`HEYGEN_API_KEY`、`HYPERFRAMES_API_KEY`、`ELEVENLABS_API_KEY`、`FIGMA_TOKEN`、telemetry 开关等），已在环境里的变量优先，其它键忽略并在 stderr 提一行 |
| **升级提示** | 自管安装会后台静默自升级并在下次运行打一行；不跨大版本（1.0 前不跨次版本）。`HYPERFRAMES_NO_UPDATE_CHECK=1` 关掉检查、安装与提示；`HYPERFRAMES_NO_AUTO_INSTALL=1` 只关安装 |

其它对自动化有用的环境变量：`HYPERFRAMES_CACHE_DIR`（改缓存根目录）、`HYPERFRAMES_NO_ORPHAN_CLEANUP=1`（preview/render 前不清理崩溃会话残留的 Chrome）、`HYPERFRAMES_SKIP_SKILLS=1`（关技能提示）。

## 日常项目循环

| 任务 | 命令 |
| - | - |
| 建项目 | `npx hyperframes init my-video` |
| 打开 Studio 预览 | `npx hyperframes preview` |
| 查 HTML 结构 | `npx hyperframes lint` |
| 跑浏览器 / 布局 / 运动 / 对比度检查 | `npx hyperframes check` |
| 抓评审帧 | `npx hyperframes snapshot --at 0,2,5` |
| 渲染成片 | `npx hyperframes render --output output.mp4` |
| 诊断机器 | `npx hyperframes doctor` |
| 清理占用的磁盘 | `npx hyperframes clean` |

## 检查：lint / check / snapshot / keyframes

### `lint`

只读 HTML，不开浏览器，所以很快。

```bash
npx hyperframes lint [dir]
npx hyperframes lint [dir] --verbose   # 带上 info 级发现
npx hyperframes lint [dir] --json
```

```
◆  Linting my-project/index.html

  ✗ missing_gsap_script: Composition uses GSAP but no GSAP script is loaded.
  ⚠ video_missing_muted [clip-1]: <video id="clip-1"> has data-start but declares neither muted nor data-has-audio. ...

◇  1 error(s), 1 warning(s)
```

- `✗` 错误必须在渲染前修（缺适配库、非法属性）；`⚠` 警告是「很可能有问题」；`ℹ` info 默认不显示。
- `--json` 额外给 `errorCount`、`warningCount`、`infoCount` 与 `findings` 数组。
- **只 lint 项目实际用到的 composition**：`index.html` 加上它通过 `data-composition-src` 挂载的文件（递归）。`compositions/` 下没被挂载的文件默认跳过并提示数量，`--include-unmounted` 一并检查，`--json` 里列在 `unmountedCompositions`。
- `--lint-verbose` 与 `--verbose` 同义（`render`、`preview`、`publish` 也认这个），`check` 同样支持。

### `check`

浏览器闸。老版 `validate` → `inspect` → `snapshot` 三轮做的事，合并成一条命令、一个浏览器会话。

```bash
npx hyperframes check [dir]
npx hyperframes check [dir] --json         # {ok, browserSkipped, scope, lint, runtime, layout, motion, contrast, craft, audio, hdr, snapshots}
npx hyperframes check [dir] --snapshots
npx hyperframes check [dir] --at 1.5,4,7.25
npx hyperframes check [dir] --strict       # 警告也判失败
```

执行顺序：先跑 lint。**lint 错误会让 check 失败但不中断**——浏览器检查照跑，两者都报，除非 lint 错误导致没有 composition 可加载（根缺 `data-composition-id`）。然后加载 composition 一次，扫一遍 seek 网格，在**每个采样点**跑全部审计：运行时 console 错误与失败请求、布局缺陷（溢出、裁切、保持重叠、遮挡、坐标帧漂移）、`*.motion.json` 断言、WCAG AA 对比度、craft 警告。

`audio` 段读渲染会混的每个 clip：无音轨或读不出音频（error，渲染会停）、起点超过文件末尾、播放段全静音（warning）、整片没声音（info）。

浏览器没跑起来时（没 composition 可加载、linter 崩溃、浏览器启动失败），`browserSkipped` 为 `true`，`layout` / `motion` / `contrast` 三段是**空的，不是干净的**。

| Flag | 说明 |
| - | - |
| `--json` | 一个聚合信封；每条发现带 selector、`data-*` 身份、源文件、bbox、采样时间 |
| `--snapshots` | 把五个审计帧存到 `.hyperframes/artifacts/snapshots/`，每条发现另存 `finding-NN-<code>.png` 裁图 |
| `--include-unmounted` | 也 lint `index.html` 从未挂载的 `compositions/` 文件 |
| `--samples` | 按时长取中点采样（默认 9） |
| `--at` | 显式时间戳（秒，逗号分隔） |
| `--at-transitions` | 额外采样每个 tween 的起止，抓转场接缝处的瞬时重叠 |
| `--max-transition-samples` | 限制转场采样数；截断时报告省略数量。默认不限 |
| `--range <start,end>` | 只采样这段时间（全局秒）。lint 仍全工程；整片 motion、`sweep_static`、craft 警告被跳过 |
| `--components <selectors>` | 只报涉及这些元素或其后代的发现。**计数、`ok`、退出码仍覆盖全量** |
| `--max-issues` | 静态折叠后打印/返回的发现数（默认 80） |
| `--collapse-static` / `--no-collapse-static` | 把重复的静态发现折成一行（默认开） |
| `--tolerance` | 允许的溢出像素（默认 2） |
| `--timeout` | 渲染就绪预算（ms，默认 45000，同 snapshot 与 render），也会把页面导航的 10s 下限抬高 |
| `--budget <ms\|90s\|8m>` | 从开始算起最多跑这么久（至少 30s），到点报告已有结果并给 `check_budget_reached` 标明所在阶段 |
| `--no-cache` | 工程文件、flag、CLI、浏览器都没变时也重跑。否则未变工程约 1 秒返回上次结果并标记 cached；**失败从不缓存** |
| `--no-contrast` | 迭代时跳过 WCAG |
| `--no-craft` | 跳过 craft 段（整次 check 最慢的部分），报告标记为已跳过。渲染前预检可用，发版前的完整检查要留着 |
| `--strict` | 警告也返回非 0（默认只看错误） |
| `--caption-zone "<x0=..;y0=..;x1=..;y1=..>"` | 可选的字幕带闸：文字 DOM 盒子与该比例带重叠即报，可带 `severity` 与 `seek` |
| `--frame-check` | 可选：检测 `img`、`svg`、`video`、`canvas` 出画 |
| `--layout` | 布局旋钮，目前是 `proseCoverageFloor=0.05`（0–1，默认 0.15） |
| `--browser-gpu` / `--no-browser-gpu` | 硬件 GPU 捕获，或确定性的 SwiftShader（默认自动探测） |
| `--proxy` / `--no-proxy` | 自动把浏览器不友好的编码转成可播代理（默认开） |
| `--prepare-media` | 只生成浏览器可播的视频副本后退出：不开浏览器、不出报告。`--json` 逐个列 `ready` / `cached` / `failed`，有失败则退出 1 |

**浏览器解不了的视频**（HEVC、ProRes）会被复制一份，首次 check 最多等 2 分钟，超时报 `media_copies_unfinished` 并列出未完成文件与进度；用 `--prepare-media`（无时限）跑完。长视频若在 composition 里的每次使用都有已知 `data-duration`（在 video 或其定时元素上、且无 `loop`），只从文件起点复制那一段，按整 15 秒向上取整。超过 22 秒的源按 15 秒分块保留，中断后下次从已完成块继续。复制期间每 10 秒往 stderr 打一次进度（stdout 留给 `--json`），3 分钟无进展就结束该副本（`HYPERFRAMES_PROXY_STALL_MS`）。

**严重度看持续性**：只在单个采样点出现的发现降为 info；持续出现的才决定退出码。3 秒以上的 composition 若画面从未变化、且页面上有 GSAP tween/时间轴或 CSS/Web 动画一直停在某个时间没有结束（seek 驱动不了它），报 `sweep_static` 并失败；静止标题卡这类「本来就不动」的画面则是 `sweep_static` 警告，`--strict` 下也失败——可以用 `data-no-timeline` 声明跳过该守卫。

#### Craft 警告

`craft` 段报「观众看久了会发现、单帧看不出来」的问题。**每条 craft 都是警告，`--strict` 下也一样**（成片可以有意为之）。在整片 8 fps 扫描上测量，短行边界复测到约 0.01 秒；`--range` 检查时跳过。

| Code | 何时警告 |
| - | - |
| `line_unread` | 阅读尺寸的行（1080 下 ≥30 px）在屏时间不足下限：1–2 个词 0.4 秒，否则 0.75×(0.5 秒 + 0.3 秒/词)。全片最后一行需要 2 秒 |
| `dead_stretch` | 超过 3 秒画面无变化（与 `keepsMoving` 同一个分类器） |
| `text_below_floor` | 小于画幅短边 2.6%（1080 下 28 px）的文字在屏 ≥0.5 秒。每个文件一条，列出全部此类元素 |
| `first_frame_empty` | 第 0 帧只有背景（无文字、图片、视频、canvas、SVG），或有元素覆盖了其它所有 clip。第一帧是社交播放器上的封面 |
| `film_silent` | 超过 15 秒的片子没有非静音 `<audio>`，也没有带声音的 `<video>` |

`data-track-kind="captions"` 的字幕轨文字和 `data-layout-ignore` 的文字豁免；`dead_stretch` 不计最后 3 秒（保留的片尾卡）和带 `data-hold` 的元素在屏期间。

#### 音频发现

| Code | 级别 | 触发 | 修复 |
| - | - | - | - |
| `audio_source_unreadable` | error | 文件读不出音频，渲染混音会失败并中止 | 换成有音轨的文件，或重新导出 |
| `audio_source_rejected` | error | 文件没有音轨，渲染开始前就停 | 换有音频的文件，或去掉 `<video>` 上的 `data-has-audio="true"` |
| `audio_starts_past_source_end` | warning | `data-media-start` 大于等于文件长度，clip 什么都不播 | 把 `data-media-start` 调到文件长度以内 |
| `audio_clip_silent` | warning | clip 播放的那段整段静音 | 确认该处有声，或把 `data-media-start` 移到有声的位置 |
| `audio_none` | info | 没有 clip 播放音频，也没有非静音 `<video>` 带音轨 | 默片可以不管，否则加上 `<audio>` |
| `audio_check_unavailable` | info | 音频检查本身失败，并说明原因 | 重跑；反复出现就 `hyperframes doctor` |
| `audio_src_not_found` | error | `<audio src>` 指向项目里不存在的文件，渲染会静音。由 `lint` 报出 | 补文件或改路径 |

### 用 HTML 标记「这是有意为之」

审计误判但布局本身是设计时，在 HTML 里标注后重跑：

| 标记 | 用途 |
| - | - |
| `data-layout-allow-overflow` | 计划内的画外出入场 |
| `data-layout-allow-overlap` | 有意叠放的文字 |
| `data-layout-allow-occlusion` | 有意藏在道具后的文字 |
| `data-layout-ignore` | 完全不参与审计的装饰 |

overflow、occlusion、ignore **可继承**（放祖先生效）；**overlap 是局部的**——写在参与叠放的那个文字块上，别写在场景/根容器上，否则后代真实碰撞会被漏审。字幕带里的下层文字用 `data-layout-allow-caption-zone`，它只关闭 `caption_zone_collision`，溢出、重叠、遮挡、对比度照旧。

### 用 `*.motion.json` 验证运动

布局采样抓不到「渲染 ≠ 预览」类问题（入场被 seek 跨过、stagger 顺序乱、元素在 tween 中途飘出画、镜头冻结）。在 composition 旁边放一个 `*.motion.json`，`check` 会自动评估——不用加 flag，也不用改 HTML。

```json
{
  "duration": 6,
  "assertions": [
    { "kind": "appearsBy", "selector": "#headline", "bySec": 0.5 },
    { "kind": "before", "a": "#headline", "b": "#cta" },
    { "kind": "staysInFrame", "selector": ".card" },
    { "kind": "keepsMoving", "withinSelector": ".scene" }
  ]
}
```

| 断言 | 抓什么 |
| - | - |
| `appearsBy(selector, bySec)` | 入场被 seek 跨过——元素须在 `bySec` 前不透明度 ≥ 0.5（`motion_appears_late`） |
| `before(a, b)` | stagger 顺序乱——`a` 必须严格先于 `b` 首次出现（`motion_out_of_order`） |
| `staysInFrame(selector)` | 出画漂移——可见之后盒子不离开画布（`motion_off_frame`） |
| `keepsMoving(withinSelector?)` | 镜头冻结——不存在长于 `maxStaticSec`（默认 2 秒）的全静窗（`motion_frozen`）；最后 3 秒不计 |

断言失败默认是 error，会让整次运行失败；selector 匹配不到任何元素时报 `motion_selector_missing`，不会静默通过。

### `snapshot`

不跑完整渲染，直接把指定帧抓成 PNG。

```bash
npx hyperframes snapshot my-project --at 2.9,10.4,18.7
npx hyperframes snapshot my-project --frames 10
npx hyperframes snapshot my-project --zoom '#headline'
npx hyperframes snapshot my-project --at 1.5,4.3 --against ref.mp4
npx hyperframes snapshot my-project --review --json
```

| Flag | 说明 |
| - | - |
| `--at` | 时间戳（秒，逗号分隔） |
| `--frames` | 改为均匀取帧（默认 5） |
| `--range <start,end>` | 把 `--frames` 摊在这个区间（根时间轴秒），不额外加末帧 |
| `--composition <id>` | 把 `--frames` 摊在指定 `data-composition-id` 元素的可见区间内 |
| `--output, -o` | PNG 输出目录（默认每次新建 `.hyperframes/artifacts/snapshots/<id>`） |
| `--end` / `--no-end` | 是否总是加一张可读的末帧（默认加） |
| `--keep` | 往 `--output` 里追加而不是清空，按时间命名 `at-1.5s.png` |
| `--zoom` | 裁剪到 CSS selector 或精确 `x,y,w,h` 区域，用提高的设备像素比，**不动布局** |
| `--against` | 参考视频（比如正在复刻的素材），额外存帧对齐的参考帧与并排对比图 |
| `--zoom-scale` | `--zoom` 裁剪的像素密度（默认 3） |
| `--angle` | 正交 3D 机位 `front`、`iso`、`top`、`side` 或 `yaw,pitch` 度数，看深度与遮挡 |
| `--review` | 抓「体现时间推进」的帧：`review-beats.jpg`、`review-seam-NN.jpg`（每个剪辑点前后 0.2 秒）、`review-phone.jpg` |
| `--json` | 一个信封：`frames`（路径、时间、review 下的 role/label）、contact sheets 等进度的东西走 stderr |
| `--describe` | 默认关。把每帧发给 Gemini 并写 `descriptions.md`，需要 `GEMINI_API_KEY` 或 `GOOGLE_API_KEY` |
| `--timeout` | 等运行时的时间预算（ms，默认 5000） |

### `keyframes`

列出 composition 里实际检测到的 GSAP、CSS、Anime.js 关键帧，或给单个元素渲染洋葱皮诊断图。

```bash
npx hyperframes keyframes [dir]
npx hyperframes keyframes [dir] --selector "#card" --shot card-motion.png
```

`--selector` 限定元素，`--runtime gsap|css|anime|all` 过滤，`--json` 输出机器可读结果，`--shot` 写洋葱皮 PNG，`--samples` 采样数（默认 9）。

## 渲染：`render`

把 composition 渲染成 MP4、WebM、MOV、GIF、HLS 或 RGBA PNG 序列。

```bash
# 快速迭代
npx hyperframes render --output output.mp4

# 确定性输出
npx hyperframes render --docker --output output.mp4

# 带透明通道，用于叠加层与下三分之一条
npx hyperframes render --format webm --output overlay.webm

# HLS VOD（输出是播放列表与 .ts 分片的目录）
npx hyperframes render --format hls --output stream

npx hyperframes render --output output.mp4 --fps 60 --quality high
npx hyperframes render --gpu --output gpu.mp4
```

### 最常用的 flag

| Flag | 默认 | 说明 |
| - | - | - |
| `--output, -o` | `renders/<project>_<YYYY-MM-DD>_<HH-MM-SS>.<ext>` | 输出路径 |
| `--composition, -c` | `index.html` | 渲染别的 composition 文件。用 `<template>` 包装的子 composition 必须经 `index.html` 的 `data-composition-src` 引用 |
| `--format` | `mp4` | `mp4`、`webm`、`mov`、`gif`、`png-sequence`、`hls`。WebM 与 MOV 带透明；`png-sequence` 写 RGBA 帧目录；`hls` 写 VOD 播放列表目录 |
| `--fps, -f` | 根 `data-fps`，否则 30 | 1–240，或 ffmpeg rational（如 `30000/1001` 表示 29.97） |
| `--quality, -q` | `looks` | `draft`、`looks`（CRF 16，默认）、`delivery`（= `high`）、`standard`、`high`。MOV 一律用固定的保 alpha ProRes 4444 |
| `--resolution` | composition 自身尺寸 | 通过 Chrome 的 `deviceScaleFactor` 渲染到预设（`landscape-1440p`、`portrait-1440p`、`square-1440p`）。**宽高比必须一致**，放大必须是整数倍：720p→1440p 可以，1080p→1440p 被拒。不能与 `--hdr` 同用 |
| `--docker` | 关 | 在 Docker 内渲染以获得确定性输出 |
| `--variables` | — | JSON 对象，合并到 composition 的 `data-composition-variables` 默认值之上 |
| `--variables-file` | — | 从 JSON 文件读同样的覆盖值 |
| `--batch` | — | 按变量行各渲染一个产物，输入是 JSON 数组或 `{"rows":[...]}` |
| `--json` | 关 | 配合 `--batch` 时打印一份最终 JSON 结果文档，而不是进度 |

### 质量与体积

| Flag | 默认 | 说明 |
| - | - | - |
| `--crf` | 由 `--quality` 定 | 覆盖 MP4/WebM 编码 CRF，0–51，越小越好。不能与 `--video-bitrate` 同用；MOV 拒绝 |
| `--video-bitrate` | 由 `--quality` 定 | MP4/WebM 目标码率，如 `10M`、`5000k`。不能与 `--crf` 同用；MOV 拒绝 |
| `--keyframe-interval` | 编码器默认 | 仅 MP4。至少每 N 秒一个关键帧，便于播放器 seek 或切换码流 |
| `--start` / `--end` | 全片 | 只渲染这两者之间的秒数，产出从 0 开始的 MP4（带该段音频）。仅 24/25/30/60 fps 的 SDR MP4 |
| `--max-bitrate` | 不设上限 | 仅 MP4。峰值码率上限，如 `8M`，与 `--crf`/`--quality` 目标并存而非替代 |
| `--buffer-size` | 2 × `--max-bitrate` | 仅 MP4。上限的测量缓冲，需要 `--max-bitrate` |
| `--vp9-cpu-used` | 编码器默认 | WebM 的 libvpx-vp9 速度/质量权衡，-8 到 8。环境变量 `PRODUCER_VP9_CPU_USED` |
| `--chroma-subsampling` | `420` | MP4 色度：`420` 或 `444`。`444` 保住细彩色文字与 UI 的全分辨率颜色（yuv444p、无损 PNG 捕获、更慢）；仅软件编码器 SDR MP4，与 `--gpu`、`--hdr` 等互斥，很多播放器解不了 4:4:4 |
| `--gif-loop` | `0` | GIF 循环次数，`0` 为永久，0–65535，仅 `--format gif` |
| `--hls-segment-seconds` | `4` | HLS 目标分片长度（整秒，1–60），仅 `--format hls`；同时固定编码 GOP 让每个分片从关键帧开始 |
| `--video-frame-format` | `auto` | 源视频抽帧格式：`auto`、`jpg`、`png`。UI 录屏、屏幕捕获等对颜色敏感的素材用 `png` |
| `--hdr` / `--sdr` | 关 / 关 | 强制 HDR（即使没有 HDR 源，仅 MP4）或强制 SDR |
| `--page-motion-blur` | 作者设置 | 传 `off` 可在不改 composition 的前提下关掉页面动态模糊 |

### 机器使用与放弃条件

| Flag | 默认 | 说明 |
| - | - | - |
| `--workers, -w` | auto | 1–24 个 worker，每个是独立 Chrome，约占 256 MB。N 是上限：能走 drawElement 捕获的渲染只用一个 |
| `--low-memory-mode` / `--no-low-memory-mode` | ≤8 GB 内存时 auto | 安全档：单 worker、截图捕获、不做校准 |
| `--gpu` | 关 | 硬件 FFmpeg 编码（NVENC、VideoToolbox、AMF、VAAPI、QSV） |
| `--browser-gpu` / `--no-browser-gpu` | 本地 auto，Docker 内关 | Chrome 与 WebGL 捕获用宿主 GPU，或用软件渲染 |
| `--page-side-compositing` / `--no-...` | 开 | mp4/hls/gif 的 SDR shader 转场走页面侧 WebGL，约快 6 倍 |
| `--experimental-fast-capture` | 能启用时开 | Chrome 的 draw-element 捕获，约快 2 倍，失败会自行回退到截图。环境变量 `PRODUCER_EXPERIMENTAL_FAST_CAPTURE` |
| `--frames-cache-dir` | `<tmpdir>/hyperframes-extract-cache-<uid>` | 抽帧缓存目录；传 `off`、`none`、`false`、`0` 关闭 |
| `--max-concurrent-renders` | `2` | Producer 服务上的并发任务数，1–10 |
| `--best-effort` / `--no-best-effort` | 开 | 有捕获就绪警告也完成，或在媒体缺失/未就绪时失败 |
| `--wait` / `--no-wait` | 开 | 每个用户账号同时只跑一个本地渲染：等待或在忙时立即失败。`HYPERFRAMES_RENDER_LOCK=off` 可并行 |
| `--strict` / `--strict-all` | 关 | lint 错误即失败，或错误加警告都失败 |
| `--strict-variables` | 关 | **变量键未声明或类型不对时失败而不是警告** |
| `--batch-concurrency` / `--batch-fail-fast` | `1` / 关 | 批量并发行数与「首次失败即停」 |
| `--resume` | 关 | 复用上次中断渲染已完成的分段（每个分段会重新校验） |
| `--keep-segments` | 关 | 渲染成功后也保留 `renders/.hf-segments/<hash>/` |
| `--debug` | 关 | 保留中间产物并把诊断写到 Producer 的 `.debug` 目录 |
| `--quiet` | 关 | 少输出 |

**并行渲染会互相抢 CPU/GPU 直到掉帧**，所以同一账号的第二个本地或 `--docker` 渲染会等第一个并说明在等谁（pid 与项目）。锁文件在该账号的 HyperFrames 缓存目录里；不同账号不排队，设 `HYPERFRAMES_CACHE_DIR` 到共享目录可让它们共用一个槽位。进程死掉的渲染会自动释放槽位。

低内存检测读的是**宿主**内存，不是 cgroup 或容器限额，所以容器化调用方（包括 `--docker`）应显式设 `PRODUCER_LOW_MEMORY_MODE`。

### 渲染超时：三个独立预算

错误信息会告诉你是哪一个。三者都有环境变量兜底，**环境变量一律用毫秒**，即使对应 flag 用秒。

| 预算 | flag | 默认 | 环境变量 |
| - | - | - | - |
| 页面根本没加载 | `--browser-timeout`（**秒**，0.001–86400） | 60 | `PRODUCER_PAGE_NAVIGATION_TIMEOUT_MS` |
| 播放器没就绪 | `--player-ready-timeout`（ms） | 45000 | `PRODUCER_PLAYER_READY_TIMEOUT_MS` |
| 单次 CDP 调用卡住 | `--protocol-timeout`（ms） | 300000（按输出像素面积自动放大，上限 30 分钟） | `PRODUCER_PUPPETEER_PROTOCOL_TIMEOUT_MS` |

`Runtime.callFunctionOn timed out` 和 `Target closed` 通常就是第三个——8 GB 内存以下的机器和素材密集的 composition 该调高。

### 长渲染与捕获路径

单 worker 的 mp4/mov 渲染**任何时长**都默认边抓边流给 FFmpeg（老的 4 分钟上限是为整片 FFmpeg 超时设的，现在超时是按帧的）。落地到磁盘写原始 RGBA 时，代价是 `frames × width × height × 4` 字节——1080p30 大约每分钟 25 GB。

`png-sequence` 与 `gif` 输出、HDR 或 shader 转场分层、以及关掉流式时，仍走磁盘路径。

| 环境变量 | 默认 | 作用 |
| - | - | - |
| `PRODUCER_ENABLE_STREAMING_ENCODE` | `true` | `false` 让所有渲染走磁盘路径 |
| `HF_CAPTURE_PARALLEL_STREAM` | BeginFrame 时开 | `true` 让多 worker 截图捕获也流式 |
| `PRODUCER_STREAMING_ENCODE_DURATION_CAP_ENABLED` | `false` | `true` 恢复时长上限 |
| `PRODUCER_STREAMING_ENCODE_MAX_DURATION_SECONDS` | `240` | 上限值，仅在上一个为 `true` 时生效 |

**分段捕获**（长渲染）：切成固定大小的段，各自独立 ffmpeg 编码最后流拷贝拼接。好处是 scratch 变成编码后的视频、浏览器在段边界重启、崩溃只损失一段。段在 `renders/.hf-segments/<hash>/`，hash 覆盖所有决定段字节的因素（composition、fps、分辨率、质量、段大小变了就重来）。成功后删除，失败后保留给 `--resume`。仅 mp4 与 mov。

| 环境变量 | 默认 | 作用 |
| - | - | - |
| `HF_SEGMENTED_CAPTURE` | 未设 | `true` 让所有符合条件的渲染分段；`false` 是总开关 |
| `HF_SEGMENTED_MIN_SECONDS` | 关 | 只对至少这么长的渲染分段 |
| `HF_SEGMENT_FRAMES` | `3000` | 每段帧数（30fps 约 100 秒），最小 30 |
| `HF_SEGMENT_BROWSER_RECYCLE` | `3` | 每 N 段重启浏览器，`0` 全程一个会话 |
| `HF_VIDEO_FRAME_WINDOW` | 未设 | `true` 时每段只抽自己用到的源帧，关闭时删除 |

### 用不同内容渲染同一 composition

先在 composition 根上声明变量、在内部读取，再在渲染时覆盖：

```bash
# 用声明的默认值（preview 也用这套）
npx hyperframes render --output default.mp4

# 覆盖；缺的键落回默认值
npx hyperframes render --variables '{"title":"Q4 Report","theme":"dark"}' --output q4.mp4

npx hyperframes render --variables-file ./vars.json --output out.mp4
```

`getVariables()` 返回「声明的默认值 + 覆盖值」合并结果，所以同一份 composition 在预览与生产行为一致。多产物用 `--batch`，一行一个文件。详见 [variables.md](variables.md)。

### 透明 WebM 叠加层

```bash
npx hyperframes render --format webm --output captions.webm

ffmpeg -c:v libvpx-vp9 -i captions.webm -i background.mp4 \
  -filter_complex "[1:v][0:v]overlay=0:0" -y composited.mp4
```

> 想保住透明，composition 的根元素要 `background: transparent`；WebM 走 PNG 帧捕获而不是 JPEG，以保留 alpha。

### `benchmark`

```bash
npx hyperframes benchmark [dir]
```

跑几组配置（fps、质量、worker 数）比较耗时与体积。`--runs` 每组运行次数（1–20，默认 3），`--json` 输出 JSON。

## 预览：`preview`

```bash
npx hyperframes preview [dir]
npx hyperframes preview --port 4567
npx hyperframes preview --background     # 命令退出后继续跑
npx hyperframes preview --foreground     # 非交互 shell 里也保持挂载
npx hyperframes preview --status --json  # 从 agent 侧查托管预览
npx hyperframes preview --list           # 所有在跑的预览
```

| Flag | 说明 |
| - | - |
| `--port` | 端口（默认 3002） |
| `--open` / `--no-open` | 是否开浏览器 |
| `--background` | 命令退出后保留内嵌预览 |
| `--foreground` | 非交互 shell 里也保持挂载 |
| `--json` | 托管启停、状态、列表、kill-all 输出一份带版本号的 JSON |
| `--browser-gpu` / `--no-browser-gpu` | Studio 缩略图与抓帧用硬件 GPU 或确定性 SwiftShader |

不带参数时按调用方选最安全生命周期：人的交互终端里前台跑，非交互或 agent shell 里起托管后台预览。重复执行会复用已有健康预览。**每次启动或查询都返回精确的 Studio 项目 URL 与底层服务 URL**，agent 不必靠端口猜。`--status` / `--stop` 作用本项目的后台预览，`--list` / `--kill-all` 作用全部。

预览与渲染共用同一个 runtime，但预览是实时播放、受硬件限制，重的 composition 会卡；渲染是 seek 驱动、一帧一帧抓。**以渲染出的文件为准。**

## 编辑：`timeline`

打印项目轨道与 clip，或编辑单个 clip。

```bash
npx hyperframes timeline [dir]
npx hyperframes timeline --json
```

```
timeline 10s

graphics (3)
  |████████████████████████████████████████| #title title 0-10s
  |████████████                            | #intro intro 0-3s
  |                    ████████████        | #outro outro 5-8s
```

```bash
npx hyperframes timeline move '#outro' +1 --plan   # 只看结果与 diff，不写文件
npx hyperframes timeline move '#outro' after:#intro
npx hyperframes timeline trim '#intro' --duration 2
npx hyperframes timeline split '#outro' 6.5
npx hyperframes timeline set '#music' volume=0.5
npx hyperframes timeline duplicate '#intro' --at 3
npx hyperframes timeline delete '#outro' --json
npx hyperframes timeline delete '#outro' --start 6 --end 7   # 剪掉一秒
```

| 命令 | 作用 |
| - | - |
| `move <ref> <time>` | 让 clip 在 `<time>` 开始，长度不变 |
| `trim <ref>` | 改 `--start`、`--end` 或 `--duration`（至少一个） |
| `split <ref> <time>` | 在 `<time>` 把 clip 切成两段 |
| `delete <ref>` | 删除 clip，或只删 `--start` 到 `--end` 这一段（保留前后） |
| `set <ref> <field>=<value>` | 设置 `volume`、`rate`（播放倍率）或 `track`（整数） |
| `duplicate <ref>` | 复制到 `--at <time>`（默认紧接其后） |
| `apply <file>` | 应用一组 JSON 编辑（如 `[{"verb":"move","ref":"#intro","time":"+1"}]`），全成功或全不写；`-` 从 stdin 读 |
| `undo <receipt>` | 按回执恢复被某次编辑改过的文件 |
| `ids` | 给时间轴上的 clip 盖上稳定 id |

时间写法：秒（`4.5`）、相对 clip 起点的偏移（`+1`、`-0.5`）、帧（`30f`）、`end`，或锚到别的 clip：`after:#intro`、`before:#intro`、`start-of:#intro`、`end-of:#intro`。**编辑命令不使用的 flag 会在写文件前被拒**；被拒的编辑（比如移动会造成邻居重叠）什么都不写，退出 2 并说明怎么修。

`--plan` 对所有编辑命令可用（只打印结果与 diff）；`--overwrite` 允许 move/trim 与同轨邻居重叠；`--snap` 把时间对齐到整帧（项目需有 `data-fps`）。

## 机器健康

### `doctor`

```bash
npx hyperframes doctor
npx hyperframes doctor --json
```

```
hyperframes doctor

  ✓ Version          0.1.4 (latest)
  ✓ CLI binary       npm global install · /usr/local/lib/node_modules/hyperframes/dist/cli.js
  ✓ Node.js          v22.x (linux x64)
  ✓ FFmpeg            7.x
  ✓ FFprobe           7.x
  ✓ Chrome            (system or cached)
  ✓ Docker            24.x
  ✓ Docker running    Running
```

报告 CLI 版本、跑的是哪个二进制以及来自哪里（全局 npm/pnpm/bun/Homebrew、项目 `node_modules`、`npx`、agent 插件）、Node、CPU、内存、磁盘、抽帧缓存、Linux 的 `/dev/shm`、FFmpeg、FFprobe、Chrome、Docker 等。

### 缓存与清理

HyperFrames 把下载与构建产物（无头浏览器、模型、可选包、字体、项目历史）放在 `~/.cache/hyperframes`；`HYPERFRAMES_CACHE_DIR` 可换位置（后台预览记录、registry 清单缓存、本地搜索模型与 catalog 也会跟着搬）。`hyperframes clean` 清掉它占的磁盘。

## Heron 用在哪

- **CLI 入口是配置项**：`HeronProductionProperties.hyperframes` 指到 CLI 入口（`node` 是另一个配置项），`NewsProductionPipeline.cliEntry()` 校验该路径存在，否则抛 `news_tool_missing`（HTTP 503）。所以升级 HyperFrames 后要同步改这个配置路径。
- **两道闸分别对应两条命令**：版式合法性走 `hyperframes check`（`NewsScriptContract` 类注释明确「事实真伪不在这一层判」）；渲染闸 `renderGate` 的判据是 **`hyperframes render` 退出码 0 且产物非空**——对应本页渲染一节里的退出码语义（`0` 成功、`1` 跑了但失败、`2` 命令行本身有问题）。
- **`--strict-variables` 是硬约束**：模板声明了变量集合时，`templateVariables()` 只输出声明过的键；多给的键会被这条 flag 判成错误并中止渲染。新增模板变量必须同步改 Java 侧的键集合。
- **时间轴补丁走磁盘而不是 `timeline` 命令**：根 `data-duration` 在编译期只读一次，`timeComposition()` 直接改源码；如果需要更安全的编辑，`timeline move/trim/set --plan` 会先给 diff 再写，可作为替代实现参考。
