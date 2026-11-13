# 时间轴与确定性渲染

> 上游：<https://hyperframes.heygen.com/concepts/data-attributes> · <https://hyperframes.heygen.com/concepts/determinism> · 快照：`_upstream/concepts-data-attributes.md`、`concepts-determinism.md` · 版本：hyperframes CLI（npm `^0.9.6`）· 抓取 2026-11-13

HyperFrames 把时间放在 HTML 里。一个定时元素通常需要稳定 ID、开始、时长、轨道：

```html
<section id="headline" class="clip" data-start="0" data-duration="3" data-track-index="1">
  Launch day
</section>
```

| 属性 | 控制什么 |
| - | - |
| `data-start` | 元素进入时间轴的时刻 |
| `data-duration` | 它的时间槽长度 |
| `data-track-index` | 浏览器编辑器用哪条显示道（可选；渲染忽略） |

定时 DOM 与图片元素加 `class="clip"`：运行时按 `data-start` 决定可见性，但共享的 `.clip` 规则负责给它满帧盒子。视频可见性由媒体运行时管；音频没有视觉生命周期。

## 轨道不是层级

track 是浏览器编辑器画的行，既不决定谁在前，也不调度任何东西。前后用 CSS `z-index`。

同一 track 上两个 clip 可以重叠，渲染会把两者合成；有意重叠（比如交叉淡化）放到不同 track，编辑器里才看得清：

```html
<video
  id="intro"
  data-start="0"
  data-duration="4"
  data-track-index="0"
  src="./intro.mp4"
  muted
  playsinline
></video>
<section id="result" class="clip" data-start="intro - 0.5" data-duration="3" data-track-index="1">
  The result
</section>
```

### 让负层级留在父级里

负 `z-index` 是合法 CSS，但父级没建层叠上下文时，一个不透明的祖先背景会把 clip 盖住：

```css
#root {
  position: relative;
  background: #0a0a0a;
}
#backdrop {
  position: absolute;
  inset: 0;
  background: #f5e6c8;
  z-index: -1;
}
```

给父级加一条：

```css
#root {
  isolation: isolate;
}
```

`isolation: isolate` 建局部层叠上下文，背景层在浏览器、`snapshot`、`render` 里都可见，`z-index: 0` 及以上的兄弟仍能盖住它。也可以改用更早的背景兄弟 + 非负 `z-index` 抬高前景。

`hyperframes lint` 的 `negative_z_index` 是**基于 CSS 文本**的警告，祖先已经建了层叠上下文时也会报——以实际可见结果为准，别把每个负值都当错误。

## 相对另一个 clip 开始

数字 `data-start` 是绝对秒数；写 clip ID 表示「那个 clip 结束时开始」，加减秒数用于留空或重叠：

```html
data-start="intro" data-start="intro + 0.5" data-start="intro - 0.5"
```

引用只在同一 composition 内解析，被引用 clip 必须有已知时长，引用链不能成环。

## 媒体与嵌套 composition 的时间

媒体可以再声明源偏移、播放倍率、音量；嵌套 composition 额外声明源路径和自己的固定时间窗。完整属性见 [composition-contract.md](composition-contract.md)，那里有 `data-media-start` 与 `data-playback-start` 两套读取方的坑。

## 确定性渲染

同一份 composition 永远产出同一段视频。这是自动化流水线、CI 测试和 AI 编辑能被信任的前提。

渲染从不「播放」视频：它一次要一帧，而「第 90 帧长什么样」只取决于一个变量——数字 90。

```
Frame 90 ──┐
           ├→ t = 90 / fps（整数运算，绝不读时钟）
Locked before frame 0:     │
  fps、宽、高、variables   ↓
                    所有动画 seek 到精确的 t
                           ↓
                       同样的像素，每次都一样
                      ✗ 一旦某帧读了 Date.now、
                        未播种的 Math.random、
                        或渲染中途 fetch 就会破功
```

规则很短：

- **不读墙上时钟**：不用 `Date.now()`、`requestAnimationFrame`、系统定时器。
- **不用未播种的随机**：`Math.random()` 每次给不同帧。
- **渲染中途不取资源**：所有素材在第一帧之前加载完毕。
- **输出尺寸固定**：`fps`、`width`、`height` 在第一帧前锁定。
- **长度有限**：每个 composition 都有已知终点。

### 每帧发生什么

1. **帧时钟** —— 引擎用整数运算算时间：`time = floor(frame) / fps`，从不参考真实时间。
2. **Seek** —— frame adapter 拿到 `seekFrame(frame)`，把每个动画、DOM 变化、canvas 绘制移到那一刻。所有 GSAP 时间轴都是**暂停后 seek，从不播放**。
3. **捕获** —— Chrome 的 `HeadlessExperimental.beginFrame` 一次原子操作抓像素，不会有画了一半的帧。
4. **编码** —— FFmpeg 把帧编成 MP4，并混入 `<audio>` / `<video>` 的声音。

自写 frame adapter 也必须遵守确定性契约：同一帧的 `seekFrame(frame)` 结果相同，seek 可乱序，帧提交后不再有异步解析，生命周期固定为 `init` → `seekFrame`（多次）→ `destroy`。

用截图捕获时，Chrome 会把变化的 raster tile 整体重画，所以一帧的像素不依赖页面之前画过什么；worker 数、分块边界、seek 顺序都不影响结果。**motion blur 是例外。**

### 把「机器」这个变量排除掉

不同机器的字体和 Chrome 版本不同，本地渲染可能差一个像素。需要精确复现时用 Docker：

```bash
npx hyperframes render --docker --output output.mp4
```

Docker 钉住了 Chromium 版本、字体集和 FFmpeg 编码器，平台不再是输入项。

### 预览和成片一致吗

每一帧都一致：两者跑同一个 `hyperframe.runtime`，producer 的 seek 行为是唯一事实来源，`__playerReady` 与 `__renderReady` 两个闸会压住捕获直到 composition 完全加载。

**速度不一致**：预览是浏览器实时播放，受硬件限制；渲染是 seek 驱动、一帧一帧来，帧再贵也不会丢。预览里卡顿的 composition 渲染出来依然正确。

## Heron 用在哪

- **根 `data-duration` 改了也不会影响变量**：上游明确写了编译器在 composition 脚本运行前就读根时长，脚本或变量覆盖都改不了同一次渲染的值。这正是 `NewsProductionPipeline.timeComposition()` 不走变量、直接在磁盘上补丁时间轴的原因——音频时长算出来之后要改片长，只能改源码里的时间轴。
- **确定性是渲染闸的前提**：`renderGate` 只判「`hyperframes render` 退出 0 且产物非空」，同输入同输出的保证才让这条闸有意义。
- 模板若在脚本里用 `Date.now()` 或未播种随机做动效，会直接破坏这条保证，成片每帧可能不同。
