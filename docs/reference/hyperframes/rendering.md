# 渲染与性能

> 上游：<https://hyperframes.heygen.com/guides/rendering> · <https://hyperframes.heygen.com/guides/performance> · 快照：`_upstream/guides-rendering.md`、`guides-performance.md` · 版本：hyperframes CLI（npm `^0.9.6`）· 抓取 2026-11-13

## 标准流程

```bash
npx hyperframes lint
npx hyperframes check
npx hyperframes render --output final.mp4
```

`lint` 查项目结构；`check` 在浏览器里跑运行时、布局、运动、媒体与对比度检查；最后渲染。省略 `--output` 时产物写到 `renders/`。

**渲染是用户闸**：设计上由人点头才跑，用预览那个停顿把剪辑定死，再付导出的代价。

## 选格式

| 格式 | 用途 |
| - | - |
| MP4 | 正常分享、发布、交付 |
| MOV | ProRes 工作流、带透明的剪辑中间文件 |
| WebM | Web 交付、透明叠加层 |
| GIF | issue、PR、文档里的短预览 |
| PNG sequence | 逐帧交给合成软件 |
| HLS | 从播放列表目录做 VOD 流 |

```bash
# 带透明的网页叠加层
npx hyperframes render --format webm --output overlay.webm

# ProRes 剪辑文件
npx hyperframes render --format mov --output master.mov

# 短循环预览
npx hyperframes render --format gif --fps 15 --output preview.gif

# 目录里的 RGBA 帧
npx hyperframes render --format png-sequence --output frames

# 目录里的 HLS VOD 流
npx hyperframes render --format hls --output stream
```

GIF 没有音轨、透明支持有限，正常播放优先 MP4 或 WebM。

### 透明视频

网页叠加层用 WebM，剪辑中间文件用 MOV（ProRes 4444）。**要在输出里保持透明，composition 背景就不能画**——不透明的 `html`、`body` 或满帧背景都会被编码成可见像素。

校验透明时别看播放器（黑底上永远看不出），把文件放到对比背景上看。VP9 的 `ffprobe` 报 `pix_fmt=yuv420p` 并不代表丢了透明——FFmpeg 默认 VP9 解码器也能把含 alpha 的文件解成不透明。要逐帧看，必须在输入**之前**显式指定 alpha 解码器：

```bash
ffmpeg -c:v libvpx-vp9 -i overlay.webm -frames:v 1 -pix_fmt rgba overlay-frame.png
```

### HLS 输出

`--format hls` 把 HLS VOD 流写进输出**目录**：`master.m3u8`、`video.m3u8` 与 `video_NNNNN.ts`，有音频时还有 `audio.m3u8` 与 `audio_NNNNN.ts`。视频与音频编码与 MP4 完全一致，只是交付容器不同，打包是流拷贝而不是二次编码。

```bash
npx hyperframes render --format hls --output stream
npx hyperframes render --format hls --hls-segment-seconds 6 --output stream
```

分片长度正好是 `--hls-segment-seconds`（默认 4），最后一片除外，每片都从关键帧开始。HLS 只支持 SDR，因此 `--hdr` 被拒；`--gpu` 也被拒，因为 GPU 编码器不遵守定长分片依赖的强制关键帧。云端渲染（`lambda`、`cloudrun`）暂不支持 HLS。

## 输入视频编码

浏览器编辑器、预览、`check` 与已发布项目，通常会给 Chrome 无法可靠解码的本地视频（常见 HEVC、ProRes）建缓存代理。**原始文件留在项目里，并且仍是渲染源**。

如果 clip 只在预览里黑屏：保留自动代理、确认源在本地，然后跑 `npx hyperframes check`。只有浏览器本来就支持该源，或你正在诊断代理本身时，才关掉代理。

## 质量与帧率

默认 `looks`（CRF 16）适合大多数成品。MOV 是剪辑中间格式，默认 ProRes 4444 带 alpha；`--prores-profile` 可换更小的 4:2:2 profile（`hq`、`422`、`lt`、`proxy`），但**没有 alpha**，透明区域会渲染成黑色。`--crf` 与 `--video-bitrate` 对 ProRes 不适用，MOV 会拒绝——需要这些控制就选 MP4 或 WebM。

```bash
npx hyperframes render --format mov --prores-profile hq --output edit.mov
```

交付给播放器或平台时，MP4 还能控制关键帧落点与峰值码率：`--keyframe-interval 2` 至少每两秒一个关键帧；`--max-bitrate 8M` 压峰值（质量目标仍决定其余部分），`--buffer-size`（默认上限的两倍）设上限的测量窗口。不写这些就是原样编码。**在 Lambda 上该间隔是上限**（拼接分片会新增关键帧），且上限按分片生效，拼接时可能瞬间到一整个 buffer。

```bash
npx hyperframes render --keyframe-interval 2 --max-bitrate 8M --output delivery.mp4

# 更快的评审版
npx hyperframes render --quality draft --output review.mp4

# 更大的最终母版
npx hyperframes render --quality high --output master.mp4

# 显式帧率
npx hyperframes render --fps 60 --output final-60fps.mp4
```

只有源或目标需要时才提高帧率——帧更多、渲染更久。CLI 优先用 composition 的 `data-fps`，否则默认 30 fps。

## 4:4:4 母版

MP4 默认按 4:2:0 存色度（两个方向都只有一半分辨率）。细笔画彩色文字、UI 描边、高饱和边缘会发软或描边发虚。文字与 UI 为主的母版用全分辨率色度：

```bash
npx hyperframes render --chroma-subsampling 444 --output master.mp4
```

它编 H.264 High 4:4:4 Predictive（yuv444p），并且每帧按无损 PNG 捕获——因为 Linux 上与截图捕获时 Chrome 的 JPEG 捕获本身就是 4:2:0。代价是慢：上游测试里文字 UI 型 composition 约为默认的两倍，满帧实拍约为三点五倍（PNG 编码随细节变贵）。同 CRF 对比无损母版，彩色文字色度 PSNR 提升约 16 dB、UI 录屏约 13 dB，而实拍与渐变没有明显变化——**实拍为主的视频保持默认**。

`444` 只支持软件编码器的 SDR MP4，与 `--gpu`、`--hdr` 及其它格式互斥；分布式渲染（含 Lambda 与 Cloud Run）只编 4:2:0，会拒绝 `chromaSubsampling: "444"`。

很多播放器解不了 4:4:4，所以它只用于母版与剪辑交接，不要直接发布：

| 播放器 | 4:4:4 MP4 |
| - | - |
| Chrome 155 (Windows) | 可播 |
| Edge 155 (Windows) | 可播 |
| Firefox 157 (Windows) | 不可播（无 YUV 4:4:4 解码器） |
| VLC 3.0 (Windows) | 可播 |
| Windows Media Player / Media Player app | 不可播（"unsupported encoding settings"） |
| Safari (iOS 26.6) | 可播 |
| Safari 26.6 (macOS 26.6) | 可播 |
| QuickTime / AVFoundation (macOS 26.6) | 可播 |

（测试条件：H.264 High 4:4:4 Predictive，8-bit，1920×1080。上表所有播放器都能播默认的 4:2:0 输出。）

## 本地还是 Docker

本地是常态选择，启动快、能用机器上的浏览器 GPU。

```bash
npx hyperframes render --output final.mp4
```

（WSL2 下 Chrome 可能拿不到 GPU，见上游 troubleshooting。）

需要受控的 Chrome、FFmpeg 与字体环境时用 Docker：

```bash
npx hyperframes render --docker --output final.mp4
```

Docker 有启动与基础设施开销，适合 CI 和可复现的生产环境，不是每次最终渲染的必需品。

## 渲染别的 composition

默认渲染根 `index.html`。要指定另一个独立 composition：

```bash
npx hyperframes render \
  --composition compositions/intro.html \
  --output intro.mp4
```

用 `<template>` 包装的嵌套 composition，应当通过包含它的根 composition 来渲染。

## 渲染来源标记

成片容器里带两个元数据标签，标明是哪个工具写的：

```bash
ffprobe -v error -show_entries format_tags -of json out.mp4
```

```json
{ "hyperframes_renderer": "hyperframes", "hyperframes_version": "0.7.107" }
```

只有这些：渲染器名字与版本，**没有**文件路径、用户名、机器名、项目名或任何 composition 信息。它是容器元数据不是水印，画面像素不变（Matroska 读取时会把标签名大写，所以 `.webm` 里是 `HYPERFRAMES_RENDERER`）。

要删掉：

```bash
ffmpeg -i out.mp4 -map_metadata -1 -c copy clean.mp4
```

> 这是**未认证的诊断提示，不能当作来源证明**：普通未签名的容器键，任何工具一条 `ffmpeg -metadata` 就能写同样的值；标签缺失也不能说明什么，重编码、重新封装或任何丢弃未知键的工具都会把它剥掉，旧版本渲染的文件本来就没有。

## 渲染失败时

```bash
npx hyperframes doctor
npx hyperframes lint
npx hyperframes check
```

保留**第一条确切的错误**，而不是只看最后的 "render failed"。后续排查见上游 Troubleshooting。

> 永远亲眼看导出的文件。预览只能证明项目能播，输出文件才证明交付正确。

## 性能：先看症状

成品渲染可以很顺，而预览卡顿——预览必须实时画每一帧，渲染可以花任意长的时间去抓同样的帧。上游给了一组真实数字：同一个 composition 两种写法，1920×1080、300 帧、三次取中位数，**25.0 秒 vs 9.8 秒**，差别只在一个 CSS 声明，逐像素比对确认画面没变。

| 看到的现象 | 先查 |
| - | - |
| 某个场景预览卡 | 该场景里的大模糊、遮罩、阴影，或大量动画层 |
| 图片首次出现时预览停顿 | 源图过大或图片解码 |
| 整页变慢 | 脚本开销、布局抖动、DOM 节点太多 |
| 渲染慢但结果正确 | 源视频抽帧、帧捕获、编码 |
| WebM 比 MP4 慢很多 | VP9 编码；带透明的 WebM 吃 CPU |

### 减少昂贵的浏览器工作

- 少用大面积的 `backdrop-filter` 与 `filter: blur()` 层。
- 避免同时动画几十个带阴影的元素。
- 用预渲染图片替换静态模糊或纹理堆叠。
- 图片按实际交付尺寸准备；很大的 JPEG 仍会解码成很大的位图。
- 动画回调里的工作要小，不要在同一帧里反复读布局又写样式。

1920×1080 的 composition，3840×2160 的源已经够 2× 屏使用，更大的源通常只增加内存与解码开销。

### 测量而不是猜

1. `npx hyperframes preview`
2. 打开 Chrome DevTools 的 **Performance**
3. 录下卡顿的那段
4. 看最长的任务：**Paint** / **Composite Layers** 指向滤镜、阴影、遮罩或大图层；**Layout** / **Recalculate Style** 指向布局工作；**Script** 指向作者代码

改一个昂贵特性再录一次，保留把瓶颈挪走的那个版本。

### 快速评审版

composition 故意做得太重、没法实时播放时，审一个编码后的文件：

```bash
npx hyperframes render --quality draft --output review.mp4
```

交付用 `standard` 或 `high`。draft 只改捕获与编码质量，**不动 composition 的时间**。

### 只在需要时调透明 WebM

WebM 用吃 CPU 的 VP9 编码器，默认值适合大多数工作。想用更多编码时间换压缩质量：

```bash
npx hyperframes render --format webm --vp9-cpu-used 2 --output overlay.webm
```

`--vp9-cpu-used` 接受 -8 到 8 的整数，数值越大越快、质量/体积的折损越大。

## Heron 用在哪

- **渲染闸的判据**是 `hyperframes render` **退出码 0 且产物非空**（`NewsProductionPipeline.renderGate`）。退出码语义：`0` 成功、`1` 跑了但失败、`2` 命令行本身有问题——`2` 通常意味着模板或变量传错了，不是渲染器出了问题。
- **新闻线产出的是 9:16 竖屏**，模板在 `data/templates/news-*-9x16/`；默认 30 fps 与 `looks` 质量足够，调参先动 `--quality draft` 做评审、最后一遍再用默认出片。
- **常用到 `--strict-variables`**：模板声明了变量集合而多传键时会被判错中止，见 [variables.md](variables.md)。
- **渲染慢先看症状表**：新闻模板里大量文字与图表，`first_frame_empty`、`composition_heavy_overlay_count_high` 这类 lint 警告往往就是慢的根因，先修模板再调 `--workers`。
