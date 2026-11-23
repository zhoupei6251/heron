# 画面信号闸：黑场拦、冻结只记

> 口径来源：`docs/2026-11-20-storyboard-and-visual-signal-report.md` §六.2 第 3 条
> 「冻结帧先收一个月量再定阈值」；`docs/plans/2026-11-23-remaining-work.md` 第 2 条欠账。

## 问题

`VideoSignalCheck` 已经用 ffmpeg 算出黑场与冻结段，但 `NewsProductionPipeline.videoSignalGate`
只把它们写进 `checks` 的 `measured`，注释明写「只记不拦」。
理由是「拦下来没有自动修复动作，只会让这条片子白跑一遍渲染」——但闸跑在渲染**之后**，
渲染的钱已经花了；拦下来省的是人工审片的时间，不是渲染成本。

## 做法

| 文件 | 动作 |
| --- | --- |
| `VideoSignalCheck.java` | 新增 `blockingBlack(Result)`：第一段致命黑场，没有就 null |
| `NewsProductionPipeline.java` | `videoSignalGate` 检出致命黑场就抛 `news_video_black_frame`（409） |

口径只有一条：**检出的黑场就是致命的**。

- 检测阈值早就在那儿了（`BLACK_MIN_SECONDS = 1.0`、98% 画面是黑像素、像素黑阈 3%），
  短于此的转场根本不会报上来——所以不需要再拍一个阈值。
- 三套数据风格都是暗底（亮度 13～20/255），阈值是照着真片量出来的，
  2026-11-23 geo 验收片实测「全片没有黑场与冻结段」，不误报。

**冻结帧继续只记**：静帧有时是刻意的表达（静帧叙事、图表定格），
而且报告明确要求先收一个月量再定阈值。冻结数字照旧进 `measured` 与闸的 `reason`，
审稿层据此给结论。

失败信息按仓库规矩写全三样：

- 哪条闸：`news_video_black_frame`（画面信号闸）
- 哪段输入：`成片第 4.0–7.0 秒`
- 为什么拒：`整段纯黑（3.0 秒），画面上没有内容可讲`

## 顺带记渲染成本

`renderGate` 原来只记 `bytes` 与 `exitCode`。现在多记三项：
`renderMs`（渲染墙钟耗时）、`videoSeconds`（成片时长）、`msPerVideoSecond`（每秒画面花多少毫秒）。
没有这组数就定不了渲染预算线，也做不了 2D 与 3D 的成本对照。

## 验证

- 自检 `VideoSignalCheckTest`：6 例全过（有黑场即致命、干净成片无致命黑场、只有冻结不拦）。
- 模块全量测试：707 例全过（`mvn -o -pl ruoyi-modules/ruoyi-heron test`）。
