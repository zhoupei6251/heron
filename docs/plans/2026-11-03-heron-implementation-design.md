# Heron 实现设计（2026-11-03）

**定位**：实现级设计，承接 [2026-11-02 两条业务线需求文档](2026-11-02-heron-two-line-requirements.md)（下称「需求文档」）。
业务规则（状态机语义、闸纪律、确认三样、失败分类、保留与鉴权）**全部沿用，不重述**。

**本文件经用户确认推翻需求文档的三处口径**（2026-11-03）：

| 原口径 | 现口径 | 理由 |
|---|---|---|
| §3.3 模板由人在会话外交付 | **本轮由 Claude 生成** | 模板不存在时 N1 无法出真效果；用户要求「完整实现」 |
| §3.2 本轮不接 vars 映射 / `newsProductionPipeline` / 视觉闸 | **本轮全部接上** | 同上；模板由本会话产出后，变量名不再靠想象 |
| §6「Windows 开发机不能直跑渲染，本地开发用 `render --docker`」 | **Windows 本机直跑**：绕开 `process_guard`（§4.2），rlimit 三项由开发者须知写明 | 需求文档那条把「guard 只能 Linux」误写成「渲染只能 Linux」；渲染本身只依赖 Node + Chromium + FFmpeg，本机都有 |

其余不变。原型线 G4 已实测（zee-rp 子进程 + RP9 钉死），本轮只核实 P2，不补新功能。

---

## 0. 本轮已拍板

| # | 决策 | 定论 |
|---|---|---|
| E1 | composition 模板归属 | Claude 在本会话用 hyperframes 生成，落 `data/templates/news-9x16/`（不进 Git） |
| E2 | 渲染运行环境 | Windows 本机直跑：Node 切 22+、hyperframes 钉版（**本机尚未安装，属 M1 前置**）、本机 Chromium 与 FFmpeg；`process_guard` 仅 Linux 生效，Windows 用 `guard-enabled=false` 显式绕过 |
| E3 | 模型 | 已有可用 OpenAI 兼容端点；实现后由用户填真实 base-url / model / api-key |
| E4 | 未提交基线 | 先验证（后端编译 + 测试、前端类型检查）→ 提交 in-flight 全部改动，再在干净基线上写 G1 |
| E5 | 本轮范围 | 新闻线全闭环 G1→N1→N2→N3；原型线只核实 P2 是否已落 |
| E6 | 模板视觉 | 品牌青 `#1b6c80` + 深蓝黑底，播报三段式；底部固定 AIGC 声明与来源行 |
| E7 | 交付物 | 本设计文档；用户逐节修正后再实现 |
| E8 | 字幕粒度 | **本轮交 cue 级时间戳**（edge-tts CLI 实测只出整句一条 cue，§3.7）。`subtitles` 闸改判「cue 非空且覆盖音频时长」，不宣称词级 |

### 0.1 评审修正记录（2026-11-03 实测核对，逐条给证据）

| # | 原文档写法 | 事实 | 证据 |
|---|---|---|---|
| F1 | §3.3 根 `data-duration` 省略、由初始化脚本按变量写 clip 时长 | **变量改不了根时长**：根时长是编译期从静态属性读一次，`--variables` 或脚本改写一律被忽略。本轮由 **Java 直接改写复制到工作区的 HTML**（根 + clip 一起写死），不依赖运行时脚本 | `hyperframes-core/references/determinism-rules.md:41` |
| F2 | §3.2/§3.4/§3.7 `check --strict --variables-file vars.json` | `check` 的 flag 表里没有变量相关项；变量只在 **render** 侧校验（`--strict-variables`）。版式闸跑「模板默认值」，变量合规由渲染兜 | `hyperframes-cli/references/lint-validate-inspect.md:31-42` |
| F3 | §3.2 步 4 / §3.4 / §3.6 「词级时间戳」 | edge-tts 7.3.1 CLI 实测：30 字整句 → VTT **一条** cue（`00:00:00,100 --> 00:00:05,762`）；CLI 没有 `--words-in-cue`。词级要写 Python `Communicate` 脚本，属新增件 | 本机实测，见 §3.7 |
| F4 | §3.1 流水线读 `NewsAssetService` 拿锁定资产 | 资产哈希在认领时已快照进 `inputSnapshot.assets`，流水线**从 `run.inputJson()` 读快照**即可；再读一次配置是第二份真相 | `NewsProductionDomain.java:85` |
| F5 | §1.3/§4.3 `heron.news.production-assets` 是「空缺 / 新增」 | **读取与比对代码已在**（含 `503 news_assets_unavailable`），缺的只是值。不列入新增工作量 | `NewsProductionDomain.java:306,317-319` |
| F6 | §3.1 工具清单加 `hyperframes` 即可 | `resolvable()` 的 `default` 分支落到 `properties.getZeeRp()`——直接加名字会去探测 zee-rp 目录，判出假可用。要同步加分支与新属性 | `ProductionCapabilityService.java:63-74` |
| F7 | §8 验收 8「缺模板 → 503 且原因具体」 | 能力检查里 `templateRequired = pipelineBean.startsWith("prototype")`，新闻模板**根本不检查**。要改这一判据 | `ProductionCapabilityService.java:49-52` |
| F8 | §3.5 「附 check 原文」「路径只出现在 error_reason」 | `reason` ≤2000 字符且**不得含 `/` 或 `\`**；`inputLocator` 字符集也没有 `/`。check 原文含路径，塞进 `reason` 会被 `complete()` 判废 → 只放 finding code + selector | `NewsProductionDomain.java:225-232` |
| F9 | §3.2 步 7 未给时间预算 | `ProductionProcessService` 硬顶 `MAX_TIMEOUT_SECONDS = 240`，越界直接 400。90 秒竖版 `--quality looks` 有超 240 秒风险 → 需把常量抬到 600（同模块内改动） | `ProductionProcessService.java:40,261-264` |
| F10 | §4.2 「保留超时 + 1MiB + terminate」 | 属实，但 1MiB 是 stdout/stderr **各自**上限，且**渲染进度输出就撞线**；hyperframes 需 `--json`/静默形态。另外 `guard-enabled=false` 时 `persistIdentity` 与 guard-JSON 回读两步必须跳过，否则每条命令 503 | `ProductionProcessService.java:37,83-86,113-126` |
| F11 | §4.2 Windows 绕 guard 的代价 | 补一条：`stopAndConfirm()` 在非 POSIX 平台第一步就 `return false`（`File.separatorChar != '/'`），Windows 上**终止无法确认**，恢复流程只能等租约超时 | `ProductionProcessService.java:164-166` |
| F12 | §4.1 「`npx --no-install` 解析到 0.9.6」 | 当时是**报错**：`npx canceled due to missing packages`，hyperframes 本机没装。npm 上存在 0.9.6，但「已装」是假的。当前激活 Node 20.9.0，nvm 里有 22.20.0 / 24.22.1 / 26.8.2 | 本机实测 |
| F13 | §3.2 步 1 失败类 `model` | 真实 token：`503 model_not_configured`、`503 model_failed`、`409 news_script_source`、`409 news_script_contract`（`author()` 返回 **JSON String**，不是对象） | `NewsScriptAuthor.java:10`、`NewsModelScriptAuthor.java:43,56` |
| F14 | §3.2/§3.6 音频如何进成片 | 原文没写。成片要有 1 路音频（验收 2），必须：模板内固定 `<audio src="narration.mp3">`（带作者态 fallback src，否则 lint `media_variable_src_no_fallback`），流水线把 TTS 产物落成同名文件。**已实测走通**：样片 ffprobe 回 `h264 1080x1920@30 + aac` | `hyperframes-core/references/variables-and-media.md`、本机 render 样片 |
| F15 | §3.2 步 9 / §3.7 `ffmpeg -i in -c copy -metadata AIGC=…` | mp4 容器**默认不写用户元数据**：同一份样片不加 flag 时 `format_tags` 里根本没有 AIGC（只有 major_brand/encoder 等），加上 `-movflags +use_metadata_tags` 才回 `TAG:AIGC=内容由 AI 生成`。原形状会「命令退出 0、闸却永远判不过」——所以步 9 的判定必须读回 ffprobe，不能只看退出码 | 本机同文件两次 ffmpeg/ffprobe 对比 |
| F16 | §3.5 `visual` 闸用 `check --strict` | `--strict` 把 warning 也判失败，而模板自检就有 14 条 warning：3 条 `line_unread`（600 字正文 7 秒读不完，是契约的算术后果）+ 10 条「拆子工程/轨道太密」的 Studio 风格建议。版式闸改为判 `errors==0` 且 `contrast/runtime/motion` 三段为空，warning 只计数 | 本机 `check --json` 实测输出 |
| F17 | §3.4「未用槽位填空串，模板隐藏」 | 只靠 `scene_count` 隐藏**不够**：`check` 不带变量跑（F2），用的是模板默认 `scene_count=6`，未用槽位仍带旧时点挂在同一条轨道上，与已用槽位时间重叠 → `content_overlap` 判失败，真渲染第一次就死在这里。补丁必须把未用槽位一并挪到时间轴末尾（`data-start=总长`、`data-duration=0.01`） | `NewsProductionPipelineRenderTest` 首跑失败原文 |
| F18 | §3.5 `measured.checkFindings` 之类的计数 | `check --json` 每一段是对象 `{ok, errorCount, warningCount, infoCount, findings:[…]}`，**不是数组**。按 findings 遍历去数 severity 会把 `errorCount` 等数字字段也算进去（真跑数出 38 条 error 而 `ok=true`）。计数一律用 CLI 自己的 `errorCount/warningCount` | 第二次真跑 `版式闸未过：errors=38 blankFrames=0` 与 `ok=true` 自相矛盾 |
| F19 | §3.5 `visual` 闸的 `inputLocator` 写 `composition/index.html` | 该字段字符集不含 `/` 与反斜杠（`NewsProductionDomain.java:229`），任务一路跑到 `complete()` 才被 `409 news_gate_failed` 拒掉 —— 两分钟真渲染白跑。定位符改成 `composition#index.html`，并在渲染测试里断言十闸的 `inputLocator`/`reason` 都不带路径分隔符 | 真跑第 3 次失败原文「检查闸缺失、失败或报告不安全」 |
| F20 | §4.1「`node: node` 就够」 | 后端从 PATH 解析 `node`，而 nvm-windows 的 `node` 是机器级符号链接，会被同机其它会话切回 20.x；hyperframes 直接拒绝并打印 `requires Node.js >= 22`，而我的失败消息只带 stdout，把这句丢了，排查绕了一圈。现在 dev 覆盖成绝对路径 `D:/Users/zhoupei/AppData/Local/nvm/v24.22.1/node.exe`（含本机用户名，换机器要改），失败消息同时带 stdout 与 stderr | 真跑第 2 次失败 + 手工复现时 `node -v` = 20.9.0 |

---

## 1. 已核实代码事实（2026-11-03）

### 1.1 已提交

`2a8ac20a9 Collect news sources and seed the Heron menu surface` 及之前：共享生产核心、两线状态机、表、闸结构、权限、操作台、抓取安全边界（URL 策略 / 地址闸 / DNS 钉死）。

### 1.2 未提交（**整批**，M0 统一验证后提交）

- **G2 模型**：`OpenAiModelClient`、`NewsModelScriptAuthor`、`NewsScriptContract`、`NewsArticleBody`、`NewsSourceFetcher.fetchArticle`、`heron/news-script-contract.md`、`ModelClientTest`、`NewsScriptContractTest`
- **G4 原型**：`ProtoProductionPipeline`、`ProtoModelRp1Author`、`ProtoRp1Author`、`heron/rp1-authoring-contract.md`、`ProtoEnginePipelineTest`、`ProtoRp1AuthorTest`、`ProtoManifestMediaTest`
- **G3 发布**：`NewsPublishBo`、`NewsWorkServiceImpl.publish`、`NewsVideoVersion` `publish_*` 六列、`NewsPublishAccessTest`、前端发布回填表单
- **文档**：`CLAUDE.md`、`docs/plans/2026-11-02-heron-two-line-requirements.md`、`AGENTS.md` 等
- **另有 40+ 个已跟踪文件处于 modified**（yml、`pom.xml`、docker-compose、`script/sql/*` 等），提交范围按 `git status` 实盘逐条过，不用 `git add .`

### 1.3 空缺（本轮全部工作量）

| # | 空缺 | 落点 |
|---|---|---|
| G1-a | `newsProductionPipeline` 生产实现 | 新建 `NewsProductionPipeline` + `@Bean("newsProductionPipeline")` 条件注册 |
| G1-b | composition 模板 | 新建 `data/templates/news-9x16/`（本会话生成，不进 Git；`data/` 目录本机**尚不存在**，M1 创建） |
| G1-c | 稿件 JSON → 模板变量映射 | 新建 `NewsTemplateVariables`（确定性函数，无模型；只映射**文本**，时长走磁盘补丁） |
| G1-d | edge-tts / ffprobe / ffmpeg / hyperframes 调用 | 全部经 `ProductionProcessService` |
| G1-e | 十道闸的真实判定 | 由流水线产出 `checks`，`NewsProductionDomain.complete()` 复核 |
| G1-f | 资产锁定的**值** | `heron.news.production-assets` 的读取与比对已在 `NewsProductionDomain:306`；缺模板/字体/音色哈希值与注入 |
| G1-g | **运行前置** | hyperframes 本机未安装、Node 仍 20.9.0 —— 属 M1 第一步，不装不写模板 |

---

## 2. 目标架构

```
                    ┌──────────────── 共享生产核心（不改）────────────────┐
                    │ ProductionService / Execution（租约·认领·心跳）      │
                    │ ProductionRunner → IProductionPipeline              │
                    │ Recovery / Artifact / Usage / Cleanup               │
                    └───────────────┬─────────────────┬──────────────────┘
                                    │                 │
                  newsProductionPipeline        prototypeProductionPipeline
                  （本轮新建）                  （已实测）
                                    │                 │
     ┌──────────────────────────────┴──┐        zee-rp 子进程（node scripts/proto.mjs）
     │ 模型写稿 → 契约闸 → 变量映射     │
     │ → 磁盘补丁定时长 → edge-tts      │
     │ → hyperframes check → render     │
     │ → ffprobe decode → ffmpeg AIGC   │
     │ → 视觉闸 → 六类产物原子落盘      │
     └─────────────────────────────────┘
```

**只加一个流水线 bean + 一份模板资产 + 配置面；不改状态机、不新增表、不拆服务。**

---

## 3. G1 新闻渲染流水线设计

### 3.1 类与注册

接口契约（实测）：`IProductionPipeline` 只有一个方法 —— `ProductionResultVo execute(ProductionRunVo run)`（`IProductionPipeline.java:11`），无 `throws`（失败抛非受检 `HeronBusinessException(status, code, message)`），**没有** `supports()` / `capability()` / 步骤名声明。恢复是独立接口 `IProductionRecoveryPolicy.canResume(run)`，只有实现它才参与续跑。

```java
public class NewsProductionPipeline implements IProductionPipeline {
    private final NewsScriptAuthor author;
    private final ProductionProcessService processes;
    private final ProductionArtifactService artifacts;
    private final HeronProductionProperties properties;
    private final Clock clock;
}
```

```java
@Bean("newsProductionPipeline")
@ConditionalOnProperty(prefix = "heron.news", name = "pipeline-enabled", havingValue = "true")
public NewsProductionPipeline newsProductionPipeline(...)
```

- bean 名**硬编码在两处**、必须逐字相同：`ProductionRunner.java:201-209` 与 `ProductionRecoveryService.java:141-149`（`news_video` → `"newsProductionPipeline"`）。名字打错 = 任务被 poll 但拿不到流水线，静默不报错。
- 资产**不新增 `NewsAssetService`**：`reserve()` 已把 `heron.news.production-assets` 快照进 `inputSnapshot.assets`，流水线从 `run.inputJson()` 读同一份快照比对磁盘哈希（F4），避免认领与运行两读两真相。
- 缺模板 / 缺 hyperframes / 缺模型配置时不注册 bean，由 `ProductionCapabilityService` 报不可用，不给「能提交但必失败」的路径。为此要动两处现有代码（F6/F7）：
  1. `resolvable()` 加 `case "hyperframes" -> properties.getHyperframes()`，按「CLI 入口文件存在」判定；否则该名字会掉进 `default` 去探测 zee-rp 根目录。
  2. `templateRequired` 现在只认 `startsWith("prototype")`；新闻侧同样要求模板目录存在，否则 §8 验收 8 落空。缺件原因沿用现有 token 形状（`tool_missing:hyperframes` / `template_missing`），**不自造 `tool_missing` 裸串**。
- 新闻工具清单：`{node, python, ffmpeg, ffprobe, hyperframes}`（现有三项 + python + hyperframes）。`configured / available` 判定式、`data_root_unavailable` 检查不改。
- `worker-enabled` 仍默认 `false`。
- **本轮不实现 `canResume()`**：中断即整条重跑，出新版本新指纹（沿用「重渲使确认失效」的既有纪律），不引入 checkpoint 语义。

### 3.2 编排（每步 = 真实外部效果）

进程一律 `processes.run(run, new ProductionProcessBo(tool, args, workDir, timeout, env))`；工作目录恒为本次尝试目录（`validate()` 强制），命令内用相对路径。

| # | 步骤 | 调用 | 超时 | 失败语义 |
|---|---|---|---|---|
| 1 | 写稿 | `author.author(run)` → **JSON String**：抓正文 → 模型 → 覆盖出处三字段 → 契约闸 | 模型侧 300 s 常量 | `503 model_not_configured` / `503 model_failed` / `409 news_script_source` / `409 news_script_contract`；不重试、不改稿 |
| 2 | 变量映射 | `NewsTemplateVariables.of(script)`（纯 Java，只文本） | — | `409 news_script_contract` |
| 3 | 复制模板 + 磁盘补丁 | 模板目录 → `work/composition/`，写 `vars.json`，**Java 改写 `index.html` 的根与 clip `data-duration`** | — | `503 engine` |
| 4 | 配音 | `python -m edge_tts -f narration.txt -v <voice> --no-progress --write-media composition/narration.mp3 --write-subtitles subs.vtt` | 120 s | `audio`；**无兜底** |
| 5 | 定时 | `ffprobe` 实测音频时长 → 按声明时长比例缩放，回写步 3 的属性（步 4 之后执行，见下） | 30 s | `audio` |
| 6 | 版式闸 | `node <cli> check composition --json`（**不带变量、不挂 `--strict`**，F2 + §3.3） | 240 s | `visual`（版式）；只记 finding code + selector |
| 7 | 渲染 | `node <cli> render composition --variables-file vars.json --strict-variables --quality looks --output out.mp4` | **600 s**（需抬 `MAX_TIMEOUT_SECONDS`，F9） | `render` |
| 8 | 解码 | `ffprobe -v error -show_format -show_streams out.mp4` | 30 s | `decode` |
| 9 | AIGC 元数据 | `ffmpeg -i out.mp4 -c copy -metadata AIGC="内容由 AI 生成" final.mp4` | 120 s | `aigc_metadata`；**不可跳过** |
| 10 | 视觉闸 + 产物 | ffmpeg 抽帧 tile 出联络表、空白帧判定、六类产物落盘、`checks` / `script` / `asset_manifest` | 120 s | `visual` / `409 manifest_invalid` |

> 页尺寸写在模板的 `data-width="1080" data-height="1920"`。
> 步 3 先按稿件声明时长铺一版属性，步 5 拿到实测音频时长后再按同一规则缩放重铺——两次都只改磁盘上的 HTML，渲染读到的永远是编译期那一份。

### 3.3 模板设计（本会话生成）

- 位置：`data/templates/news-9x16/`（`.gitignore:62` 的根锚定 `/data/` 已实测生效：`git check-ignore -v data/templates/news-9x16/index.html` 命中该规则）。
- 画布：`data-width="1080"` `data-height="1920"`，9:16。
- 结构：标题区（大字，≤15 字）+ 6 个场景槽（领句 + 屏句）+ 结尾领句 + 底部固定条。
- **音频轨**：模板内固定一个 `<audio id="narration" src="narration.mp3" data-start="0" data-duration="...">`，流水线每次把 edge-tts 产物落成该同名文件（F14）。视频元素必须 muted/inline，音频只能是独立 `<audio>`——这是 hyperframes 的媒体规则，不是我们的选择。
- 底部固定条**由模板保证始终在场**：
  - 「内容由 AI 生成」角标（可见、对比度过 `check`）
  - 「来源：`{{source_name}}`」
- 视觉：品牌青 `#1b6c80` + 深蓝黑 `#131a2a`；字体用开源中文字体（思源黑体 / Noto Sans SC 的 woff2 随模板放），**禁用微软雅黑**。
- 时间轴：**根 `data-duration` 必须写死**，由流水线按实测音频时长在磁盘上改写；clip 的 `data-duration` 同样由流水线改写。依据：根时长编译期从静态属性读一次，**脚本或 `--variables` 事后改写一律无效**；clip 时长确实从 live DOM 重读，但那只在根省略时才影响总长推导（`determinism-rules.md:41`）。结论是「Java 改文件」而不是「运行时脚本读变量」——少一个脚本、少一处不可复现。
- `check` 会因「3 秒以上时间轴全程零几何变化」判 `sweep_static` 失败：模板必须让某元素持续在动（角标呼吸、逐段揭示），否则一切绿判定都不可信。
- 交付自检（2026-11-03 实测）：`hyperframes lint` → `0 error / 9 warning`；`hyperframes check --json` → `ok=true`，`runtime / layout / motion / contrast` 四段合计 **0 条 error、14 条 warning**；`render --quality looks --workers 1` 出一条样片。
- **版式闸不挂 `--strict`**，理由全是实测出来的：`--strict` 把 warning 也变成失败，而那 14 条里 ——
  - 3 条 `line_unread`：600 字正文在 7 秒内读不完，这是契约 `body ≤ 600` 的算术后果，不砍契约就永远过不了 `--strict`；
  - 9 条 `nested_structure_needs_subcomposition` + 1 条 `timeline_track_too_dense`：hyperframes 建议把每个场景拆成 `data-composition-src` 子工程。拆成 7 个文件会让「Java 改磁盘上的 `data-start` / `data-duration`」变成跨文件补丁，还要用 `data-variable-values` 把变量传进子工程 —— 换来的只是 Studio 时间轴更好看。
  - 所以 `visual` 闸改判：`check` 退出 0 **且** `errors == 0` **且** `contrast / runtime / motion` 三段为空；warning 只计数进 `measured.checkWarnings`，不拦。
- 模板文件清单（`data/templates/news-9x16/`，均计入 `assets-manifest.json` 的 sha256）：`index.html`（单文件，样式内联，无外部 CDN）、`gsap.min.js`（3.14.2 从 npm 拷入）、`fonts/NotoSansSC-Regular.otf`（思源黑体简体中文 Regular，16.4 MB，来自 notofonts/noto-cjk）、`narration.mp3`（45 秒静音占位，供模板自检；真跑由流水线同名覆盖）、`index.motion.json`（AIGC 角标断言：`appearsBy #aigc-badge 0.5s` + `staysInFrame`，selector 打不到元素会报 `motion_selector_missing`，不会静默放行）。
- `shortcut: 屏上正文用 -webkit-line-clamp: 11 截断，音频读全文；触发升级条件是实测反馈「画面要点不够用」，那时砍 body 契约长度而不是加滚动。`
- 模板目录同时产出 `assets-manifest.json`（列出 index.html / css / 字体 / 脚本的 sha256），其 sha256 锚定进配置。

### 3.4 变量契约（script JSON → vars.json）

变量声明在 `<html data-composition-variables='[{"id":…,"type":…,"label":…,"default":…}]'>`，支持类型 `string`（可带 `maxLength`）/ `number`（可带 `min`/`max`）/ `color` / `boolean` / `enum`（**必须给 `options`**）。**没有数组类型**，因此场景用显式槽位。每个标量会自动作为 `--{id}` CSS 自定义属性落到根上，文本用声明式绑定 `data-var-text="id"` 即可，不写取变量的脚本。

| 变量 | 类型 | 来源 | 约束 |
|---|---|---|---|
| `title` | string | `script.title` | maxLength 15 |
| `closing` | string | `script.closing` | maxLength 200 |
| `source_name` | string | `script.source_name` | maxLength 80 |
| `accent` | color | 固定 `#1b6c80` | 模板默认值 |
| `scene_count` | number | `scenes.length` | 3–6，`min/max` 已声明；未用槽位由初始化时的一次变量读取 `hidden + display:none` 摘掉 |
| `scene{n}_lead` | string | `scenes[n-1].lead` | maxLength 40；未用槽位为空串 |
| `scene{n}_body` | string | `scenes[n-1].body` | maxLength 600；未用槽位为空串 |

- **时长不进变量**（F1）：`scene_count`、`scene{n}_seconds` 一律取消。段数与每段秒数由流水线直接写在 `index.html` 的 `data-start` / `data-duration` 上；未用槽位写 `data-duration="0"` 并从时间轴上摘掉。
- 映射是**确定性函数**（`NewsTemplateVariables.of(JsonNode)`），不含模型、不含分支选择；未用槽位填空串。
- 时长缩放：`audioSeconds`（ffprobe 实测）÷ `Σ duration_s` → 乘各段声明时长，保证音画同长；总长以实测音频为准。
- 变量的键名/类型/长度合规由渲染的 `--strict-variables` 兜（未知键、类型不符、枚举越界 → 失败）；`check` 侧不看变量（F2），所以**版式合规靠模板默认值自证**。
- 契约闸已有的数值上限（实测 `NewsScriptContract.java:18-22,39-45,72-77`）：`title ≤ 15`、`closing ≤ 200`、`source_name ≤ 80`、`lead ≤ 40`、`body ≤ 600`、`scenes 3–6`、`Σ duration_s 30–90`、每段 `duration_s > 0`、`body_sha256` 必须 `[0-9a-f]{64}`、标题不得与任一段 `lead`/`body` 逐字相等。模板 maxLength 与之取齐，Java 不重复实现几何校验。

### 3.5 十道闸的真实判定

`checks` 是数组（1–200 项），每项键只能是 `{gate, inputLocator, passed, reason, measured}` 的子集；`gate` 匹配 `[a-z][a-z0-9_]{0,63}`；`passed` 必须是布尔且为 `true`；`inputLocator` 匹配 `[a-zA-Z0-9_.:#\[\]-]{1,256}`；`reason` 必须是字符串、≤2000 字符且**不含 `/` 与 `\`**；`measured` 必须是对象，键 `[a-zA-Z][a-zA-Z0-9_]{0,63}`、**值只能是数字或布尔**。十项全在场且全 `passed=true`，否则 `409 news_checks_incomplete`（`NewsProductionDomain.java:222-241`）。

| 闸 | 判定者 | inputLocator | measured | 不通过 |
|---|---|---|---|---|
| `schema` | `NewsScriptContract.violations(script)` 为空 | `script` | `{violations: 0}` | 退回写稿，条目进 `error_reason` |
| `evidence` | 出处三字段存在（`source_url`/`fetched_at`/`body_sha256`，且哈希为完整 SHA-256） | `script.source_url` | `{sourceUrl: true, fetchedAt: true, bodySha: true}` | 失败，指出缺项 |
| `audio` | edge-tts 退出 0 + ffprobe 音频时长 > 0 | `narration.mp3` | `{audioMs, voiceBytes}` | `audio` 失败，**无兜底** |
| `subtitles` | VTT 末条 cue 覆盖 **≥ 90%** 实测音频（真跑实测：52 s 音频末条 cue 落在 50.2 s，卡绝对差会误判） | `subs.vtt` | `{cues, coveredMs, audioMs}` | `subtitles` 失败 |
| `render` | hyperframes 退出 0 + 产物非空 | `out.mp4` | `{exitCode, bytes}` | `render` 失败，现场进 `error_reason` |
| `decode` | ffprobe 容器/尺寸/时长/fps/流数 | `final.mp4:stream.0` | `{durationMs, fps, videoStreams, audioStreams}` | `decode` 失败 |
| `visual` | `check --json` 退出 0 且 `errors==0` 且 `contrast/runtime/motion` 为空 + 接触表采样帧非空白 | `composition/index.html` | `{checkErrors, checkWarnings, blankFrames}` | `visual` 失败 |
| `aigc_metadata` | ffprobe 读 mp4 `format_tags` 里的 `AIGC` | `final.mp4:tags` | `{aigcTag: true}` | **拒绝进入发布，不可跳过** |
| `aigc_visible` | 角标的 `*.motion.json` 断言随 `check` 一起过（selector 匹配不到元素会报 `motion_selector_missing`，不会静默放行） | `#aigc-badge` | `{badgeAssertion: true}` | 失败 |
| `assets` | 模板/字体/音色/manifest 磁盘哈希与 `inputJson.assets` 逐项相等 | `assets-manifest.json` | `{templateMatched, fontMatched, manifestMatched}` | `assets` 失败 |

- **`reason` 里放什么**：一句人话 + finding code / 条目名，路径一律不写（F8）。`check --json` 原文、stderr 原文、契约违规清单统统进任务 `error_reason`，不进 `reason`。
- **原文往哪放**：产物类型固定六类、`SAFE_KIND` 也限长 32，**不能新增 `check_report` 一类**。`check` 原文只能落两处：`checks` 产物 JSON 内的 `reason`（净化后，单项 ≤2000）与任务 `error_reason`；两者都受 256 KiB 的 JSON 总量闸（§3.6）。因此版式闸只保留 finding code + selector + 计数，不逐字贴 HTML 片段。
- 任何一步失败 = 流水线抛 `HeronBusinessException`，任务失败、原因入库；`complete()` 不写半份结果。

### 3.6 六类产物

`NewsProductionDomain.java:34` 的 `KINDS` 固定六项，缺一即 `409`（「必须具备全部六类产物」，:180），kind/扩展名/mediaType 与 `storageKey` 前缀都有硬校验：

| kind | 内容 | mediaType | 说明 |
|---|---|---|---|
| `mp4` | 成片 | `video/mp4` | 经 AIGC 元数据后写 |
| `contact_sheet` | 联络表 PNG | `image/png` | ffmpeg 等距抽帧 + tile；**人工确认指纹锚点** |
| `script` | 稿件 JSON | `application/json` | 必须与 `resultJson.script` 逐字节相等（:202-216） |
| `subtitles` | 字幕 cue JSON | `application/json` | 从 VTT 解析为 `[{text,startMs,endMs}]`（E8：cue 级，不叫词级） |
| `checks` | 十道闸报告 | `application/json` | 必须与 `resultJson.checks` 逐字节相等 |
| `asset_manifest` | 本次资产清单 | `application/json` | 模板/字体/音色/输入哈希 |

- 全部先写工作目录，再经 `ProductionArtifactService.finalizeFile(run, kind, file, mediaType)`（:54；带版本号的重载 :61）原子搬移。
- `resultJson` 必须含 `script`（对象且再过一遍契约）与 `durationMs`（整数 > 0），`checks` 从同一对象读；三段 JSON 单段 ≤256 KiB（`NewsProductionDomain.java:130-135,333-337`）。`durationMs` 取 ffprobe 实测。

### 3.7 外部命令形状（全部经 `ProductionProcessService`）

```
python -m edge_tts -f narration.txt -v <voiceKey> --no-progress
        --write-media composition/narration.mp3 --write-subtitles composition/subs.vtt
ffprobe -v error -show_entries format=duration -of default=nw=1 composition/narration.mp3
node <hyperframes-cli> check composition --json
node <hyperframes-cli> render composition --variables-file vars.json --strict-variables
        --quality looks --workers 1 --no-best-effort --quiet --output out.mp4
ffprobe -v error -show_format -show_streams out.mp4
ffmpeg -i out.mp4 -c copy -movflags +use_metadata_tags -metadata AIGC=内容由 AI 生成 final.mp4
ffmpeg -i final.mp4 -vf "select=...,scale=...,tile=3x2" -frames:v 1 contact.png
```

0.9.6 实测 CLI 帮助（2026-11-03，`node bin/hyperframes.mjs <cmd> --help`）给出的三条硬约束：

- **渲染必须 `--workers 1`**：默认 `auto` 按 16 核各起一个 Chrome（每个约 256 MB），而 `doctor` 实测本机 `Memory false — 31.5 GB total · 1.4 GB available`。多 worker 不是更快，是先把机器压死；`--workers 1` 同时让渲染可复现。
- **必须 `--no-best-effort`**：`--best-effort` 默认 `true`，意思是「媒体缺失或未就绪也带着告警出片」。我们要的是缺旁音就失败，不是出一段无声成片再靠 `decode` 闸补救。
- **必须 `--quiet`**：`ProductionProcessService` 对 stdout 与 stderr **各自**限 1 MiB，渲染逐帧进度足以撞 `409 production_output_limit`（F10）。
- `check` 的帮助里只有 `--json / --strict / --proxy / --snapshots`，**没有任何变量相关 flag** —— F2 由 CLI 自证。
- `--quality` 的帮助原文是 `draft, looks, delivery, or standard/high`，且「looks is the default (CRF 16)、delivery is high」。所以 `standard/high` 不是另一套云端词表，而是同一 CLI 里 `delivery` 的别名；本轮仍只用 `draft | looks | delivery`，与需求文档口径一致。
- `--variables-file` 与 `--strict-variables` 确实只挂在 `render` 上（F2 的后半句成立）。
- `--docker` 存在但本机 `Docker running false`，R3 的退路暂时不可用；`Chrome true` 已缓存 `chrome-headless-shell win64-152.0.7977.30`，R3 的「Windows 无头浏览器」前提已具备，剩下的只是真跑稳定性。

实测与本子的三处不一致，已按实测改：
- `edge-tts` 7.3.1 的 CLI 参数是 `-f/--file`、`-v/--voice`、`--write-media`、`--write-subtitles`，另有 `--no-progress`（进度条写 stderr，1 MiB 上限下必须关）。**没有** `--words-in-cue`，一次 30 字整句只出一条 cue（E8/F3）。要词级就得写 `edge_tts.Communicate` 的 Python 脚本——本轮不做。
- `check` 不吃变量（F2）。`--strict` 是把 warning 也进门禁的那一档。
- `render` 的 `--quality` 本地词表确为 `draft | looks | delivery`，CLI 默认 `looks`；`draft|standard|high` 属 cloud 路径，不得混用。`--docker` 存在，留作 R3 的退路。
- 工具名仍只能用白名单 `{node, ffmpeg, ffprobe, python, zee-rp}`（`ProductionProcessService.java:36`）：hyperframes 以 `node <cli 入口>` 形态调用（镜像 zee-rp 的 oracle 形态），**不 import 其内部代码**，也不把 `hyperframes` 塞进白名单。
- `edge-tts` 用 **Python CLI**（本机 7.3.1，出网到微软实测通过：30 字整句出 34 KB mp3），不是需求文档 §6 写的 node-edge-tts；一个依赖同时给音频与字幕时间戳。

### 3.8 失败语义

沿用需求文档 §3.4：每类失败带「哪条闸 + 哪段输入 + 为什么」。不自动改稿、不自动重试、不静默降级、不退回「只用标题」。

---

## 4. 运行环境与进程执行面

### 4.1 Node 与 hyperframes（M1 前置，不是「已就绪」）

实测现状（2026-11-03 已装好）：激活 Node **24.22.1**（原来 20.9.0，nvm-windows 切换）、`python` 3.12.3、`ffmpeg`/`ffprobe` N-122527、edge-tts 7.3.1、**hyperframes 0.9.6** 装在 `heron/data/tools/node_modules/`（`/data/` 已忽略，不进 Git），CLI 入口 = `heron/data/tools/node_modules/hyperframes/bin/hyperframes.mjs`，`--version` 回 `0.9.6`。

- 用 `npm install hyperframes@0.9.6 --prefix data/tools` 显式钉版，不跟 `npx @latest`（`npx --no-install` 当时是报错「missing packages」，不是解析到版本，F12）。
- `hyperframes doctor --json` 实测：`Chrome true`（`~/.cache/hyperframes/chrome/chrome-headless-shell/win64-152.0.7977.30`，无需再下载）、`FFmpeg/FFprobe true`、`Docker true 但 Docker running false`、`Memory false（31.5 GB total · 1.4 GB available）`、whisper/Kokoro 缺（本轮链路不用）。**`ok=false` 主要由内存那一项造成**，所以 `--workers 1` 是硬要求（§3.7）。
- 配置项 `heron.production.hyperframes` = 上面那个 `.mjs` 绝对路径；能力检查按「文件存在」判定（需在 `resolvable()` 加分支，§3.1）。
- `nvm use 24.22.1` 是**机器级**切换，顺带解开 `heron-ui` 的 pnpm 10.34.5（需 Node ≥22.13）导致的 vue-tsc/lint/build NOT RUN 旧阻塞。

### 4.2 `process_guard` 的 Windows 口径

**事实**：`ProductionProcessService.run()` 无条件把每条命令包成 `python process_guard.py --timeout … --identity … -- <tool> <args>`（:66-73），guard 用 `resource` / `/proc` / `killpg`，**仅 Linux**；而且 `persistIdentity()` 要求 2 秒内读到 `guard-identity.json`，读不到就 `503 production_identity_unavailable`（:83-86,221-245）——也就是说在 Windows 上不是「少一层限制」，是**每条命令都跑不通**。

**设计**：新增 `heron.production.guard-enabled`（默认 `true`）。

- `true`：现有路径不变，生产 Linux 照旧有内存/CPU/文件/超时/进程组终止。
- `false`：直接 `ProcessBuilder` 起 `<tool> <args>`（跳过 guard 包装、跳过 identity 落盘与 guard-JSON 回读，:120-126 那步在 no-guard 分支必须不执行），保留 Java 侧已有的**超时（`waitFor(timeout + 10s)` → `destroy`/`destroyForcibly`）+ stdout/stderr 各 1 MiB 上限 + 非零退出码交回**。
- 只在 Windows 开发机显式设 `false`；生产 yml 不设（默认 true）。**不做平台自动探测**——静默降级是被禁止的。
- 代价写进开发者须知，三条都要写：① Windows 开发机没有内存/CPU/文件 rlimit；② `stopAndConfirm()` 在非 POSIX 第一步就 `return false`（:164-166），**Windows 上无法确认子进程已被终止**，恢复只能等租约超时；③ 1 MiB 是 stdout 与 stderr **各自**上限，hyperframes 渲染进度刷屏即撞 `409 production_output_limit`——所以命令一律带 `--json` / 静默形态，长输出落文件。

### 4.3 配置项总表

| 配置 | 当前 | 本轮 |
|---|---|---|
| `heron.production.worker-enabled` | `false` | N3 打开 |
| `heron.production.python` | `python3`（Windows PATH 上无 `python3.exe`，能力探测必失败） | Windows dev 设 `python` |
| `heron.production.hyperframes` | 无 | 新增属性 + `resolvable()`/`toolPath()` 分支；CLI 入口绝对路径 |
| `heron.production.guard-enabled` | 无 | 新增，默认 `true` |
| `heron.production.max-timeout`（或直接把常量抬到 600） | 常量 240 s | 渲染需要，见 F9；改的是本模块常量，不动公共模块 |
| `heron.news.pipeline-enabled` | 无 | 新增，默认 `false`；N1/N2 打开 |
| `heron.news.production-assets` | **读取代码已在**（`NewsProductionDomain:306`），值缺失 → `503 news_assets_unconfigured` | 补值并注入 yml/环境，**不算新增代码** |
| `heron.news.allowed-hosts` | `[]` | N3 填泛资讯源 |
| `heron.news.collection-enabled` | `false` | N3 打开 |
| `heron.prototype.engine-enabled` | `false` | 不动 |

---

## 5. 模型与资产配置面

### 5.1 模型（已接线，不改代码）

- 键：`heron.model.base-url` / `heron.model.name` / `heron.model.api-key`；先读 `sys_config`，空/空白回落 `Environment` 的**同一点分键**（yml 或 `HERON_MODEL_*`，靠 Spring relaxed binding 命中，代码里没有显式读环境变量名），取值 `.trim()`（`OpenAiModelClient.java:45-47,104-110`）。
- 超时 300 秒是常量，无第四项配置；`model_not_configured` 在发请求与起用量行**之前**判定（:48-50），请求失败 `503 model_failed` 在 `usage.fail` 之后（:113-121）。
- 本轮出 seed SQL `script/sql/heron/005-model-config.sql`：三条 `sys_config` 行（`config_value` 留空或占位），由用户在「参数管理」填真实值；**密钥不进 Git**。实测：目录里只有 `001-production`…`004-menu`，`heron.model.*` 一条 seed 都没有，005 号位正确。
- 页面只回显「已配置」，不回显明文（沿用）。基座「参数管理」对管理员仍显示明文，是选 `sys_config` 存密钥的既定代价，不在本轮加固范围。

### 5.2 资产锁定

`heron.news.production-assets`（JSON，Environment 读取）：

```json
{
  "templateKey": "news-9x16",
  "templateVersion": "1",
  "fontSha256": "<字体文件 sha256>",
  "voiceKey": "zh-CN-XiaoxiaoNeural",
  "voiceVersion": "1",
  "assetManifestSha256": "<assets-manifest.json 的 sha256>"
}
```

- 现有校验：必填这六个键、值匹配 `[a-zA-Z0-9_.-]{1,80}`、`*Sha256` 匹配 `[0-9a-f]{64}`；不匹配 → `503 news_assets_unavailable「选择的模板或声音尚未验证」`（`NewsProductionDomain.java:311-319`）。
- 值在模板生成后计算并写入 `application-dev.yml`（本地）/ 部署环境。
- 流水线侧只加一件事：拿 `inputJson.assets` 的快照与磁盘文件实算哈希逐项比，写 `assets` 闸（F4）。

### 5.3 前端固定键

`news/work/index.vue` 现在的「模板 / 音色」是自由文本 `el-input`（:102-106），初值为空、载入时 `|| 'default'` 兜底（:510-511），提交 `saveNewsDraft`（:556）——`default` 一定撞 `503 news_assets_unavailable`。改为**固定值**：

- `templateKey = news-9x16`、`voiceKey = zh-CN-XiaoxiaoNeural`，只读展示（或隐藏传值），同时改掉 `:510-511` 的 `|| 'default'` 兜底。
- 顺带记一笔现有不一致：前端校验正则 `^[a-zA-Z0-9_-]{1,80}$`（:421-422）**不收点**，后端 `NewsProductionDomain` 的 `[a-zA-Z0-9_.-]{1,80}` 收点。本轮两个固定值都不含点，不触发；不顺手改正则。
- 其余页面不动；发布回填表单已在 in-flight（「人工发布回执」:278-327，提交 `publishNewsVersion(...)` :639-643）。

---

## 6. 变更清单

**后端（新增）**
- `service/impl/NewsProductionPipeline.java`（实现 `execute(ProductionRunVo)`，不实现 `canResume`）
- `service/impl/NewsTemplateVariables.java`（只映射文本变量）
- `service/impl/NewsCompositionPatch.java`（把根/clip 的 `data-duration` 与音频时长写进复制出来的 HTML；纯字符串改写，不引 JS 引擎）
- 测试：`NewsTemplateVariablesTest`、`NewsCompositionPatchTest`、`NewsProductionPipelineTest`（假进程服务）

**后端（改动，均为本模块）**
- `config/HeronProductionConfiguration.java`：注册 `newsProductionPipeline`
- `config/HeronProductionProperties.java`：`hyperframes`、`guardEnabled`
- `service/impl/ProductionProcessService.java`：`guard-enabled` 分支（跳过 guard 包装、identity 落盘与 guard-JSON 回读）；`MAX_TIMEOUT_SECONDS` 240 → 600
- `service/impl/ProductionCapabilityService.java`：新闻工具清单加 `python` + `hyperframes`、`resolvable()` 加 hyperframes 分支、`templateRequired` 放开新闻侧
- 不改：`NewsProductionDomain`（闸与资产比对已就位）、`ProductionRunner` / `ProductionRecoveryService`（bean 名对上即可）、`ProductionArtifactService`
- 测试改动是连带的，不是可选：
  - `ProductionProcessTest.rejectsInvalidCommandsBeforeStart`（:56-61）现在断言 **300 秒被拒**；把 `MAX_TIMEOUT_SECONDS` 抬到 600 就把它改绿不了 → 同一改动里把越界样本改成 601/700，别留红。
  - 5 个 Linux 用例是 `@EnabledOnOs(OS.LINUX)`，Windows 上恒跳过 → `guard-enabled=false` 分支要新增**本机就能跑**的用例（无 guard 起进程、超时置 `timedOut`、1 MiB 溢出仍 409），否则这条新路径零覆盖。
  - `ProductionCapabilityTest` 加：`tool_missing:hyperframes`、新闻模板缺失报 `template_missing`（现在只报 prototype 侧）。

**配置**
- `heron-production.yml`：新开关与 hyperframes 路径
- `application-dev.yml`：`python: python`、`guard-enabled: false`、`pipeline-enabled`、`news.production-assets`
- `script/sql/heron/005-model-config.sql`：模型三键 seed
- `ruoyi-heron/pom.xml:16`：默认排除组由 `exclude,mysql` 扩为 `exclude,mysql,render` —— 真渲染测试要几分钟起 Chrome/ffmpeg，不挂在每次构建上。跑法：`mvn -pl ruoyi-modules/ruoyi-heron -am test -Dheron.test.excludedGroups=exclude,mysql -Dtest=NewsProductionPipelineRenderTest`；`HERON_TEST_NODE` 指向绝对 node 路径，否则 PATH 上的 20.x 会被 hyperframes 拒（F20）

**模板（不进 Git）**
- `data/templates/news-9x16/`：`index.html`、样式、字体、`*.motion.json`、`assets-manifest.json`（固定 `<audio src="narration.mp3">`，无初始化脚本）

**前端**
- `src/views/heron/news/work/index.vue`：模板/音色固定值 + 改掉 `|| 'default'` 兜底

**依赖（本轮实测待办）**
- `nvm use 24.22.1`（Node 22+）、安装并钉版 hyperframes 到 `data/tools/`、Chromium（由 hyperframes 取）；edge-tts 7.3.1 已装、FFmpeg 已装
- 不新增 Java/Maven 依赖

### 6.1 M2 落地实况与设计条目的差异（实现后回填）

| 设计条目 | 实际落地 | 原因 |
|---|---|---|
| 新增 `NewsTemplateVariables` / `NewsCompositionPatch` / `NewsAssetService` 三个类 | **只新增 1 个类** `NewsProductionPipeline`，映射与补丁是它的包级静态方法（`templateVariables`、`timeCompositionString`、`subtitleJson`） | 三个类都只被流水线调一次；原型线就是一个类装全部（`ProtoProductionPipeline` 191 行），照 house pattern 走 |
| §3.4 变量表含 `scene{n}_seconds` | 取消。段落秒数只写进磁盘 HTML，不进变量 | 根时长编译期读一次（F1），发出去也没人读 |
| §3.4 变量表含 `accent` | 不发。模板默认值 `#1b6c80` 生效，CSS 用 `var(--accent)` | 单一品牌色没有运行期变化，发它只多一个必对项 |
| §3.1 能力检查「新闻工具清单加两项」 | 除清单外还改了 `resolvable()`（加 `case "hyperframes"` 并对 null 直接判不可用）与模板判据（由 `startsWith("prototype")` 改为两条线都要求模板在场） | 不改这两处，hyperframes 会被拿去探测 zee-rp 根目录、新闻模板缺失永远报不出来（F6/F7） |
| §3.5 `visual` 闸用 `--strict` | 改为 `check --json` 判 `errors==0` 且 `contrast/runtime/motion` 为空，warning 只进 `measured.checkWarnings`（F16） | 模板自检固有 14 条 warning，挂 strict 等于永远红 |
| §3.5 `aigc_metadata` 判定 | 额外强制**读回** `ffprobe format_tags=AIGC` 并逐字比对声明文本 | 不带 `+use_metadata_tags` 时 ffmpeg 退出 0 而标签根本没写（F15），只看退出码会放行无声明成片 |
| §3.6 `subtitles` 产物 | 内容为 `[{text,startMs,endMs}]` 的 cue 列表（不是 `word`） | edge-tts CLI 只给整句 cue（E8/F3） |
| §4.3 `worker-enabled` 到 N3 才打开 | dev yml 现在就置 `true` | 不打开就没有 `ProductionRunner` bean，M3 提交的任务永远不会被执行，N1 无法验 |
| §7 M0 出口「一条 commit」 | 已提交 `de6822a6e`（63 文件）；前端固定键改动发生在其后，随 M2 一起走 | — |
| §8 验收 9「checks 自证单测」 | **未做**：`validateChecks` 经 `JsonUtils` 静态初始化，脱离 Spring 就 `ExceptionInInitializerError`，纯单测借不到它 | 该契约改由 M3 真实跑（提交→完成全流程过 `complete()`）验证；不为它单独起一个 Spring 测试上下文 |

新增单测覆盖：`NewsProductionPipelineTest`（映射确定性、时间轴缩放与末段余量、缺槽位即失败、cue 解析）、`ProductionProcessTest.runsChildWithoutGuard`（no-guard 分支在任意平台真起子进程且不落 `guard-identity.json`）、`ProductionCapabilityTest.newsReportsMissingRendererAndTemplate`（`tool_missing:hyperframes` + `template_missing`）；`rejectsInvalidCommandsBeforeStart` 的越界样本由 300 s 改 700 s（常量抬到 600 的连带改动）。

M3 还缺的两件都不在代码里：`sys_config` 里 `heron.model.*` 的真实值（E3 由用户填），以及 `heron.news.allowed-hosts` 至少一个泛资讯域名（否则 `NewsSourceFetcher` 的地址闸直接拒绝抓取）。

---

## 7. 实现顺序

| 里程碑 | 内容 | 出口 |
|---|---|---|
| **M0 基线** | 两段后端验证 + 前端类型检查（命令见 §7.1）；全部通过后**报请用户批准**再提交 in-flight | 干净基线 + 一条 commit（提交需单独授权，不由「写代码」推定） |
| **M1 模板** | 装 hyperframes 并钉版 → 生成 `news-9x16` → `lint` 0 error、`check --json` 0 error → `preview` 看过 → `render --quality looks --workers 1` 出样片 → 算 `assets-manifest.json` 与哈希 | 模板 + 清单 + 一条样片 |
| **M2 G1 流水线** | `NewsProductionPipeline` + 变量映射 + 磁盘补丁 + 十道闸 + 配置 + 能力检查 + 单测（假进程） | 单测绿；缺件时能力如实报不可用 |
| **M3 N1/N2 真跑** | 打开 `pipeline-enabled`，真实候选 → 抓正文 → 模型写稿 → TTS → check → render → decode → AIGC → 审片 | 一条真 mp4（9:16、30–90s、有声、AIGC 标签、来源行） |
| **M4 N3 闭环** | 打开 `collection-enabled` + `allowed-hosts` + `worker-enabled`；候选→认领→自动生产→审片确认→人工发布回填 | 列表出现「人工已发布」，`published_at` 有值 |

> M3 的真实候选必须来自采集（`input.evidence.items[].sourceUrl` 有值）。手工条目 `sourceUrl` 为 null，会被 `409 news_script_source` 直接拒（需求文档 §3.2 已写死），拿它验 N2 是无效测试。

### 7.1 M0 的两段后端验证（原文一条命令覆盖不到）

`ruoyi-heron/pom.xml:16` 有 `<heron.test.excludedGroups>exclude,mysql</heron.test.excludedGroups>` —— 默认 `mvn package` **不跑** 15 个 MySQL 集成测试类。只跑第一条就报「测试全绿」是自欺。

```
# ① 编译 + 单元测试（默认排除 mysql）
mvn -pl ruoyi-admin -am package
# ② MySQL 集成测试（需隔离库，见 §9-R13）
export HERON_TEST_MYSQL_URL='jdbc:mysql://localhost:3307/heron_it_local?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=GMT%2B8&zeroDateTimeBehavior=convertToNull&nullCatalogMeansCurrent=true'
export HERON_TEST_MYSQL_USER=root HERON_TEST_MYSQL_PASSWORD='heron!2026'   # 隔离栈的 3307，不是用户那台 3306
mvn -pl ruoyi-modules/ruoyi-heron -am test -Dheron.test.excludedGroups=exclude
```

集成测试强制三个环境变量，且库名必须以 `heron_it_` 前缀隔离，否则 `IllegalStateException`（`HeronMysqlTestSupport.java:175-195`）：`HERON_TEST_MYSQL_URL` / `HERON_TEST_MYSQL_USER` / `HERON_TEST_MYSQL_PASSWORD`。**集成测试必须按 Spring 上下文分组跑，不能整模块一次跑完**（2026-11-03 实测定性）：三条支持基类各自建一个上下文与一套 Hikari 池，同一个 JVM 里三个上下文并存时，一个池留下的未结束事务会锁住 `heron_production_task` 的行，另一个池的 `UPDATE ... SET status='running'` 直接等到 `innodb_lock_wait_timeout`。整模块一次跑的表现是**级联假故障**：`ProductionAccessTest` 2 项 50 s → `ProductionCleanupTest` 1 项 → `ProductionLeaseTest` 4/4 → `ProductionRecoveryTest` 7/7 → `ProductionTransactionTest` 54/54，实测 `information_schema.INNODB_TRX` 里同时有 `Sleep` 但持锁的事务与 `LOCK WAIT` 的更新。分组跑则全绿：

```
# A 组（HeronMysqlTestSupport 基类上下文）84 tests / 0 fail
mvn -pl ruoyi-modules/ruoyi-heron -am test -Dheron.test.excludedGroups=exclude \
    -Dtest='Production*Test,HeronMenuSeedTest,ProductionSchemaTest'
# B 组（NewsMysqlTestSupport，news 上下文）34 tests / 0 fail
... -Dtest='News*Test'
# C 组（ProtoMysqlTestSupport，proto 上下文）19 tests / 0 fail（1 skipped）
... -Dtest='Proto*Test'
```

副作用要记：中途 `TaskStop` 只杀了 mvn，surefire 的 fork JVM（命令行里带 `surefirebooter-`）活着继续跑并占着库连接 —— 下一轮开跑前必须确认 `information_schema.INNODB_TRX` 与 `processlist WHERE db='heron_it_local'` 都为 0，否则又是一轮假故障。

**本机可用入口（2026-11-03 起改为隔离栈）**：

- **不要动用户正在跑的 `mysql` / `redis` 容器。** 那台 `mysql` 里同时有 `heron`、`ry_vue`、`xy_wh` 等无关库，`root@'%'` 是他多套工具共用的凭据；我曾按授权把它改成 yml 里的 `heron!2026`，随即发现打断了用户的其它连接并已回滚原值。**纪律：不改任何现有账号口令，不在别人的实例上建库。**
- 替代做法：另起一套隔离开发栈 `script/docker/docker-compose.dev-isolated.yml`（容器 `heron-dev-mysql` / `heron-dev-redis`，端口 **3307 / 6380**，独立卷，口令沿用仓库里已明文的 `heron!2026`，不引入新密钥）。起法：`docker compose -f script/docker/docker-compose.dev-isolated.yml up -d`。
- 该实例已灌入 `script/sql/ry_vue.sql` + `ry_ai.sql` + `heron/001–005`（57 张表、143 条菜单、6 条 `sys_config`），并另建空库 `heron_it_local` 供集成测试自举。
- `application-dev.yml` 的 datasource 端口改 3307、redis 端口改 6380（DB 口令那行不动，仍是仓库里已有的明文）；跑后端只需 `REDIS_PASSWORD=heron!2026`。用户自己那套 3306/6379 与其中 `heron` 库**保持原样**（我曾往里应用过 001–004/005，新增 10 表 + 34 菜单 + 3 配置行，未删改任何既有对象）。
- 集成测试 URL 必须匹配 `jdbc:mysql://<host>/<heron_it_[a-z0-9_]+>(\?.*)?`（`HeronMysqlTestSupport:41-45,178-180`），实测可用参数：`useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=GMT%2B8&zeroDateTimeBehavior=convertToNull&nullCatalogMeansCurrent=true`。harness 每类 `@BeforeEach` 自己 `DROP` 再按 001/002/003 重建，空库即可。
- 中途 `TaskStop` 只杀 mvn，surefire 的 fork JVM（命令行含 `surefirebooter-`）会活着继续占库连接；下一轮开跑前先确认 `information_schema.INNODB_TRX` 与 `processlist WHERE db='heron_it_local'` 归零。



### 7.2 M0 实测结果（2026-11-03 本机）

| 项 | 结果 |
|---|---|
| ① `mvn -pl ruoyi-admin -am package` | **PASS**，退出 0，产物 `ruoyi-admin/target/heron.jar`（270 MB） |
| heron 单元测试（10 类） | **68 tests / 0 failures / 0 errors / 5 skipped** |
| 5 个 skipped | `ProductionProcessTest` 的 Linux 用例 `@EnabledOnOs(OS.LINUX)`，Windows 上本来就跳过 —— 不是本轮引入的回归，但意味着 §4.2 的 no-guard 分支**在 Windows 上一个测试都没盖到**，M2 要补 Windows 可跑的用例 |
| ② MySQL 集成测试（按上下文分三组，见上） | **PASS**：A 组 84 / B 组 34 / C 组 19 = **137 tests, 0 failures, 0 errors, 1 skipped**，三组各自 BUILD SUCCESS。整模块一次跑会级联假故障，不是代码问题 |
| 前端 `vue-tsc --noEmit` | **PASS**，退出 0。前提是 `nvm use 24.22.1` + `pnpm install --frozen-lockfile`：原 `node_modules` 装残缺（无 `.bin`、缺 `@babel/parser`），Node 20.9.0 下 pnpm 直接罢工。这条解开了「前端类型检查一律记 NOT RUN」的旧阻塞 |

### 7.3 原型线 P2 核实结论（已从「待核实」改为已核实）

| 事项 | 结论 | 证据 |
|---|---|---|
| 下载鉴权（越权 404） | **已落** | `ProtoController.java:105,112` 两个下载口；`ProtoAccessTest.crossUserProjectVersionAndArtifactAllReturnReal404` 覆盖项目/版本/产物三层越权 |
| 用量记录 | **已落，形态是任务详情内嵌表，不是独立用量页** | `ProductionServiceImpl.java:149` 从 `HeronUsageRecordMapper` 填 `ProductionTaskVo.usage`（:50）；前端 `views/heron/production/index.vue:167` 渲染 |
| 版本快照 | **已落** | `HeronProtoVersion.inputSnapshot`（:23）+ `ProtoVersionTransactionTest` |

→ 本轮原型线**没有需要补的功能**，P2 三项都在；需求文档 §7 的 P2 验收里「用量页有记录」按内嵌表口径验收。

### 7.4 M1 / M2 出口实测（2026-11-03）

| 判据 | 实测 |
|---|---|
| 模板过闸 | `lint` 0 error / 9 warning；`check --json` `ok=true`，`motion/contrast/runtime` 三段 `errorCount=0` |
| 不经模型的完整链路 | `NewsProductionPipelineRenderTest`：固定稿件 → 真 `edge-tts` → `ffprobe` → 磁盘时间轴补丁 → `check` → `render` → decode → AIGC → 联络表 → 六类产物定稿，**121 s 通过** |
| 成片实测 | `1080x1920`、`30 fps`、1 视频 + 1 音频、`duration=54.43 s`、`TAG:AIGC=内容由 AI 生成`、`TAG:hyperframes_version=0.9.6`（需求文档 §6「渲染版本进产物记录」由容器自带标签满足） |
| 十道闸 measured | `audio{audioMs:54408,voiceBytes:326448}`、`subtitles{cues:10,coveredMs:50200,audioMs:54408}`、`decode{durationMs:54433,fps:30,videoStreams:1,audioStreams:1}`、`render{bytes:3328701,exitCode:0}`、`visual{checkErrors:0,checkWarnings:9,blankFrames:0}`、`aigc_visible{motionSamples:300,badgeAssertion:true}`、`assets` 三项 true、`schema{violations:0}`、`evidence` 三项 true、`aigc_metadata{aigcTag:true}` |
| U5 渲染耗时 | 整条链路 121 s（含 TTS 与 check），**超出需求文档 §3.1「渲染 ≤60 秒」目标**；认领→可审仍在 5 分钟内。要么降 `--quality`/调 `--workers`，要么把 60 秒改成实测口径 —— 需用户定 |
| 字幕覆盖 | 末条 cue 50.2 s ÷ 音频 54.4 s = 92.2%，按 ≥90% 判据通过 |

测试产物落在被忽略的 `data/it-render/<uuid>/videos/10/v5/a1-<token>/`（六件齐全，可直接放给人工看片）。这不是任务产物路径，只是测试落点；真任务仍走 `ProductionArtifactService` 的 `videos/<workId>/v<versionId>/a<attempt>-<runToken>/`。

### 7.5 M3 / M4 真闭环实测（2026-11-04）

走的是真后端 + 真浏览器，不是单测：

| 步骤 | 实测 |
|---|---|
| 采集 | 来源页新增 `IT之家 数码快讯` → `https://www.ithome.com/rss/`，「立即采集」落库 **60 条候选**（走 `NewsUrlPolicy` + 地址闸 + DNS 钉死那套共享代码） |
| 认领→草稿→提交 | 认领首条（汽车类，非时政）→ 保存草稿（模板/音色为锁定只读值）→ `generate` 返回 `taskId` + `status=queued` |
| 自动生产 | worker 领取后真跑到成片：**140 秒 succeeded**。落库 `duration_ms=54408`、`checks` 10 项、`artifact_manifest` 6 件、`script` 存稿件原文 |
| 失败不留半份 | 前 3 个版本（抓取不可达 / 版式闸 / 定位符违规）在 `heron_video_version` 里 `script`、`checks`、`duration_ms` 全为 NULL |
| 人工确认 | `review approved` 必须带 `contactSha256` + `videoSha256` 两个完整哈希，通过 |
| 人工发布 | `publish` 写 `publish_source=manual`、`publish_at=2026-11-04 02:29:47.906`，候选 `published_at` 同一毫秒值；真实浏览器列表页显示「**v4 · 已通过　人工已发布**」 |

**本轮写稿用的是本地假 OpenAI 端点**（`data/tools/model-stub.mjs`，返回固定稿件，只服务本机验证、不进 Git）。因此已被证实的是接缝：HTTP 往返、契约闸、用量记账、渲染链、闸报告、落库与人工腿。**未验证的是模型真实写稿质量**（需求文档 §8-U6 的一次通过率仍未测）—— `heron.model.*` 填真值后重跑同一条候选即可。

遗留一条真缺陷：失败任务已 `finalizeFile` 搬进 `videos/<workId>/v<versionId>/a<attempt>-<token>/` 的产物，在被 `complete()` 拒收后成为**无人引用的孤儿目录**（清理服务按任务清单找候选，扫不到它们）。不属本轮范围，记为 R19。


---

## 8. 验收判据（可执行）

1. 模板：`hyperframes check --json` 的 `errors` 为 0 且 `contrast/runtime/motion` 三段为空（不挂 `--strict`，理由见 §3.3）；preview 里 AIGC 角标与来源行可见。
2. 出片：`ffprobe` 报告 `1080x1920`、30–90s、1 路视频 + **1 路音频**；`format_tags` 里 `AIGC` 有值。
3. 契约：构造缺字段/超时长/标题重复的稿件 → 任务失败，原因列出条目，不写版本。
4. 抓取失败 → 任务失败并指出 URL 与失败类，**不退回只用标题**。
5. TTS 失败 → `audio` 闸失败、原因入库、无本地音色兜底。
6. 重渲 → 新版本 + 新指纹，原人工确认失效需重确认（现有逻辑）。
7. 发布：回填后列表显示「人工已发布」，`published_at` 有值；重复回填不增记录。
8. 能力：缺模板 → `template_missing`、缺 CLI 入口 → `tool_missing:hyperframes`、缺 Python → `tool_missing:python`、开关未开 → `worker_disabled` / `pipeline_missing`，提交接口按 `available=false` 拒绝且原因具体，不是运行期才炸。（这一条要求 §3.1 的两处能力改动先落地，否则新闻模板缺失报不出来，F7。）
9. `checks` 自证：任一项 `measured` 塞字符串、或 `reason` 带路径分隔符 → `complete()` 判 `409 news_checks_invalid`（用单测锁，防流水线哪天写飘）。

---

## 9. 风险与待实测

| # | 事项 | 处理 |
|---|---|---|
| R1 | ~~根 `data-duration` 省略后能否推导总长~~ | **已闭合**（F1）：根时长编译期读静态属性、变量与脚本改不了 → 本轮由 Java 改磁盘上的 HTML，不省略根时长 |
| R2 | ~~`check` 是否接受 `--variables-file`~~ | **已闭合**（F2）：不接受。版式闸跑默认值，变量合规由 `render --strict-variables` 兜 |
| R3 | Windows 无头 Chromium 下 `check` / `render` 稳定性 | 实测三条样片；失败则改 `render --docker`（需 Docker Desktop） |
| R4 | edge-tts 出网可用性与中文断句 | **出网已实测通过**（30 字整句 → 34 KB mp3 + 一条 cue）。剩「人听合格」仍需三条真实稿件；接口失效报 `audio` 类，不降级 |
| R5 | 模型一次通过率（D6/U6） | 10 个真实需求样本统计；过低才讨论骨架库 |
| R6 | 开源中文字体授权与字形覆盖 | 只用思源黑体 / Noto Sans SC；禁用微软雅黑 |
| R7 | Windows 绕过 guard 失去 rlimit + 终止无法确认 | 显式开关 + §4.2 三条代价写进开发者须知；生产 Linux 默认 true |
| R8 | hyperframes 版本漂移 | 本机**当前未安装**（F12）；装上后钉版本 + 记录 commit，升级跑固定回归样本 |
| R9 | 渲染 >240 s 被 `MAX_TIMEOUT_SECONDS` 拒（400）；进度输出撞 1 MiB（409） | 常量抬到 600 + 命令一律静默/`--json`、长输出落文件；实测 P50/P95 记回 U5 |
| R10 | hyperframes 匿名 telemetry / `feedback` 上报 | 自部署开源产品默认关掉（`doctor`/`telemetry` 具体开关 M1 实测确认），不进我们脚本的自动上报路径 |
| R11 | `sweep_static`：全程静止的时间轴会让 `check` 直接失败且一切绿判定不可信 | 模板保留一个持续运动元素；M1 首条样片验 |
| R12 | 15 个 MySQL 集成测试类默认被 `heron.test.excludedGroups=exclude,mysql` 排除（`ruoyi-heron/pom.xml:16`） | M0 必须跑第二段命令（§7.1），否则「测试绿」只覆盖 10 个单元测试类 |
| R13 | 集成测试要 `HERON_TEST_MYSQL_URL/USER/PASSWORD` 且库名 `heron_it_` 前缀 | 已解：隔离栈 3307 上建 `heron_it_local`，三组分别跑，137 项全绿 |
| R14 | `guard-enabled=false` 这条新路径的覆盖 | 已补 `ProductionProcessTest.runsChildWithoutGuard`（任意平台真起子进程、不落 guard-identity）；仍缺超时与 1MiB 两条分支的 no-guard 用例 |
| R15 | 失败任务已搬进 `videos/…/a<attempt>-<token>/` 的产物，被 `complete()` 拒收后成为无人引用的孤儿目录 | 清理服务按任务清单取候选，扫不到它们。本轮不修（属清理侧范围），要避开就先让 checks 过再 finalizeFile |
| R16 | 渲染耗时目标 60 秒 vs 实测：测试链路 121 秒、真任务 140 秒（`looks` + `--workers 1`） | 没有擅自降到 `draft`（画质换速度不该由实现侧决定）。要么把目标改成实测口径，要么 `draft` 出草片、`looks` 只用于定稿 |
| R17 | hyperframes 的匿名 telemetry / `feedback` 上报仍未逐条核实关法 | M1 只确认 `doctor` 可跑；部署前把上报关掉并记录开关名 |

---

## 10. 与需求文档的差异（供评审）

1. **模板归属**：§3.3「人工交付」→ 本轮 Claude 生成（E1）。
2. **本轮不接三件**：§3.2 ①②③ → 本轮全部接上（模板存在后不再靠想象写映射）。
3. **edge-tts 运行时**：§6「node-edge-tts」→ 本轮用 Python `edge-tts`（已装 7.3.1，出网实测通过）。
4. **字幕粒度**：§2-D3 / §3.4「一次调用同时给**词级**时间戳」→ CLI 实测只出 **cue 级**（F3/E8）；词级需 Python API 脚本，本轮不做，`subtitles` 闸的判据相应改写。
5. **新增开关**：`guard-enabled`、`pipeline-enabled`、`hyperframes` 路径、超时常量抬到 600——需求文档 §0 默认把新增开关视为越界，这四项是实现所必需，列此备案。
6. **Windows 可跑性**：§6「Windows 开发机不能直跑渲染，本地开发用 `render --docker`，此约束写进开发者须知」→ 本轮用 `guard-enabled=false` 在 Windows 直跑，把「guard 只能 Linux」与「渲染只能 Linux」分开；代价与「终止无法确认」照 §4.2 写进须知。
7. **`process_guard` 平台口径**：Linux 生产不变；Windows 是显式绕过，不是自动降级。
8. **模板时间轴写法**：§3.3 未规定 → 本轮定「Java 改磁盘 HTML，根时长写死」，不引入运行时初始化脚本（F1）。

---

**请评审**：§0.1 修正记录（F1–F14）是否照收、§3.3/§3.4 模板与变量契约（时长走磁盘补丁）、§3.5 `reason`/`inputLocator` 口径与原文落点、§4.2 guard 三条代价、§7 顺序与 M0 提交授权点、§10 差异清单。
