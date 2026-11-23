# compare 屏做成真正两栏对照

> 口径来源：`docs/2026-11-20-storyboard-and-visual-signal-report.md` §六.2 第 2 条
> 「compare 做成真正两栏，配合 DatasetFacts 已有的首末/领先者数据，不需要新数据源」。

## 问题

compare 屏原来只有「正文垫一层面板」——对照关系写在一句话里
（「数值居首的一条记录达到 6.8 级……和首条比，差距一眼可见」），画面上并没有两栏可比。

## 做法

纯模板层，不动后端、不加数据源：

| 文件 | 动作 |
| --- | --- |
| `data/templates/_gen/behaviors/compare.js` | 新增。按事实挑出要对照的两项，建成 `.compare-pairs` 两栏 |
| `data/templates/_shared/storyboard.css` | 新增 `.compare-pairs` 样式；`has-compare` 时正文让位 |
| `data/tools/build-templates.mjs` | `script(cfg)` 给支持 compare 的模板带上 `compare.js` |

数据口径由 Java 侧 `DatasetFacts` 决定（`wide` / `long` / `rows` / `geo` 四种 shape），
模板这边只按事实取数：

| shape | 对照的两项 |
| --- | --- |
| `rows` / `geo` | 首条 vs 最高 |
| `wide` | 起点 vs 最新 |
| `long` | 同一对象的起点 vs 最新 |

三条补充规则：

1. **首条就是最高时**（「首条 vs 最高」塌成同一个对象）退到 **最高 vs 最低**——
   对照屏总要有个对照，不能空着。
2. **焦点指了两个对象就照焦点来**（`sceneN_focus.labels` 命中两个对象时用它们），
   这样同一屏里模型想比谁，屏幕上就比谁。
3. **事实不够就不画这一栏**：正文照旧，不摆空框。

中间一栏写「vs」与两者的差值（`差 2.1`），差值来自同一份事实，不是另算的一套数。

## 边界

- 字号按这一栏实际能放下的宽度降档（`--fs-title-sm` → `--fs-lead` → `--fs-body`），
  不按字数猜——各风格的栏宽与字号都不同。
- 进出场借 `motion.js` 的 `DUR` / `EASE` 与同一条时间轴，跟着所在屏走；
  所以 `compare.js` 排在 `motion.js` 之后（构建脚本里的顺序是硬约束）。
- 只给 `supportedSceneTypes` 含 `compare` 的模板（五个数据模板）；文稿线没有 compare 屏。

## 验证

- 自检：`data/tools/test-compare-pairs.mjs`（node:test + node:vm 沙箱），6 例全过：
  快照类取首条与最高、首条即最高时退回最高与最低、区间类取起点与最新、
  焦点指两个对象时照焦点、没有事实就不画、不是 compare 屏不动它。
- 渲染：`data/work/verify-takeover/`（复用 2026-11-23 geo 验收片的工作目录，只换模板产物）。
  该片第 3 屏与第 6 屏都是 compare 屏，抽帧见 `docs/acceptance-2026-11-23/compare-acceptance.md`：
  t=22s 与 t=50s 两处两栏都出得来（首条 4.7 / 最高 6.8 / 差 2.1），正文让位。
