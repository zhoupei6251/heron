# 数据呈现与动效

来源：`hyperframes-creative/references/data-in-motion.md`；`hyperframes-animation/rules/` 下的 `counting-dynamic-scale.md`、`stat-bars-and-fills.md`、`chart-scrub-readout.md`。

Heron 数据看板线的画面主体是图表和数据，这一篇是最直接可用的部分。

## 五条铁律

- **不要饼图**——难比较，且像 PPT。
- **不要多轴图**——观众只有 3 秒，来不及研究交点。
- **不要六宫格看板**——2–3 个相关指标并排可以，6 个以上是网页习惯。
- **不要网格线、刻度、图例**——在运动中只是噪音。
- **不要图表库输出**——用 GSAP + SVG/CSS 手搭，不用 D3 或 Chart.js。

## 数字需要视觉重量

**一个孤零零的数字会飘在空白里。** 每个指标都要配一个给它「存在感」的视觉元素：按比例填充的条、背景色块、代表数值的形状、进度环。不一定是图表，但必须占住画面，让数据摸得着，而不只是背景上的一行字。

## 视觉连续性

同一概念下连续出现的指标（Q1→Q2→Q3，或同一产品的三个指标），保持在**同一个视觉空间、同一套审美**里，只让数值变。

审美的变化应该标志「换了一个概念」，而不是「换了一个数」。

## 布局：选一种，全程别换

- **单一聚焦**：一帧居中，数字是主角，环或条在下方/四周。适合依次揭示（指标 1 → 2 → 3 用同一个画面）。
- **分屏**：大数字在左，配图在右。适合同时展示多个指标，或每个指标需要不同视觉。

**同一条片子里不要在不同指标之间来回换布局**，读起来就是不一致。

## 数字滚动

数值从 A 数到 B，同时 `transform: scale` 从初始尺寸涨到最终尺寸——制造升级感，又不逐帧重排文本。

两条同步 tween 用**同一个时间位置和同一个缓动**：一条驱动数值写文本，一条驱动 scale。后缀（%、×、+）在数字落定**之后**滑入；标签提前淡入。

```css
.counter-wrap { display: flex; align-items: baseline; width: {固定宽度}; }
.counter {
  font-variant-numeric: tabular-nums;   /* 强制 */
  display: inline-block;
  font-size: {终值字号};                 /* 静态，不 tween */
  transform-origin: center center;
}
```

```js
tl.to(state, { value: TARGET, duration: COUNT_DUR, ease: COUNT_EASE,
  onUpdate: () => counter.textContent = Math.round(state.value).toLocaleString() }, 0);
tl.fromTo(counter, { scale: START_SIZE / END_SIZE }, { scale: 1,
  duration: COUNT_DUR, ease: COUNT_EASE }, 0);
tl.to(".counter-suffix", { opacity: 1, y: 0, duration: 0.3, ease: "back.out(1.7)" }, COUNT_DUR);
```

参数：

| 项 | 范围 | 说明 |
| - | - | - |
| 目标值 | 2–3 位最佳 | 4 位以上要加宽容器，终值字号下不能裁切 |
| 起始缩比 | 终值的 40–60% | 只用来算 `START_SCALE`，永远不 tween `font-size` |
| 计数时长 | 1.2–2.5s | 低于 0.8s 就是一闪而过，眼睛读不到滚过的数字 |
| 缓动 | `power3.out` / `expo.out` | 越 out 越有收势；值和 scale 共用 |
| 后缀 | 0.3–0.6s | 在计数结束后触发，不要在计数中 |
| 标签 | 在计数过半前到，0.4–0.7s | 标签是**大字号标题的一部分**，不是网页式的小注解 |

硬约束：

- `tabular-nums` + 固定宽度容器，缺一个，9→10→100 时数字就会抖。
- `onUpdate` 里只写文本，不写样式、不建 DOM，保持 O(1)。
- 用 `Math.round` 不用 `Math.floor`——落到最后一个整数时应该已经显示终值。
- 计数器本身**不要 `back.out` / `elastic.out`**——过冲会让数字看起来不稳（这是数据，不是装饰）。原地长大。

## 条形 / 填充 / 星级

| 形式 | 做法 |
| - | - |
| 增长条 | CSS 写好每条高度，GSAP 只 `scaleY: 0 → 1`，`transform-origin: bottom`，`stagger 0.06–0.1s`，最后一条用强调色 |
| 进度条 | `scaleX` 从左边长，`transform-origin: left`；`.fill` 必须 `width: 100%`，否则 0 宽乘任何 scale 还是 0，整个条不可见 |
| 进度环 | `getTotalLength()` 量周长再算 `strokeDashoffset`，别硬编码；`<circle>` 在 CSS 里转 -90° 让起点在 12 点 |
| 星级 | 灰层上叠金层，`clip-path: inset(0 X% 0 0)` 擦到小数分值 |

- 条数 4–6 条读作「一个趋势」，再多就是噪音；最后一条代表当前值。
- **永远 tween `transform` / `clip-path`，不 tween `height` / `width`。**
- 图形和数字必须**同起点、同缓动、同时落定**——一对数据是一个节拍，不是两个。

## 图表刮擦读数

图表已经在屏幕上，这一条是「审问」它：一条竖向跟踪线随播放头扫过，标记点贴着折线走，tooltip 读出 `日期: 数值`，数字像里程表一样跳。这是「这数据是真的，你凑近看」那一拍。

做法要点（完整配方见原包 `rules/chart-scrub-readout.md`）：

1. **数据在 setup 时写死**成字面量数组，折线的 `points` 由纯函数一次性算出——图表和读数共用一个真相源。每次渲染随机生成，既破坏确定性，也拆了「数据是真的」这个论点。
2. **一个驱动量 `p: 0 → 1`**，跟踪线 x、标记点 x/y、tooltip 位置全部在它的 `onUpdate` 里由 `p` 推导。任何 seek 都落到同一帧。并行的多条 tween 只共享时间，舍入后会漂，读起来像图表配件而不是读数头。
3. **标记点必须沿折线走**：在相邻两个已烘焙点之间插值。单独打关键帧的标记点一定会飘离折线。
4. **读数是按索引跳变的**：最近索引变了才写 `textContent`（索引守卫）。位置逐帧滑（合成器便宜），文字按数据点跳——不逐帧刷 DOM。
5. **落定要停住**：驱动结束后至少留 0.8s，让最终值被读走；或交给数字滚动接棒。

参数：数据点 10–40（少于 10 像幻灯片，多于 40 糊成纹理）；刮擦 1.5–3s；`power1.inOut` 为默认，播放头形态用 `none`，峰值停靠用 `power3.out`；**永远不要 `back.out`**——读数头过冲再回读看着像坏了。

## 数据场景的通用节奏

图表动效天然可以串联：折线画进来（`svg-path-draw`）→ 刮擦读数审问它 → 落定值交给数字滚动收尾。三段是三个节拍，不要糊成一个。
