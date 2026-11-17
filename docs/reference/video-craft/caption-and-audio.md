# 字幕、音频与调色

来源：`media-use/audio/references/captions/*`、`media-use/references/grading.md`、`hyperframes-audio/SKILL.md`。

## 字幕：能量分档

先判断旁白的能量，再选技术组合。**所有档位都以 Karaoke（逐词高亮）为基线**，差别在强度。

| 能量 | 高亮 | 出场 | 循环 |
| - | - | - | - |
| 高 | Karaoke + 强调色光晕 + 15% 缩放弹跳 | 散开 / 坠落 | 每 2 组换一次高亮样式 |
| 中高 | Karaoke + 颜色弹出 | 散开 / 收拢 | 每 3 组换一次 |
| 中 | Karaoke（纯白，克制） | 淡出 + 滑动 | 每 3 组换一次 |
| 中低 | Karaoke（极小幅缩放） | 淡出 | 单一样式，每组换缓动 |
| 低 | Karaoke（暖色调，慢过渡） | 收拢 | 每 4 组换一次 |

**强调词永远打破规律**：情绪关键词、全大写、品牌名，给比周围更重的动画（更大的缩放、强调色、过冲缓动），靠对比制造重音。

**标注类高亮叠在 Karaoke 之上**：荧光笔横扫、圈选、爆点、涂鸦、速写。能量高用爆点，关键术语用圈选，常规用荧光笔，克制的用涂鸦。

不要在同一组、同一时刻叠多个互相竞争的动画。不同组之间换技术，跟着内容节奏走。

## 字幕的分组与定位

**分组按能量**：高能量 2–3 词，快换；对话感 3–5 词，自然短语；克制/沉稳 4–6 词，组更长。在句子边界、150ms 以上停顿、或字数上限处断开。

**定位**：

- 横屏 1920×1080：距底 80–120px，居中
- **竖屏 1080×1920：距底约 600–700px，居中**（不是贴着最底边——那是平台 UI 和评论按钮的地盘）
- 永远不要盖住人脸；`position: absolute`，不要 relative
- 同一时刻只显示一组

**防溢出**用 `window.__hyperframes.fitTextFontSize(text, { fontFamily, fontWeight, maxWidth })` 量出能放下的字号，别写死——中文长句和英文长单词的溢出方式不一样。

**逐词样式**：品牌/产品名放大+独有色；全大写加缩放/闪；数字统计用粗体+强调色；情绪关键词用过冲/弹跳；CTA 高亮或下划线。

**按语气选风格**：

| 语气 | 字体感觉 | 动画 | 色彩 | 字号 |
| - | - | - | - | - |
| 高燃/发布 | 重压缩 800–900 | Scale-pop，`back.out(1.7)`，0.1–0.2s | 亮色压暗底 | 72–96px |
| 企业 | 干净无衬线 600–700 | 淡入+滑动，`power3.out`，0.3s | 白/中性+低饱和强调 | 56–72px |
| 教程 | 等宽/干净无衬线 500–600 | 打字机/淡入，0.4–0.5s | 高对比、极简 | 48–64px |
| 叙事 | 衬线/优雅 400–500 | 慢淡入，`power2.out`，0.5–0.6s | 暖低饱和 | 44–56px |
| 社媒 | 圆润无衬线 700–800 | 弹跳，`elastic.out`，逐词 | 活泼、彩色胶囊 | 56–80px |

Heron 数据看板线落在「企业」到「教程」之间：干净无衬线 + 淡入滑动，字号按竖屏那档往上抬。

## 音频反应（音乐必须）

**源音频是音乐时，必须做音频反应。** 音乐没有反应会显得和画面脱节。即使低能量的抒情曲，也该有轻微的低频脉动和高频泛光。

做法是构建期整形，不是播放期回调：读出每组时间段内的低频/高频峰值，用它调制这一组的入场强度。

```js
var AUDIO = JSON.parse(audioDataJson); // { fps, totalFrames, frames: [{ bands }] }

GROUPS.forEach(function (group, gi) {
  var startFrame = Math.floor(group.start * AUDIO.fps);
  var endFrame = Math.min(Math.floor(group.end * AUDIO.fps), AUDIO.totalFrames - 1);
  var peakBass = 0, peakTreble = 0;
  for (var f = startFrame; f <= endFrame; f++) {
    var frame = AUDIO.frames[f];
    if (!frame) continue;
    peakBass = Math.max(peakBass, frame.bands[0] || 0, frame.bands[1] || 0);
    peakTreble = Math.max(peakTreble, frame.bands[6] || 0, frame.bands[7] || 0);
  }
  tl.to(groupEl, { scale: 1 + peakBass * 0.06,
    textShadow: "0 0 " + Math.round(peakTreble * 12) + "px rgba(255,255,255," + peakTreble * 0.4 + ")",
    duration: 0.3, ease: "power2.out" }, group.start);
  tl.set(groupEl, { scale: 1, textShadow: "none" }, group.end - 0.15);
});
```

- 音频数据决定「多少」，内容决定「什么」。
- **保持克制**：3–6% 的缩放变化 + 柔和光晕。重了就看不清字了。
- 数据用 `extract-audio-data.py audio.mp3 --fps 30 --bands 8 -o audio-data.json` 生成后内联。

## 字体与字幕的关系

字幕是**大字号标题的一部分**，不是网页式小注解。参考 [frame-design.md](frame-design.md) 的尺寸表：竖屏数据标签 ≥24px，正文 ≥32px。

字幕底衬要克制——一块半透明黑条会让画面立刻变得廉价。优先用文字本身的描边/阴影/对比色解决可读性。

## 调色

调色是唯一推荐走 CLI 而不是手写 HTML 的环节——手写 CSS 滤镜拼不出真正的 LUT。

```bash
npx hyperframes media-use resolve --type grade --intent "warm daylight" --project . --json
```

返回预设后应用到具体元素：

```bash
hyperframes media-treatment --project . --file index.html --selector '#hero' \
  --grading '{"preset":"warm-daylight","intensity":1}' --apply --json
```

- 作用域不确定时先加 `--dry-run`；`--clear` 撤掉。
- **永远不要 `cat` 一个 `.cube` 文件**——33³ 约 3.6 万行纯数字，污染上下文且对人类和模型都没有信息量。要看效果用 `hyperframes grade-compare` 渲染对比，要看描述读 `luts/index.json`。
- 参数化数学（`buildCube`）复现不了真实胶片或乳剂特性，那种要真实的扫描 LUT。

可动画的调色属性（只对这些写 tween，且必须用注册过的 paused timeline）：

| 属性 | 范围 |
| - | - |
| `--hf-color-grading-intensity` | 0–1 |
| `--hf-color-grading-lut-intensity` | 0–1 |
| `--hf-color-grading-exposure` | -2–2 |
| `--hf-color-grading-blur` | 0–1 |
| `--hf-color-grading-bloom` | 0–3 |
| `--hf-color-grading-kuwahara` | 0–1 |
| `--hf-color-grading-pixelate` | 0–1 |
| `--hf-color-grading-ascii` | 0–1 |
| `--hf-color-grading-dither` | 0–1 |

初值写在元素的 inline style 里，然后用有限的 `tl.to()` 关键帧。**不要帧零 `tl.set()`、不要 CSS animation 时钟、不要定时器、不要随机值、不要 `onUpdate` 回调**——这些都会破坏确定性渲染。

> 对数据看板线，调色是可选打磨项，不是必需环节。数据类片子靠构图和排版立住，不靠 LUT。

## 混音

HyperFrames 的混音观：**混音是一组关系，不是处理器的堆叠。** 两条单独听都对轨，合起来可能没法听——解决办法几乎从来不是「把其中一条调小」，而是找出它们争夺的是什么，然后把它判给更需要的那条。

- 旁白压过音乐床用 carve（动态侧链），不是把 BGM 音量硬拉低。
- 效果链写在元素的 `data-fx-chain` 上，预览和渲染跑同一套 Web Audio 图，所以**听到的就是写出来的**，不用调两遍。
- 内置效果族：gain、EQ（高通/低通/峰值/搁架）、compressor、limiter、gate、saturate、delay、reverb、chorus、phaser、bitcrush。

Heron 当前的音频链路只到「配音 + BGM」这一层，carve 与效果链属于需要时再引入的能力，原包 `hyperframes-audio/references/presets.md` 有「症状→修复」对照表，先查那里再手搭。
