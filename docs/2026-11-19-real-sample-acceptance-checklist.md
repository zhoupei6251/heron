# 真实样片验收执行清单（2026-11-19）

> 用途：为「真实样片验收」这一轮准备一份可执行、可复核的清单。**本轮不跑。**
> 状态：清单已就绪；音频那一节（§4）明确标注**等后端工程师的音频闸落地后再跑**。

---

## 0. 前置条件与边界

### 0.1 硬边界（跑之前必须确认）

| 项 | 要求 |
| --- | --- |
| 后端工程师的改动窗口 | **未结束不跑真实渲染。**渲染结果要和他的改动对账，他改着闸的时候跑出来的片子说明不了任何事 |
| 工作区状态 | 每条片子渲染时记录 `git rev-parse HEAD` + `git status --porcelain` 的完整输出 + `git diff --stat`。片子必须能对上"这是哪份代码出的" |
| 数据库 | 只用隔离库 `heron_it_render`（`localhost:3307`），**不碰 `heron_prod`** |
| 产物落点 | 全部落 `D:\zhoupei\heron\docs\acceptance-2026-11-19\<film-id>\`，不进 git，不 commit、不 push |
| 禁止 | 为了让片子好看去改生产代码的闸门、放宽事实校验、用关键词猜画面强调哪个数字、编造因果关系 |

### 0.2 工具链（已在本机验证可用）

| 工具 | 路径 | 版本 |
| --- | --- | --- |
| JDK | `D:\java\jdk21` | 21 |
| Maven | `D:\maven\apache-maven-3.9.11\bin\mvn` | 3.9.11 |
| Node | `D:\nvm\nodejs\node.exe` | ≥ 22（渲染前提） |
| ffmpeg / ffprobe | `D:\ffmpeg\ffmpeg-master-latest-win64-gpl-shared\bin\` | N-122527-g4561fc5e48 |
| Python | `D:\python\python.exe` | 带 pymysql |

依赖还原（clone 后必做，`node_modules` 不进仓库）：

```bash
cd D:/zhoupei/heron/data/tools && npm ci
```

### 0.3 环境变量

默认值相对仓库根 `./data/...`，**从 `heron/` 目录启动**。覆盖用下面这组：

```bash
# Git Bash（本轮实际用的写法）
export HERON_NODE="D:/nvm/nodejs/node.exe"
export HERON_HYPERFRAMES="./data/tools/node_modules/hyperframes/bin/hyperframes.mjs"
export HERON_NEWS_TEMPLATE="./data/templates"
export HERON_PASSWORD="heron!2026"          # 本机 redis（localhost:6380）
```

对应配置落点：`ruoyi-admin/src/main/resources/application-dev.yml:334-337`
（`heron.node` / `heron.hyperframes` / `heron.news-template`）。

启动应用（与当前 dev 进程同款，另起端口避免撞车）：

```bash
cd D:/zhoupei/heron
"D:/java/jdk21/bin/java" -jar ruoyi-admin/target/heron.jar \
  --server.port=8086 \
  --captura.enable=false \
  --heron.agent.enabled=true
```

> `SA_TOKEN_JWT_SECRET` 见 `docs/2026-11-19-acceptance-report.md`（本轮安全改动已改配置，缺密钥起不来）。

---

## 1. 三条风格各取一条片子

风格不是"渲出来的样子"，是**仓库里已有的模板 + 生成器**。三套都是 `line=DATA_BOARD` 的既有模板：

| 风格名 | 模板 key | 目录 | 声明的视觉语言 |
| --- | --- | --- | --- |
| Dynamic Data Explainer | `news-data-9x16` | `data/templates/news-data-9x16/` | 动态数据解释：折线/排行/赛跑/堆叠/多线/雷达 |
| Data Investigation | `news-data-investigate-9x16` | `data/templates/news-data-investigate-9x16/` | 数据调查纪录片：慢节奏、大字重差、颗粒质感 |
| Professional News | `news-data-broadcast-9x16` | `data/templates/news-data-broadcast-9x16/` | 专业新闻播报：下三分之一信息条、红色立标、克制动效 |

### 1.1 「风格来自模板与生成器，而不是手工改生成产物」的保证办法

这是本清单最容易造假的一环，所以要求写成可检查的动作：

1. **模板产物必须是生成器产出的，不是手改的。**
   ```bash
   cd D:/zhoupei/heron
   node data/tools/build-templates.mjs --check          # 只比对，不写盘
   node data/tools/build-templates.mjs --verify         # 生成后跑 hyperframes check
   ```
   `--check` 的输出**必须全绿**。如果某个模板的 `index.html` 与生成器输出不一致，说明有人在生成产物上手改过 —— 这条不通过，片子不能作为证据。

2. **模板目录里不许有生成产物之外的私货。**
   每个模板目录只有 `LOCK.json` / `assets-manifest.json` / `gsap.min.js` / `index.html` / `index.motion.json` / `narration.mp3`。多出来的文件（例如手写的 `bgm.mp3`、改过的 `index.html`）一律视为手工改动。

3. **LOCK.json 要能对得上。**
   ```bash
   for t in news-data-9x16 news-data-investigate-9x16 news-data-broadcast-9x16; do
     echo "== $t"; cat "data/templates/$t/LOCK.json"
   done
   ```
   记录 LOCK 里的内容哈希；片子渲染前后各读一次，两次一致才说明渲染期间没人动过模板。

4. **渲染产物的画面来源只有一个**：`composition/index.html` 由 `copyTemplate()` 从模板目录复制而来（`NewsProductionPipeline.copyTemplate`），再由 `vars.json` 注入变量。片子渲染完把 `composition/index.html` 与 `data/templates/<key>/index.html` 做一次 diff：
   ```bash
   diff "data/templates/news-data-9x16/index.html" "<work>/composition/index.html"
   ```
   差异只能是生成器注入的占位符/字体引用。出现手改的样式差异 → 作废重来。

5. **配色不手挑。**风格差异由 `template.config.json` 的 `style` 字段决定：
   - `news-data-9x16` → `style=data-board`，布局 `data/templates/layouts/data-board/news-data-9x16.css`
   - `news-data-investigate-9x16` → `style=data-investigation`，布局 `layouts/data-investigation/…`
   - `news-data-broadcast-9x16` → `style=news-broadcast`，布局 `layouts/news-broadcast/…`
   三个布局 CSS 是分开的，天然不会互相污染。

---

## 2. 每条片子的执行步骤

### 2.1 选题：走机会，不手挑数据

为了让 §3 的 sha 比对有意义，选题必须来自**数据机会**（否则冻结输入没有 `datasetWindow` 节点，走的是兼容分支）。

```bash
# 1) 触发一次数据机会分析
curl -s -X POST "http://localhost:8086/heron/dataOpportunity/analyze" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{}' | tee "$FILM_DIR/opportunity.json"

# 2) 看有哪些机会可用（挑一个 enabled 且 probe_status=ok 的源）
python - <<'PY'
import pymysql, json
c = pymysql.connect(host='127.0.0.1', port=3307, user='root', password='heron!2026', database='heron_it_render')
cur = c.cursor()
cur.execute("SELECT opportunity_id, source_key, headline, shape, window_points FROM heron_data_opportunity ORDER BY create_time DESC LIMIT 20")
for r in cur.fetchall(): print(r)
PY
```

**挑源规则（写死，不许临场挑）**：`probe_status='ok'` 且 `enabled='1'`；形态属于 `wide/long/rows`（`geo` 会在取数阶段被拒，见 §5）；`window_points` 与分析窗口一致（120）。

### 2.2 生产：跑完整链路

```bash
# 认领并生成
curl -s -X POST "http://localhost:8086/heron/newsWork/<workId>/generate" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' \
  | tee "$FILM_DIR/generate.json"

# 三套风格各跑一条：模板由线内编辑按机会自动选（NewsTemplatePicker.pick），
# 所以「风格」必须在冻结输入里确认，见 §3.3；不允许靠改模板 ID 硬凑风格。
```

产物落点（`ProductionArtifactTest` / `prepareWorkDirectory`）：

```
D:/zhoupei/heron/data/work/news_video/<taskId>/<runId>/
  composition/          # 复制出来的模板 + 注入的变量 + 素材
  narration.txt
  vars.json
  raw.mp4               # 无 BGM 的原始渲染
  final.mp4             # 混入 BGM + AIGC 元数据的成片
```

### 2.3 冻结输入留证

```bash
python - <<'PY'
import pymysql, json, sys
c = pymysql.connect(host='127.0.0.1', port=3307, user='root', password='heron!2026', database='heron_it_render')
cur = c.cursor()
cur.execute("SELECT context_id, content_hash, content_json FROM heron_production_context ORDER BY create_time DESC LIMIT 1")
cid, chash, cjson = cur.fetchone()
open(sys.argv[1], 'w', encoding='utf-8').write(cjson)
print('context_id=', cid, 'content_hash=', chash)
PY
```

---

## 3. 机器证据（每条片子都要留）

> 每一项都落成文件，文件名带片号，便于交叉核对。

### 3.1 容器与流（ffprobe）

```bash
ffprobe -v error -show_format -show_streams -of json final.mp4 > "$FILM_DIR/ffprobe.json"
```

必查字段（人工核对，写进报告）：

| 字段 | 期望 |
| --- | --- |
| `format.duration` | 与脚本声明的总时长一致（±0.5s） |
| 流数量 | 1 视频 + 1 音频（无 BGM 时是 1 视频 + 1 音频，BGM 混进去也是 1 音频） |
| 视频 `width`×`height` | 1080×1920（三套模板都是竖版） |
| 视频 `r_frame_rate` | 与 `index.motion.json` 声明一致；不得出现 0/0 |
| 视频 `nb_frames` | ≈ duration × fps，误差 < 1% |
| 音频 `sample_rate` / `channels` | 48000 / mono 或 stereo，与 `bgmFilter` 输出一致 |
| 音频 `duration` | ≈ 视频 duration |

### 3.2 整体响度（ebur128）

```bash
ffmpeg -hide_banner -nostats -i final.mp4 -filter_complex ebur128=peak=true -f null - 2> "$FILM_DIR/ebur128.txt"
```

必查（写在报告的「机器证据」栏）：

- `Integrated loudness`（I，LUFS）
- `Loudness range`（LRA）
- `True peak`（dBTP）
- `Sample peak`（dBFS）

### 3.3 旁白段与片尾段各自的 BGM-旁白电平差（新闸门的口径）

这是本轮最关键的一项，**口径必须与后端工程师落地的闸一致**。当前实现的包络在
`NewsProductionPipeline.bgmFilter`（`NewsProductionPipeline.java:732-745`）：

- 正文段 BGM 增益 **0.22**
- 进入实际结尾窗口（`closing-slot` 起点）后线性升到 **0.40**
- 结尾后 `fade` 秒淡出

因此测量方法：**同一段音频，分别量「只有旁白」的源文件和「混完 BGM 之后」的成片**，
两者相减就是 BGM 引入的电平差。分两段做：

```bash
# a) 拿旁白干声（渲染中间产物）
#    narration 的实际区间来自 NewsNarrationTiming.align 的字幕对齐结果，
#    从 composition/ 下的字幕文件读；起点/终点写进 report
ffprobe -v error -show_format -of json "$WORK/composition/<subtitles>.json" > "$FILM_DIR/subtitles.json"

# b) 旁白段：[0, closingStart)   片尾段：[closingStart, closingEnd]
#    从 raw.mp4（只有旁白，无 BGM）切这两段，各量一次 LUFS
ffmpeg -hide_banner -nostats -ss 0 -t "$BODY_END" -i raw.mp4 \
  -filter_complex ebur128=peak=true -f null - 2> "$FILM_DIR/ebur128_narration_body.txt"
ffmpeg -hide_banner -nostats -ss "$CLOSING_START" -t "$CLOSING_LEN" -i raw.mp4 \
  -filter_complex ebur128=peak=true -f null - 2> "$FILM_DIR/ebur128_narration_closing.txt"

# c) 混完之后同样两段
ffmpeg -hide_banner -nostats -ss 0 -t "$BODY_END" -i final.mp4 \
  -filter_complex ebur128=peak=true -f null - 2> "$FILM_DIR/ebur128_mix_body.txt"
ffmpeg -hide_banner -nostats -ss "$CLOSING_START" -t "$CLOSING_LEN" -i final.mp4 \
  -filter_complex ebur128=peak=true -f null - 2> "$FILM_DIR/ebur128_mix_closing.txt"

# d) 差值
#    旁白段差 = L(mix_body)  - L(narration_body)      <- 应为负（BGM 被压在旁白下）
#    片尾段差 = L(mix_closing) - L(narration_closing) <- 片尾 BGM 抬高，差值应大于旁白段差
```

> **本节等后端工程师的音频闸落地后再跑。** 阈值（旁白段差值下限、片尾段差值上限、
> 真峰值上限、削波判定）要以他落地的闸为准；闸没落地之前，上面的命令可以跑出
> 数字，但**数字不构成通过/不通过的结论**，只作为对照基线存档。

补充：单独量 BGM 源本身，确认起点电平（`data/audio/bgm/*.mp3`，6 首轮换）：

```bash
for f in data/audio/bgm/*.mp3; do
  echo "== $f"
  ffmpeg -hide_banner -nostats -i "$f" -filter_complex ebur128=peak=true -f null - 2>&1 \
    | grep -E "I:|LRA:|Peak:|True peak"
done
```

### 3.4 闸门结果

闸门结果落在生产任务的运行记录里，不在文件里 —— 从接口取：

```bash
curl -s "http://localhost:8086/heron/newsWork/<workId>" -H "Authorization: Bearer $TOKEN" \
  | tee "$FILM_DIR/gates.json"
```

必查（对应本轮验收的三条 P0）：

| 闸 | 期望 |
| --- | --- |
| `data` | `reason` 里写明「取数口径与冻结输入一致」；`measured.shaMatched` 是 true/false |
| `data` | 若 `shaMatched=false`，`reason` 里**必须同时出现期望 sha 与实得 sha**（`NewsProductionPipeline.java:328-329`） |
| `data` | `measured.windowFrozen=true`、`measured.windowPoints` 与冻结口径一致 |
| `dataset_evidence` | 通过 |
| `decode` | 容器、尺寸、流数与时长实测通过 |
| `aigc_metadata` | ffprobe 读回的 AIGC 文本与声明一致 |

### 3.5 sha 比对（脚本与快照一致性）

三层证据，缺一不可：

```bash
# 1) 冻结输入里的期望口径与期望哈希
python -c "
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))
w=d['selection'].get('datasetWindow')
print('datasetWindow =', json.dumps(w,ensure_ascii=False,indent=2))
print('editorial.storyAngle =', d['editorial'].get('storyAngle'))
print('editorial.editorialIntent =', d['editorial'].get('editorialIntent'))
print('editorial.opportunityHeadline =', d['editorial'].get('opportunityHeadline'))
" "$FILM_DIR/frozen_input.json" | tee "$FILM_DIR/window_report.txt"

# 2) 生产实际取到的快照哈希（从运行记录 / dataset.json 读）
# 3) data 闸 measured.shaMatched
```

判读：

- `datasetWindow` 必须存在（这条例外：新片子走机会，一定有）
- `expectedDataSha256` 与 `data` 闸 reason 里写的「期望 sha」必须字面一致
- `storyAngle` 与 `editorialIntent` **不应相等**（相等说明又退回复制品了）
- 旁白里的每个数字都能在快照里找到对应（这是事实一致性，属"模型主观判断"栏，见 §4.3）

### 3.6 关键帧抽取

```bash
# hyperframes 自带 snapshot（按 film 时间点抓 PNG）
node data/tools/node_modules/hyperframes/bin/hyperframes.mjs snapshot \
  --help            # 先看它支持的时间点参数形式

# 或直接用 ffmpeg 抽帧，时长按 §3.1 的 ffprobe 结果算
mkdir -p "$FILM_DIR/frames"
for t in 2 8 14 20 26 32 38 43; do
  ffmpeg -y -v error -ss $t -i final.mp4 -frames:v 1 "$FILM_DIR/frames/t${t}s.png"
done
```

每条片子至少抽 **8 帧**，覆盖：开场（1-3s）、第一个数据场景、图表最复杂的一帧、
场景切换处、片尾口播开始处、片尾最后 1s。

### 3.7 关键帧「实际打开看」什么

> 只生成不看 = 没验收。每条片子必须**逐帧打开 PNG 看**，并在报告里写下看到的东西。

| 帧位 | 要看什么 | 判不通过的典型现象 |
| --- | --- | --- |
| 开场 2s | 标题是否已出；标题里有没有出现来源/口径这类"准备过程"话术 | 开场第一句在念"本期数据来自…" |
| 首个数据帧 | 图表是否已渲染；坐标轴/单位/图例是否完整 | 空白画布、图例缺失、单位丢失 |
| 复杂帧 | 数值标签是否重叠；极值标注是否压在末点上重复画 | 标签互相盖住、同一端点两个标签 |
| 数据帧（第二场景） | 场景间数据是否**看起来是同一批**（单位一致、量级一致） | 第二场景数值量级突然变一个数量级 |
| 切换帧 | 转场是否露底/黑帧；动效是否完成 | 黑帧、残留元素 |
| 片尾口播帧 | 字幕/口播文字是否与旁白一致；有没有溢出边界 | 文字出血、超框 |
| 片尾最后 1s | 结尾是否完整、是否有黑场 | 结尾被截断 |

**看帧要记的**：帧号 + 看到了什么 + 是否符合预期。三套风格对比看的是**视觉语言差异**：
调查版应该更慢、字重差更大、有颗粒质感；播报版应该有下三分之一信息条与红色立标；
数据看板版应该图表主导。**如果三套片子看起来一样，就是风格没生效，记缺陷。**

---

## 4. 「机器证据」与「模型主观判断」两栏怎么分开记

报告用两张表，**不混写**。

### 4.1 机器证据栏（可复算，不带判断词）

| 项 | 观测值 | 期望 | 判读 | 证据文件 |
| --- | --- | --- | --- | --- |
| duration | 44.87s | 45±0.5s | ✅/❌ | ffprobe.json |
| 分辨率 | 1080×1920 | 1080×1920 | ✅/❌ | ffprobe.json |
| 帧率 | 30.0 | 与 motion.json 一致 | ✅/❌ | ffprobe.json |
| 整体 I | −16.2 LUFS | — | 记录 | ebur128.txt |
| 整体 True peak | −1.1 dBTP | ≤ 0 dBTP | ✅/❌ | ebur128.txt |
| 旁白段 BGM-旁白差 | −8.4 dB | 见闸门阈值 | ✅/❌ | ebur128_mix_body.txt |
| 片尾段 BGM-旁白差 | −4.1 dB | 见闸门阈值 | ✅/❌ | ebur128_mix_closing.txt |
| shaMatched | true | — | 记录 | gates.json |
| datasetWindow 存在 | 是 | 是 | ✅/❌ | window_report.txt |

判读只允许三种：`✅ 符合` / `❌ 不符合` / `⬜ 未取到该项`。不写"还行""差不多"。

### 4.2 模型主观判断栏（人/模型的看法，明确标主观）

| 项 | 我的观察 | 依据 | 严重级别建议 |
| --- | --- | --- | --- |
| 三套风格是否真的不同 | 调查版明显更慢更重，播报版信息条明显，数据版图表主导 | 逐帧看图 | P1/P2 |
| 标题是否好懂 | 「四期金价区间变化」能一眼看懂讲什么 | 看首帧 | P2 |
| 旁白因果是否成立 | 「因为 X 所以 Y」类表述是否真有依据 | 对快照核 | **P0**（编因果 = 事实错） |
| 画面强调的数字是否就是旁白说的那个 | 图表高亮/标注与旁白数字一致 | 逐帧看图 | **P0**（对不上 = 画面与旁白打架） |
| 区间口径有没有被说成单日 | 有没有"昨日涨"这类表述 | 听/读旁白 | **P0**（口径错 = 假新闻） |

**主观栏不参与闸门判定**，它给出的是"下一步要改什么"和严重级别建议。
**主观栏发现 P0（编因果 / 数字对不上 / 口径说错）时，按缺陷处理**：写清
frame/时间点 → 期望 → 实际 → 严重级别 → 复现步骤，交后端工程师判断，不自己改代码。

### 4.3 事实一致性单独一栏（客观，但需要核对快照）

| 断言（来自旁白） | 快照里的对应值 | 一致？ |
| --- | --- | --- |
| 「最新收于 4091.55 美元」 | `facts.last.value=4091.55` | ✅ |
| 「区间增加 21.79 美元」 | `facts.change=21.79` | ✅ |

每条片子的每个数字都要过一遍这一栏。脚本里的 `{{facts.*}}` 占位符渲染后必须
与快照逐字对应；不允许脚本里出现快照没有的数字。

---

## 5. 预期会拦下片子的场景（提前登记，别当成意外）

跑之前就知道这几条会拒，拒了是**闸门工作正常**，不是 bug：

| 场景 | 拒在哪 | 错误码 |
| --- | --- | --- |
| 选了 `geo` 形态的源 | 取数阶段（`DatasetFetchService.fetched`，`DatasetFetchService.java:109-114`） | `409 dataset_shape_unsupported` |
| 冻结口径与实际取数对不上（源/形态/指标/节奏任一项） | 数据闸（`NewsProductionPipeline.dataGate`，`:307-312`） | `409 dataset_window_mismatch` |
| 冻结口径不完整（无源 / 点数非正） | 取数前（`NewsProductionPipeline.java:135-141`） | `409 dataset_window_mismatch` |
| 口径一致但上游数据在选题后更新过 | **不拒**，留痕：`measured.shaMatched=false` + reason 里两个 sha | — |
| 历史快照（无 `datasetWindow` 节点的老任务） | **不拒**，走兼容分支（取数点数兜底 30） | — |

> 最后两行是本轮验收的核心结论，跑样片时**必须再复现一次**：拿一条老任务重跑，
> 确认它没被新闸误杀。

---

## 6. 三条片子的最小执行顺序（照着跑即可）

```
Step 0  前置检查
        - 后端工程师的改动窗口已结束（确认没有并行改 NewsProductionPipeline）
        - git status / git rev-parse HEAD 存档
        - npm ci 已跑
        - 应用已起，SA_TOKEN_JWT_SECRET 已给

Step 1  触发数据机会分析 → 挑一条 ok 的源
Step 2  认领 → 生成 → 拿 final.mp4 / raw.mp4 / 冻结输入
Step 3  机器证据：ffprobe → ebur128 → 闸门结果 → sha 比对
Step 4  抽 8 帧 → 逐帧打开看 → 记主观栏
Step 5  换第二个源 / 换模板，跑第二、第三条片子（覆盖三套风格）
Step 6  拿一条历史任务重跑，验证兼容底线没被新闸误杀
Step 7  音频段（§3.3）—— 等音频闸落地后再补
Step 8  汇总报告：机器证据表 + 主观判断表 + 事实一致性表 + 缺陷清单
```

---

## 7. 报告落点

```
D:/zhoupei/heron/docs/acceptance-2026-11-19/
  <film-id>/
    frozen_input.json      冻结输入原文
    window_report.txt      口径与 sha 摘录
    gates.json             闸门结果
    ffprobe.json           容器与流
    ebur128.txt            整体响度
    ebur128_*.txt          分段响度（§3.3，音频闸落地后补）
    frames/t*.png          关键帧
    contact-sheet.png      联系表（可选，便于一眼比对）
    git-state.txt          git rev-parse HEAD + git status --porcelain + git diff --stat
    NOTES.md               主观判断与缺陷记录
  SUMMARY.md               三条片子的汇总表
```

`SUMMARY.md` 用两张表分开记（机器证据 / 主观判断），每行带证据文件路径。
缺陷单独列：编号、严重级别、frame/时间点、期望、实际、复现步骤、归属模块。

---

## 8. 明确留给后端工程师的部分

以下两项**本清单不替他们做**，只标注依赖：

1. **音频闸阈值**（旁白段 BGM-旁白差下限、片尾段上限、真峰值上限、削波判定）。
   阈值以 `NewsProductionPipeline` 里落地的闸为准。本清单 §3.3 只负责**把数字量出来**，
   判定留给闸。
2. **闸门落地后的回归**：音频闸改动完成后，需要重跑
   `mvn -pl ruoyi-modules/ruoyi-heron -am test`（含 `NewsBgmMixRenderTest`，
   它带 `@Tag("render")`，默认被 `heron.test.excludedGroups=exclude,mysql,render` 排除，
   要单跑）：
   ```bash
   mvn -pl ruoyi-modules/ruoyi-heron -am test -Dheron.test.excludedGroups=exclude,mysql
   ```
