# 变量（Variables）

> 上游：<https://hyperframes.heygen.com/concepts/variables> · 快照：`_upstream/concepts-variables.md` · 版本：hyperframes CLI（npm `^0.9.6`）· 抓取 2026-11-13

变量暴露 composition 里「本来就该变」的部分：一个客户卡片可以换名字、logo、颜色、套餐，版式和动效不变。**设计要保持稳定时用变量；结构本身要变就改源码。**

## 声明

变量写在 composition 声明上（`data-composition-variables`，JSON 数组）：

```html
<html
  data-composition-variables='[
  {"id":"title","type":"string","label":"Title","default":"Pro"},
  {"id":"accent","type":"color","label":"Accent color","default":"#6c5ce7"},
  {"id":"logo","type":"string","label":"Logo","default":"assets/logo.svg"}
]'
></html>
```

如果是模板或片段而不是整份文档，这串声明写在 `[data-composition-id]` 元素上。

### 七种类型

| Kind | 装什么 | 例子 |
| - | - | - |
| `string` | 文本，或指向文件的链接 | 客户名、视频链接 |
| `number` | 计数、位置、尺寸、强度 | 套餐席位、速度 |
| `color` | 一个已确认的颜色 | 品牌主色 |
| `boolean` | 开或关 | 是否显示徽标 |
| `enum` | 从你批准的列表里选一个 | `light` 或 `dark` |
| `font` | 字体族 | 标题字型 |
| `image` | 图片链接 | 客户 logo |

视频与声音没有独立类型：把链接声明成 `string`，再用 `data-var-src` 绑到 `<video>` / `<audio>`。

### label 与 description 写「含义」而不是「位置」

`label` 和 `description` 是给人也给 agent 读的。「每张 banner 角落里的小署名」只说了位置，槽位含义要靠猜；「本条视频宣传的品牌名」不看 HTML 也能填对。

面向「会被换成别人品牌」的模板，还可以加 `portrays`——列出该槽位代表什么。它是可选元数据、渲染时忽略，用来告诉编辑 agent 哪些槽位承载身份、不能用编造内容填：

```html
{"id":"appName","type":"string","label":"App name",
 "description":"Name of the brand this video promotes, credited in the corner of every banner.",
 "portrays":["subject_name"],"default":"HyperFrames"}
```

| 取值 | 槽位代表 |
| - | - |
| `subject_name` | 被宣传品牌自己的名字 |
| `subject_domain` | 它的域名、URL 或账号 |
| `subject_tagline` | 它自己的定位语 |
| `subject_logo` | 它的标志或字标 |
| `recommended_position` | 榜单里的冠军/推荐位，被宣传品牌要放在这里 |
| `competitor_name` | 被宣传品牌之外的真实产品 |
| `authority_badge` | 背书、榜单权威、评分或来源声明 |
| `host_identity` | 所展示应用自身的身份，remix 时不要动 |

`portrays` 与 `role` 正交：`role` 说明旋钮影响 composition 的哪个方面（`content`、`style`、`timing`、`motion`、`layout`），`portrays` 说明这个值对观众意味着什么。

## 直接绑定（不写脚本）

```html
<h1 data-var-text="title">Pro</h1>

<img data-var-src="logo" src="assets/logo.svg" alt="" />

<style>
  .card-title {
    color: var(--accent);
  }
</style>
```

- `data-var-text` 替换元素自身的文本。
- `data-var-src` 替换 image / video / audio / source 的 URL。
- 标量变量同时以 CSS 自定义属性形式可用，如 `var(--accent)`。

只有在需要条件、循环或派生值时才用 `window.__hyperframes.getVariables()`：

```js
const { featured = false } = window.__hyperframes.getVariables();
document.querySelector(".badge").hidden = !featured;
```

## 换视频或换声音

用 `data-var-src` 绑到媒体元素，元素上保留一个 `src` 作为占位：

```html
<html
  data-composition-variables='[
  {"id":"welcomeClip","type":"string","label":"Welcome clip","default":"assets/placeholder.mp4"}
]'
>
  <video
    data-var-src="welcomeClip"
    src="assets/placeholder.mp4"
    data-start="0"
    data-track-index="0"
    playsinline
  ></video>
</html>
```

同样一份工程，按账号各渲染一次：

```bash
npx hyperframes render \
  --variables '{"welcomeClip":"https://example.com/clips/northstar.mp4"}' \
  --output northstar.mp4
```

成片取的是你传入 clip 的长度和声音，不是占位的：3 秒占位换成 5 秒 clip 就渲染 5 秒，且该 clip 的声音只混一次。

限制：

- `data-var-src` 只对 `<video>`、`<audio>`、`<img>`、`<source>` 生效，其它元素忽略。
- 链接必须是路径或 `http`、`https`、`blob` URL。其它值（含 `data:` 视频）会被丢弃、渲染占位并给警告。
- 收到自己 `data-variable-values` 的嵌套 composition 里的 clip，由浏览器在渲染期解析，而不是渲染前。
- **根 composition 的长度跟随它播放的 clip，不是变量。**

## 给同一个嵌套 composition 传不同值

```html
<div
  data-composition-id="card-pro"
  data-composition-src="compositions/card.html"
  data-start="0"
  data-duration="3"
  data-track-index="1"
data-variable-values='{"title":"Pro","accent":"#ff4d4f"}'
></div>

<div
  data-composition-id="card-enterprise"
  data-composition-src="compositions/card.html"
  data-start="card-pro"
  data-duration="3"
  data-track-index="1"
data-variable-values='{"title":"Enterprise","accent":"#22c55e"}'
></div>
```

两个实例共用一份源码、各自拿到不同内容；`data-start="card-pro"` 表示「前者结束后开始」。子 composition 内部用 `window.__hyperframes.getVariables()` 读取，它会把宿主传的值**逐份**覆盖在声明的默认值之上。

## 从 CLI 传入

```bash
npx hyperframes render \
  --variables '{"title":"Enterprise","accent":"#22c55e"}' \
  --strict-variables \
  --output enterprise.mp4
```

- `--variables-file` 传 JSON 文件。
- `--batch` 让同一 composition 按数据行各渲染一次。
- **`--strict-variables` 把「未声明或类型不对」的渲染值变成错误**。

### 批量渲染

一行一个变量对象，用行内字段做输出文件名占位。批量文件支持 UTF-8（带或不带 BOM）或带 BOM 的 UTF-16LE。

```json
[
  { "name": "acme", "title": "Acme Pro" },
  { "name": "northstar", "title": "Northstar Pro" }
]
```

```bash
npx hyperframes render \
  --batch rows.json \
  --strict-variables \
  --output "renders/{name}.mp4"
```

先用默认的单行并发；只有在一次真实渲染稳定、且机器内存够同时跑多个渲染之后，才调大 `--batch-concurrency`。

## 什么不能做成变量

变量只改 composition 内部的内容，改不了：

- composition 视口；
- 根 composition 的总渲染时长；
- 帧率；
- 输出格式、编码器、质量；
- 父级或兄弟 composition（除非显式传值给它）。

这些在 composition 逻辑运行之前就从源码或渲染设置里读定了。

## 检查契约

```bash
npx hyperframes lint
```

lint 抓声明格式错误、缺字段、默认值类型不对、非法枚举值；`--strict-variables` 把未声明或类型错误的渲染值变成错误。

## Heron 用在哪

`NewsProductionPipeline.templateVariables()` 生成一份 JSON，模板用 `data-var-text` / `data-var-src` / `data-var-src`（如 `scene1_image`）声明取值。关键约束：

- 当模板声明了变量集合（`declared` 非空）时，Heron **只输出声明过的键**（`vars.keySet().retainAll(declared)`）。多给的键会被 `--strict-variables` 判成未声明，直接中止渲染。
- 模板为每个场景都声明了 `data-var-src`，所以无图场景也要给**空串**，让模板的 `has-image` 判断保持关闭，而不是缺键。
- 根时长不走变量——`timeComposition()` 直接在磁盘上改时间轴，原因见 [timing.md](timing.md)。

所以改模板变量时，`templateVariables()` 和模板里的 `data-composition-variables` 必须同步改，任何一边多了或少了都会在渲染闸前失败。
