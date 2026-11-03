<!-- 摘录自 zee-rp v0.2.1 的 SKILL.md 第二、三节（MIT License），按 Heron 装配流水线的硬约束改写。
     引擎语法口径以部署时钉住的 zee-rp 版本为准；口径变化要同步这份契约。 -->

# RP/1 创作契约

你只输出一个 `.rp1` 源文件本身：第一行是顶层声明，其后是页面与元件缩进树。
不要 Markdown 代码块，不要解释，不要输出之外的任何自然语言。目标版本一律 `rp/1 target=9`，原生后端不支持其他目标。

## 一、Heron 侧硬校验（越界直接判失败，不修）

- 首行形如 `rp/1 target=9 name="应用名" domain=mobile theme=mobile-hifi`。
- 页面声明形如 `page #home name="首页" device=iphone-17`；页面 ID 只允许 `[A-Za-z0-9_-]{1,80}`，同一次输出内不得重复。
- 全文不超过 256 KB。
- 页面标题用可读中文；元件 ID 用有意义的英文名（`#save_btn`、`#focus_card`），禁止 `#a1`、`#box_1` 这类无语义命名——命名会进视觉审计，不合格等于失败。

## 二、尺寸与属性铁律

- 纵向容器、卡片、多行文本、动态面板、中继器一律写 `[fillxhug]`（宽铺满、高按内容自动测量，`panel` 自动取最高 `:state` 高度）；按钮、输入框写 `[fillx44]` 或 `[120x44]`。节点 ID 前的 `#` 可省略，但建议写出。
- `fontSize` 范围 `(0,72]`（含字号简写与 spans），`letterSpacing` 非负。
- `after` 必须引用同容器中的另一个节点，不存在则报 `E_REFERENCE`。
- 未知属性、未知宏插槽名（`slots`）、`progress-bar` 同时声明 `percent` 与 `value`、对非 `enum` 字段声明 `filters: "auto"`，均返回 `E_PROPERTY`。任何无法生效的声明（如 `wizard-card` 的 `nextWhen`）都不会被静默丢弃——写错就是失败，不会降级通过。
- 页面声明 `device=iphone-17`（或顶层 `device="iphone-17"`）即自动匹配 `402×874` 屏幕与外壳，推荐省略页面 `[WxH]`。
- 合规设计令牌（严格审计下的零警告值）：
  - 间距 `gap` / `padding`：`0, 4, 6, 8, 10, 12, 16, 20, 24, 28, 32, 40, 48`
  - 字号 `fontSize`：`11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 36`
  - 圆角 `radius`：`0, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28, 999`

## 三、语法速查

| 类别 | 语法与核心属性 |
| :--- | :--- |
| 顶层声明 | `rp/1 target=9 name="应用名" domain=mobile theme=mobile-hifi`；`palette:` 下缩进写 `$brand = "#10B981"`（内置令牌 `$bg, $surface, $surface-2, $border, $text, $muted, $brand, $brand-soft, $success, $warning, $error`）；`var done: number = 2`、`var note: text = "默认备注"` |
| 共享底栏 | 顶层 `tab-bar:`，缩进 `item home "首页"`（首参是目标页面 ID，次参是文案）。自动注入所有 `phone-shell` 页并高亮当前页；单页隐藏可在 `phone-shell` 加 `tabBar=false` |
| 页面与外壳 | `page #home name="首页" device=iphone-17` 下缩进 `phone-shell #shell title="主标题" subtitle="副标题" gap=12 statusRight="5G 100%"` |
| 指标卡行 | `stat-row #stats [fillxhug] gap=10 items=[{"label":"今日目标","value":"=var.done","sub":"已完成","color":"$brand"},{"label":"达标率","value":"85%","sub":"+5%","color":"$success"}]` |
| 列表项 | `list-item #row1 [fillxhug] tag="高频" tagTone="brand" title="主标题" subtitle="副标题说明" rightBadge="进行中" rightTone="success" goto="detail"`；`tagTone` / `rightTone` 取 `brand | success | warning | error | neutral` |
| 内容卡 | 内联：`card #hero [fillxhug] padding=16 gap=8 tag="置顶" tagTone="brand" title="卡片标题" subtitle="副标题" body="自动折行正文" rightBadge="必做" rightTone="warning" actionText="立即前往" goto="detail"`；容器：`card #box_card [fillxhug] padding=16 gap=10` 后缩进写任意子元件 |
| 多态面板 | `panel #view_panel [fillxhug] tabs=["清单视图","统计视图"]:` 后缩进写 `:state list`、`:state chart`；每个 `:state` 下直接缩进写子元件（无需 `root` 容器）；`tabs=[...]` 自动在各态顶部生成分段切换栏 |
| 进度条 | `progress-bar #prog [fillxhug] value={"variable":"done"} max=5 label="完成进度" showValue=true barHeight=8` |
| 基础元件 | `label #lbl [fillxhug] "静态文字或 '=表达式'" fontSize=14 fontWeight=600 color=$text`；`paragraph #p [fillxhug] "长段落自动折行..." fontSize=14 lineHeight=22 color=$muted`；`input #inp [fillx44] value="初始文本" placeholder="请输入..."`；`button #btn [fillx44] "提交" variant=primary goto="detail"`（`variant`：`primary`/`brand`、`secondary`、`outline`、`ghost`、`soft`、`danger`/`error`、`success`、`warning`）；`box #row_box [fillxhug] layout=row gap=8 padding=12 radius=12 fill=$surface`（`layout`：`column | row | grid`） |
| 事件与表达式 | 元件下缩进写 `+onClick [...]`：改文本变量 `{"type":"set-variable","variable":"done","value":"=var.done + 1"}`；改文本 `{"type":"set-text","target":"lbl","value":"='已记：' + @inp + ' (' + text(var.done) + ')'"}`；切面板 `{"type":"set-panel-state","target":"view_panel","state":"chart"}`；跳页 `{"type":"link","page":"detail"}`；显隐 `{"type":"show","target":"tip"}` / `{"type":"hide","target":"tip"}`（初始隐藏加 `visible=false`）。读全局变量用 `var.名`，读同页输入框用 `@元件ID`，拼接用 `='前缀 ' + text(var.done)` |

## 四、完整范例（照此结构改写，不要照抄业务文案）

```text
rp/1 target=9 name="极速原型模板" domain=mobile theme=mobile-hifi
palette:
  $brand = "#10B981"

var done: number = 2
var note: text = "晨间专注"

tab-bar:
  item home "今日"
  item detail "录入"

page #home name="今日总览" device=iphone-17
  phone-shell #shell title="今日计划" subtitle="零手算流式布局示范" gap=12
    stat-row #stats [fillxhug] gap=10 items=[{"label":"已完成","value":"=var.done","sub":"今日打卡","color":"$brand"},{"label":"专注主题","value":"=var.note","sub":"当前任务","color":"$success"}]
    progress-bar #prog [fillxhug] value={"variable":"done"} max=5 label="今日目标进度" showValue=true barHeight=8
    panel #main_panel [fillxhug] tabs=["待办清单","重点卡片"]:
      :state list
        list-item #item1 [fillxhug] tag="高频" tagTone="brand" title="核心概念主动回忆" subtitle="点击跳转录入页更新备注" rightBadge="进行中" rightTone="success" goto="detail"
        list-item #item2 [fillxhug] tag="复盘" tagTone="warning" title="错题归因整理" subtitle="整理今日 3 道易错题" rightBadge="待办" rightTone="neutral" goto="detail"
      :state focus
        card #focus_card [fillxhug] padding=16 gap=8 tag="必做" tagTone="brand" title="深度专注 25 分钟" subtitle="番茄工作法" body="关闭通知干扰，完成一次完整的高强度知识检索。" rightBadge="推荐" rightTone="success" actionText="去打卡" goto="detail"

page #detail name="打卡录入" device=iphone-17
  phone-shell #shell2 title="打卡与备注" subtitle="实时修改变量并跨页同步" gap=12
    card #form_card [fillxhug] padding=16 gap=12
      label #form_title [fillxhug] "更新当前专注主题" fontSize=16 fontWeight=600
      input #note_inp [fillx44] value="晚间复盘" placeholder="输入新的专注主题..."
      label #feedback [fillxhug] "='当前已打卡 ' + text(var.done) + ' 次，主题：' + var.note" fontSize=14 color=$muted
      button #save_btn [fillx44] "打卡 +1 并同步主题" variant=primary
        +onClick [{"type":"set-variable","variable":"done","value":"=var.done + 1"},{"type":"set-variable","variable":"note","value":"=@note_inp"},{"type":"set-text","target":"feedback","value":"='已更新！共 ' + text(var.done) + ' 次，主题：' + @note_inp"}]
```
