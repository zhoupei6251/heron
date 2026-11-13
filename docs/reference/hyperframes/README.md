# HyperFrames 参考文档（本地蒸馏版）

本目录是对上游 HyperFrames 官方文档的本地蒸馏，供 Heron 后端做新闻视频渲染时离线查阅，不作为项目规范。
上游地址：<https://hyperframes.heygen.com/>

HyperFrames 的核心理念：**一个文件夹的 HTML 就是整段视频**。agent、浏览器编辑器、CLI、SDK、Player 读写同一份文件，中间没有导出步骤，也没有第二份工程副本。

## 版本基线

| 项 | 值 |
| - | - |
| 上游文档 | <https://hyperframes.heygen.com/>（`/_llms/docs.txt` 是 135 页的全站索引） |
| 仓库依赖版本 | `hyperframes ^0.9.6`、`gsap ^3.14.2`（见 `data/tools/package.json`） |
| 抓取日期 | 2026-11-13 |
| 抓取方式 | `curl` 拉取上游 `.md` 原文，逐页蒸馏 |

> CLI 的权威永远是**已安装的版本**：`npx hyperframes <command> --help`。本目录记录的是 `^0.9.6` 时的行为。

## 文件清单

| 文件 | 内容 | 上游页面 |
| - | - | - |
| [composition-contract.md](composition-contract.md) | 项目结构、composition 根属性、定时 clip、媒体属性、嵌套 composition、颜色分级 | [concepts/index](https://hyperframes.heygen.com/concepts/index) · [reference/html-schema](https://hyperframes.heygen.com/reference/html-schema) · [concepts/compositions](https://hyperframes.heygen.com/concepts/compositions) |
| [variables.md](variables.md) | 变量七种类型、`data-var-text`/`data-var-src`/CSS 变量绑定、嵌套传值、CLI 覆盖与批量、`--strict-variables` | [concepts/variables](https://hyperframes.heygen.com/concepts/variables) |
| [timing.md](timing.md) | 时间轴属性、轨道不等于层级、相对时间、确定性渲染的约束与 Docker 复现 | [concepts/data-attributes](https://hyperframes.heygen.com/concepts/data-attributes) · [concepts/determinism](https://hyperframes.heygen.com/concepts/determinism) |
| [animation-gsap.md](animation-gsap.md) | paused timeline 契约、六条规则、字体加载时序、嵌套时间轴 | [guides/gsap-animation](https://hyperframes.heygen.com/guides/gsap-animation) |
| [cli.md](cli.md) | 公共约定（退出码、`--json`、无值守）、`lint`/`check`/`snapshot`/`keyframes`、`render` 全量 flag、`preview`、`timeline` | [packages/cli](https://hyperframes.heygen.com/packages/cli) · [developers/cli](https://hyperframes.heygen.com/developers/cli) |
| [rendering.md](rendering.md) | 格式选择、透明视频、HLS、质量与 4:4:4 母版、Docker、来源标记、渲染失败排查、性能 | [guides/rendering](https://hyperframes.heygen.com/guides/rendering) · [guides/performance](https://hyperframes.heygen.com/guides/performance) |
| [lint-rules.md](lint-rules.md) | 全部 144 条 lint 规则：代码 / 级别 / 抓什么 / 怎么修 | [reference/lint-rules](https://hyperframes.heygen.com/reference/lint-rules) |

`_upstream/` 存放上述 12 页的**上游原文快照**，只读，不要本地编辑。它存在的目的只有一个：升级时能和上游新版本做 `diff`。

## 升级流程

```bash
cd heron/docs/reference/hyperframes
for p in concepts/index concepts/compositions reference/html-schema \
         concepts/variables concepts/data-attributes concepts/determinism \
         guides/gsap-animation packages/cli developers/cli \
         guides/rendering reference/lint-rules guides/performance; do
  n=$(echo "$p" | tr '/' '-')
  curl -sSL "https://hyperframes.heygen.com/${p}.md" -o "_upstream/${n}.md"
done
git diff --stat -- _upstream/
```

1. 先看 `git diff _upstream/`，确认上游改了哪几条规则、哪些 flag。
2. 按 diff 改对应的蒸馏文件；**`lint-rules.md` 要逐条核对**——新增或改名的规则代码不补上，`hyperframes check` 的报错就查不到解释。
3. 同步更新本 README 的「版本基线」表，以及 `data/tools/package.json` 里的 `hyperframes` 版本。

上游全站索引：<https://hyperframes.heygen.com/llms.txt>（docs 135 页 + catalog 449 页）。本目录只收「检查 + 渲染」这条链路；素材采集（`capture`、`transcribe`、`tts`、`background removal`）、云端渲染（Lambda / Cloud Run）、Studio 与账号相关页面按需再补。

## Heron 用在哪

新闻视频生产线（`ruoyi-modules/ruoyi-heron`）用 HyperFrames CLI 把模板渲染成成片：

| 环节 | 代码位置 | 对应文档 |
| - | - | - |
| CLI 入口路径 | `HeronProductionProperties.hyperframes`（另有 `node`）；`NewsProductionPipeline.cliEntry()` 校验路径存在，否则抛 `news_tool_missing` | [cli.md](cli.md) |
| 模板与契约 | `data/templates/news-*-9x16/`，每个模板目录下有 `.hyperframes/` | [composition-contract.md](composition-contract.md) |
| 变量注入 | `NewsProductionPipeline.templateVariables()` 生成 JSON；模板用 `data-var-src` 声明；多给的键会被 `--strict-variables` 判错 | [variables.md](variables.md) |
| 时间轴补丁 | `timeComposition()` 直接改磁盘上的时间轴，因为根 `data-duration` 编译期只读一次 | [timing.md](timing.md) |
| 版式闸 | `hyperframes check`；事实真伪不在这一层判（见 `NewsScriptContract` 类注释） | [cli.md](cli.md) · [lint-rules.md](lint-rules.md) |
| 渲染闸 | `NewsProductionPipeline.renderGate`：`hyperframes render` 退出 0 且产物非空 | [rendering.md](rendering.md) |

三条最容易踩的约束：

- **变量键必须与模板声明严格对齐**，任何一边多了或少了都会在渲染闸前失败。
- **根时长改不了通过变量**：要改片长只能改源码时间轴。
- 模板里 `Date.now()`、未播种 `Math.random()`、`requestAnimationFrame` 都会破坏确定性渲染，会被 lint 直接拦下。

## 注意

- 本目录是知识点摘录，不是权威来源；与上游冲突时以上游和已安装的 CLI 为准。
- 蒸馏可能省略细节，涉及 flag 默认值、规则代码含义时必须回 [上游原文](https://hyperframes.heygen.com/) 或 `_upstream/` 快照核对。
