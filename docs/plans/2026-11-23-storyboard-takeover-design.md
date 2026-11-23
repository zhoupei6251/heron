# 冲击屏：headline 屏接管整幅画面

> 口径来源：`docs/2026-11-20-storyboard-and-visual-signal-report.md` §六.2 第 1 条
> 「冲击屏。某一屏是 headline 时，让图表与常驻标题让位，这一屏独占画面。这是分镜最大的未兑现价值」。
> 契约来源：`prompt/data-board/script-contract.md`「`headline`：一句话立论或转折，正文全部让位给这一句」。

## 问题

数据线有常驻三块：选题标题与 KPI（`#title-card`）、图表（`#chart-slot`）。
分镜只做到「这一屏用什么画面」，没做到「这一屏接管整个画面」：headline 屏在版式里
只是把领句放大（`storyboard.css` 的 `.scene[data-scene-type="headline"] .lead`），
图表和标题卡照旧占着画面——冲击屏一直没兑现。

## 做法

纯模板层，不动后端：

| 文件 | 动作 |
| --- | --- |
| `data/templates/_gen/behaviors/headline.js` | 新增。取 headline 屏窗口 → 淡出 `#title-card`/`#chart-slot` → 给当前 headline 槽位加 `takeover` 类 |
| `data/templates/_shared/storyboard.css` | 新增 `.scene.takeover`：满幅、居中、撤掉版式装饰与正文，领句顶到标题档 |
| `data/tools/build-templates.mjs` | `script(cfg)` 给分镜模板带上 `headline.js` |

两条硬约束：

1. **不另建时间轴**。窗口取渲染期写回的 `slot.dataset.start/duration`（Java 侧按口播对齐后改写），
   多一条时间轴就会和口播对不齐。
2. **接管状态按帧算**。用 `tl.to(clock, {onUpdate})` 反推 `tl.time()`，
   不能用 `tl.call` 逐屏写——按帧 seek 时回调会一次跑完（与 `focus.js`、`motion.js` 的 `#tick` 同理）。

## 边界

- 只对声明了分镜的模板生效（`template.config.json` 的 `storyboard: true`，目前 5 个数据模板）。
  文稿线 14 个模板没有分镜样式，冲击屏也不进。
- 首屏就是 headline 时，片头 0 秒起标题卡就让位：这一屏是冷开场，不是漏渲染。
- 字号按「排不排得下一行」实测降档，不按字数猜——各风格的 `--fs-title` 与可用宽度都不同
  （调查线 100px 窄边距，播报线 86px 通栏），按字数猜迟早在某个风格上挤成孤字。
- 常驻层放回时间收敛到片长以内：越界的补间会把时间轴拖长，渲染帧数跟着变。

## 验证

- 自检：`data/tools/test-headline-takeover.mjs`（node:test + node:vm 沙箱），9 例全过。
- 成片：`data/work/verify-takeover/raw.mp4`（复用 2026-11-23 geo 验收片的工作目录，只换模板产物），
  57.87s / 1080×1920 / h264+aac。
- 逐帧 SSIM（新片 vs 2026-11-23 验收成片）：只有 0.57–5.87s 低于 0.9，正是冲击屏窗口；
  其余帧中位数 0.984（差值来自三维地球的动画与编码噪声）——除冲击屏外画面未变。
- 五个数据模板各抽一帧（`hyperframes snapshot --at 3`）：都是满幅、单句、无残留装饰。
