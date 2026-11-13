# GSAP 动画契约

> 上游：<https://hyperframes.heygen.com/guides/gsap-animation> · 快照：`_upstream/guides-gsap-animation.md` · 版本：hyperframes CLI（npm `^0.9.6`）· 抓取 2026-11-13

GSAP 是 HyperFrames composition 的主要动画运行时。**你写运动，HyperFrames 拥有播放头。**

## 最小契约

给 composition 一个有限时长，建一个 paused 时间轴，用 composition ID 注册它：

```html
<!doctype html>
<html lang="en">
  <head>
    <script src="https://cdn.jsdelivr.net/npm/gsap@3/dist/gsap.min.js"></script>
  </head>
  <body>
    <div
      id="root"
      data-composition-id="intro"
      data-start="0"
      data-duration="3"
      data-width="1920"
      data-height="1080"
    >
      <h1 id="title" class="clip" data-start="0" data-duration="3" data-track-index="0">
        HyperFrames
      </h1>
    </div>

    <script>
      const timeline = gsap.timeline({ paused: true });

      timeline.fromTo(
        "#title",
        { opacity: 0, y: 32 },
        { opacity: 1, y: 0, duration: 0.6, ease: "power3.out" },
        0,
      );

      window.__timelines = window.__timelines || {};
      window.__timelines.intro = timeline;
    </script>
  </body>
</html>
```

注册键必须与 `data-composition-id` 一致。

## 六条规则

1. 时间轴必须用 `{ paused: true }` 创建。
2. 注册到 `window.__timelines`。
3. 重要 tween 给显式位置参数。
4. 两个端点都重要时优先 `fromTo()`——它在向后或随机 seek 之后依然可靠。
5. 用 transform 与 opacity 做位移；避免反复动画 `top`、`left`、`width`、`height` 这类布局属性。
6. clip 可见性与媒体播放交给 HyperFrames。

常用方法是 `to()`、`from()`、`fromTo()`、`set()`。GSAP 支持大量 CSS 属性，但 agent 用的渲染安全模式见上游的 animation skill。

## 量文字之前先加载字体

下划线、按文字宽度算出的位置都需要最终字体度量。HyperFrames 会等已有标记用到的字体，但**只被脚本创建的文字用到的字面可能还没加载**。

需要先用 `document.fonts.load()` 显式请求，匹配文字的字体族、字重、字号，并把文本传进去以便加载所需字符区间。`document.fonts.ready` 只等已请求的字体，不会去请求一个没被用过的字面。

```js
const title = document.getElementById("title");
const underline = document.getElementById("underline");
title.textContent = "Build with HyperFrames";

document.fonts.load('700 64px "LocalFace"', title.textContent).then(() => {
  underline.style.width = `${title.getBoundingClientRect().width}px`;

  const timeline = gsap.timeline({ paused: true });
  timeline.fromTo(
    ["#title", "#underline"],
    { opacity: 0, y: 32 },
    { opacity: 1, y: 0, duration: 0.6, ease: "power3.out" },
    0,
  );

  window.__timelines = window.__timelines || {};
  window.__timelines.intro = timeline;
});
```

在回调**末尾**、所有 tween 建完之后再注册。异步构建完成前注册一个空时间轴，会让嵌套 composition 在动画还不存在时就显得已就绪。

## 时长

显式的根 `data-duration` 就是渲染长度：

```html
<div data-composition-id="demo" data-duration="12"></div>
```

完整 composition 用显式时长最清楚。根省略 `data-duration` 时，HyperFrames 可以在页面初始化后从已注册时间轴与支持的媒体推断长度。

> 别指望长源视频会自动拉长一个更短的显式根时长。**作者写的时长就是输出窗口。**

## 媒体归运行时管

不要从 GSAP 时间轴里播放或 seek 媒体：

```js
// Do not do this.
document.querySelector("video").play();
document.querySelector("audio").currentTime = 5;
```

用媒体的时间属性。视频需要移动或缩放时，动画它外面的 wrapper，不要动画 video 元素本身。

## 嵌套 composition

每个嵌套 composition 自己拥有并注册自己的时间轴；父级通过宿主元素决定它何时出现：

```html
<div
  data-composition-id="intro"
  data-composition-src="compositions/intro.html"
  data-start="2"
  data-duration="4"
></div>
```

**不要手动把子时间轴加进父 GSAP 时间轴**——HyperFrames 负责把父播放头映射进嵌套场景。

## 验证运动

```bash
npx hyperframes lint
npx hyperframes check
npx hyperframes snapshot --at 0,0.6,2.9
```

看首帧、运动中的状态、末状态。只在浏览器连续播放时看着对的动画，渲染器直接 seek 到某一帧时仍可能出错。

## Heron 用在哪

模板里的动效全部走 `gsap.timeline({ paused: true })` + `window.__timelines[...]` 这套契约，`data/tools/package.json` 里钉的是 `gsap ^3.14.2`。改模板动效时守住两条：

- 不要写 `Date.now()`、未播种 `Math.random()`——会破坏确定性渲染，成片每帧可能不同。
- 不要用脚本改媒体播放状态；音画同步由 `data-start` / `data-duration` / `data-media-start` 决定，Heron 侧只按音频时长补丁时间轴。
