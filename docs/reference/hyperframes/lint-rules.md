# Lint 规则字典

> 上游：<https://hyperframes.heygen.com/reference/lint-rules> · 快照：`_upstream/reference-lint-rules.md` · 版本：hyperframes CLI（npm `^0.9.6`）· 抓取 2026-11-13

`hyperframes lint`、`hyperframes check` 与 `@hyperframes/lint` 用**稳定代码**报告问题。本页按严重级别收录全部 144 条规则：代码 / 抓什么 / 怎么修。

| 级别 | 含义 |
| - | - |
| **Error** | composition 已坏，或渲染会失败 / 明显不一致。默认让 `hyperframes check` 失败 |
| **Warning** | 质量、确定性或可维护性隐患。默认不失败，`--strict` 下失败；`render` 里只有 `--strict-all` 才因警告中止 |
| **Info** | 提示，无需处理。`hyperframes lint --verbose` 才显示 |
| **Warning or error** | 仅「结构与时间轴」一节：CLI 检查项目时是警告，浏览器编辑器检查正在编辑的文件时是错误 |

> 从渲染或 check 日志里看到一个代码，回这张表查。`--json` 可以把发现项交给脚本处理。

## 结构与时间轴

`packages/lint/src/rules/structure.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `nested_structure_needs_subcomposition` | Warning or error | 顶层时间轴元素里又套了嵌套结构，而不是子 composition 挂载。时间轴每个顶层元素画一行，根 composition 只能由子 composition 组成 | 把该元素及其内容移进子 composition 文件，用 `data-composition-src` 挂载 |
| `timeline_element_missing_timing` | Warning or error | 时间轴元素没有 `data-duration`，时间轴画不出它的终点 | 补 `data-duration`（秒） |
| `caption_track_kind_missing` | Warning or error | 元素看起来是字幕行但没标记，时间轴无法归到字幕道 | 加 `data-track-kind="captions"` |
| `multiple_caption_tracks` | Warning or error | 字幕分散在多个轨上而不是一条字幕道 | 让所有字幕元素用同一个 `data-track-index` |
| `multiple_scene_templates` | Warning | 一个文件里有多个装着 composition 根的 `<template>`，且模板外没有根；只有第一个会渲染，消息会点名每个模板 | 每个文件只留一个 composition 根，或把其它场景移到各自文件 |
| `clip_ends_past_root_duration` | Warning | clip 超出根的 `data-duration`，预览、封面、渲染里会被截断 | 加长根的 `data-duration`，或让 clip 在根时长内结束 |
| `nested_clip_never_visible` | Warning or error | 定时 clip 套在另一个定时元素内，时间窗永不重叠，因此永远不出现。**嵌套 clip 的 `data-start` 从 composition 起点算，不是从外层元素算**。子 composition 文件里同样检查 | 按 composition 时间给它在父窗口内的 `data-start`，或移出父级 |

## Composition 契约

`packages/lint/src/rules/composition.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `invalid_composition_variables_declaration` | Error | `data-composition-variables` 不是合法 JSON，或不是变量声明数组 | 写成 JSON 数组，属性用单引号、JSON 内用双引号：`data-composition-variables='[{"id":"title","type":"string","label":"Title","default":"Hello"}]'` |
| `invalid_variable_values_json` | Error | `data-variable-values` 不是合法 JSON | 属性单引号、键值双引号：`data-variable-values='{"title":"Hello"}'` |
| `unknown_variable_binding` | Warning | 元素绑定的变量没在 `data-composition-variables` 里声明，绑定静默回落到作者写的兜底值 | 在 composition 根（`<html>`，或模板/片段 composition 的 `data-composition-id` 根）上声明该变量 |
| `unloadable_media_variable_default` | Error | 变量默认值是运行时拒绝加载的 URL，被绑定的元素渲染作者兜底 src，而渲染仍以 0 退出 | 用相对路径、`http(s)`、`blob:` 或 `data:image/*`；把文件拷进项目并相对引用 |
| `deprecated_data_end` | Error | 用了旧的 `data-end` 而没有 `data-duration`，或旧的 `data-end` 与旁边的 `data-duration` 不一致 | 改成 `data-duration`，编译器会自动生成 `data-end` |
| `deprecated_data_layer` | Error | 用了旧的 `data-layer` | 换成 `data-track-index`（浏览器编辑器与 linter 读的规范名） |
| `duplicate_composition_id` | Error | 同一文件里多个元素带相同 `data-composition-id`，常见是 `<meta>` 与根 `<div>` 撞车 | 只让 composition 根带 `data-composition-id`，从元数据或重复宿主上删掉 |
| `invalid_parent_traversal_in_asset_path` | Error | 素材路径用 `../` 越过项目根，渲染与预览会夹取或解析错 | 指向项目内的文件：从 `compositions/scene.html` 出发，`../assets/x.png` 和 `assets/x.png` 都能到项目的 `assets` 目录 |
| `composition_exceeds_inspection_viewport_cap` | Warning | composition 大于 `check`、`validate`、`snapshot`、`compare` 与布局审计夹取的 4096px 视口上限。`render` 不受影响，但「干净的检查结果」不再覆盖整幅画布 | 需要这个输出尺寸就保持作者尺寸，改用渲染结果验证超出上限的区域 |
| `composition_file_too_large` | Warning | HTML composition 文件已大到不好读、不好迭代、不好 diff | 拆成子 composition，从父级用 `data-composition-src` 挂载 |
| `composition_heavy_overlay_count_high` | Warning | 大量元素带重叠加层 CSS（`filter: blur`、`radial-gradient`、`clip-path`，**含隐藏元素**），会限制捕获性能 | 拆分后拼接，让每次捕获只看到少量重叠加层；删掉本来不可见的叠加层 |
| `missing_data_no_timeline` | Warning | `data-composition-id` 元素（或根）既没有对应的 `window.__timelines` 注册也没有 `data-no-timeline`，producer 会轮询最多 45 秒才超时 | 注册时间轴、用 `data-composition-src` 挂载，或对确实静态的区块加 `data-no-timeline` |
| `negative_z_index` | Warning | `z-index` 小于 0 会把元素画到最近层叠上下文内容之后；没有层叠上下文祖先时那个上下文就是 composition 根，根上的不透明背景会把元素完全盖住 | 给元素的父级建层叠上下文（最省事是 `isolation: isolate`；`transform`、`filter`、`opacity` 小于 1、`contain: paint`、`will-change` 也行） |
| `requestanimationframe_in_composition` | Error | `requestAnimationFrame` 走墙上时钟而不是 GSAP 时间轴，与帧捕获脱节，渲染会闪或丢帧 | 动画逻辑改用 GSAP tween 或 timeline 的 `onUpdate` |
| `root_composition_duration_derived` | Warning | 根没有 `data-duration` 也没有 GSAP 时间轴，长度只能从 clip 推断 | 给根加 `data-duration="<seconds>"` 自己定长度 |
| `root_composition_missing_duration_source` | Error | 根既没 `data-duration`，也没有 GSAP/CSS/WAAPI/Lottie/Three.js 动画可供推断时长，渲染引擎无法确定抓多久，直接失败 | 给根加 `data-duration`，或加一个注册到 `window.__timelines` 的 paused GSAP 时间轴 |
| `root_composition_missing_html_wrapper` | Error | 带 `data-composition-id` 的 `index.html` 是没有 `<!DOCTYPE html>`、`<html>`、`<body>` 的裸片段，浏览器会进 quirks 模式，预览服务也发不对 | 包成 `<!DOCTYPE html><html><head><meta charset="UTF-8"></head><body>...</body></html>` |
| `root_zoom_rescales_a_fixed_canvas` | Error | 画布上的 CSS `zoom` 会缩放已绘制的 composition，而捕获帧按根的 `data-width`/`data-height`（布局像素）定尺寸，不跟着缩放 | 按真实尺寸创作：设 `data-width`、`data-height`（以及根元素的宽高），去掉画布级 `zoom`。要缩放输出而不改布局，用 render 的 `--output-resolution`。后代元素上的 `zoom` 没问题 |
| `split_data_attribute_selector` | Error | 一个 `[...]` 里塞了两个属性，浏览器拒绝，GSAP 时间轴或 `querySelector` 在注册前就失败 | 分开写：`[data-composition-id="x"][data-track-index="1"]` |
| `standalone_composition_wrapped_in_template` | Error | 根 `index.html` 被 `<template>` 包住，运行时放不出来——只有经 `data-composition-src` 加载的子 composition 才用 `<template>` | 去掉 `<template>`，作为真实文档提供 |
| `subcomposition_blanks_before_host` | Warning | 挂载的子 composition 在宿主窗口内有一段空白，因为它自己的 `data-duration` 比占的槽短 | 把子 composition 的 `data-duration` 调到填满宿主窗口，或用别的 clip 补空档 |
| `subcomposition_linked_style_names_root_id` | Warning | 子 composition 外链样式表里有针对 `#<根 id>` 的规则。编译时根会被并入宿主，选择器匹配不到任何东西；同样的规则写在行内 `<style>` 里则会被改写而生效 | 把规则移进 `<template>` 内的 `<style>`，或在外链文件里选根的子元素 |
| `subcomposition_root_styled_by_class` | Error | 子 composition 的根靠 class 选择器加样式，但渲染时所有规则被限定到 `[data-composition-id=...]`，class 键控的样式失效 | 给根 `id="root"`，用 `#root` 加普通后代选择器 |
| `subcomposition_style_outside_template` | Warning | 带 `<template>` 的子 composition 在模板外有 `<style>`，或 `<head>` 外有 `<link rel="stylesheet">`。挂载只保留模板内容与 `<head>` 里的样式链接，这些样式在预览/快照/渲染里都不生效 | 移进 `<template>`，紧挨 composition 根 |
| `template_literal_selector` | Error | `querySelector` 用模板字面量变量做参数，打包器的 CSS 解析器会崩 | 用硬编码字符串选择器 |
| `timed_element_missing_clip_class` | Warning | 元素有定时属性但没有 `class="clip"`。运行时照样定时，但浏览器编辑器和 clip 归属规则靠 `.clip` 识别，编辑和 lint 都更难 | 加 `class="clip"` |
| `timeline_track_too_dense` | Warning | 单个文件的一条轨道上有超过 3 个定时元素（音频、视频、字幕条目、子 composition 挂载不计），时间轴难读难 diff | 拆成子 composition，从父级用 `data-composition-src` 挂载 |

## 文档、CSS 与脚本

`packages/lint/src/rules/core.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `host_missing_composition_id` | Error | `data-composition-src` 宿主元素自己没有 `data-composition-id` | 给每个 `data-composition-src` 宿主加 `data-composition-id` |
| `css_parse_error` | Error | `<style>` 块 CSS 解析失败 | 修掉报出的语法错误 |
| `css_transition_used` | Warning | CSS `transition` 走浏览器时钟，跨渲染 worker 无法确定性 seek。`transition: none` 与全零时长允许 | 保留 class/属性切换管状态，把视觉变化放到 paused 的 GSAP 时间轴上 |
| `id_override_reduced_specificity` | Warning | 用 `[id=...]` 或 `:where(#id)` 设定位属性，特异性只有 class 级或零，同一元素上的复合 class 规则可能悄悄胜出 | 用普通 `#id` 选择器，id 特异性高于任何只由 class 组成的选择器 |
| `id_requires_css_escape` | Error / warning | id 以数字开头，`#id` 选择器在 `querySelector` 和 GSAP 里抛 `SyntaxError`，CSS 也丢掉该规则。该选择器真的被用到时是 error，否则 warning | 改成字母开头（推荐），或运行时用 `CSS.escape(id)` 拼选择器 |
| `inline_style_attribute_selector` | Error | 选择器读行内 `style` 属性（`[style]`、`[style=...]` 等）。GSAP 每帧都写行内样式，每次写入都会重算远超被动画元素的范围，预览会卡死 | 隐藏 clip 交给运行时（它会隐藏内容）。自己的状态用脚本切 class 或 data 属性再选择 |
| `invalid_inline_script_syntax` | Error | 行内 `<script>` 的闭合语法有问题 | 用合法 `</script>` 闭合 |
| `missing_timeline_registry` | Error | composition 完全没有注册 `window.__timelines` | 把每个 composition 的时间轴注册到 `window.__timelines[compositionId]` |
| `non_deterministic_code` | Error | 脚本用了非确定性值来源（`Math.random`、`Date.now`、`gsap.utils.random`、`"random(...)"`），渲染可能逐帧或逐次不同 | 构建时算一次当常量传进去，或播种后冻结 |
| `repeated_id_descendant_selector` | Error | 选择器要求某个 id 嵌在另一个同 id 元素里。id 必须唯一，这个选择器永远匹配不到 | 去掉重复祖先：`#x #x` 改成 `#x` |
| `root_dimensions_mismatch` | Warning | 直接改了根的 `data-width`/`data-height`，但脚手架的 `html, body` CSS 与 `<meta viewport>` 还是旧分辨率，过期的 `overflow: hidden` body 会把尺寸正确的根视觉裁掉 | 同步更新 body CSS 与 viewport，或用 `hyperframes init --resolution portrait` 重新生成 |
| `root_missing_composition_id` | Error | 根 composition 缺 `data-composition-id` | 给入口 composition 包装加一个稳定 id |
| `root_missing_dimensions` | Error | 根缺 `data-width` 或 `data-height` | 在入口根上设数值型 `data-width` 与 `data-height` |
| `runtime_hidden_style_opacity` | Error | 选择器观测运行时持有的 hidden 样式（复制帧时用来隐藏原生视频）并把 `opacity` 强制为 0，破坏视频替换管线 | 删掉该规则（运行时已隐藏 clip 与内容）。要淡出或隐藏自己动画，或切 class/data 属性再选择 |
| `scoped_css_missing_wrapper` | Warning | 作用域 CSS 指向某个 composition id，但本 HTML 里没有对应包装元素 | 保留匹配的 composition 包装，或把 CSS 作用域对齐到已有包装 |
| `studio_missing_editable_id` | Warning | 时间轴可见元素没有 `id`，浏览器编辑器无法稳定定位它做时间轴与画布编辑 | 给需要被 agent 或编辑器编辑的元素加稳定可读的 id，如 `id="hero-title"` |
| `timeline_id_mismatch` | Error | 时间轴注册在某个键下，但没有元素带匹配的 `data-composition-id`，运行时无法自动嵌套 | 让 `window.__timelines["<key>"]` 与元素的 `data-composition-id` 一致 |
| `unbalanced_style_tags` | Error | `<style>` 没闭合（后续标记被当 CSS 解析并消失），或多了一个 `</style>` 提前闭合、把 CSS 当正文吐出来 | 保持 `<style>` 与 `</style>` 成对 |
| `unclosed_tag_swallowed_element` | Error | 某个标签在下一个 `<` 之前缺 `>`，后面的元素被吞成属性文本，永远成不了真实节点 | 补上前一个标签的 `>` |
| `visible_markup_comment` | Error | 可见 HTML 标记里出现 CSS/JS 块注释语法（`/* ... */`），HTML 里只有 `<!-- ... -->` 是注释，所以会显示在画面上 | 删除该文本或改成真正的 HTML 注释；CSS 注释放在 `<style>` 内、JS 注释放在 `<script>` 内 |

## GSAP 动画

`packages/lint/src/rules/gsap.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `audio_reactive_single_tween_per_group` | Warning | 音频反应字幕每组只按峰值建一个 tween，等于设了一个静态值，观众感知不到反应 | 在每组存续期内按 100–200ms 采样音频，每个采样点建一个 tween |
| `gsap_animates_clip_element` | Error | GSAP 直接在 clip 元素上设影响可见性的属性（`opacity`、`visibility`、`autoAlpha`），而可见性归框架管 | 删掉可见性/autoAlpha tween，或把内容移进子 `<div>` 再动画那个 |
| `gsap_callback_dom_measurement` | Warning | 时间轴回调里做 DOM 测量（`getBoundingClientRect`、`getTotalLength`、`getComputedStyle` 等）。回调每次 seek 都重跑，冷渲染 worker 跑它时的布局与顺序播放时不同 | 构建时同步测量并完成布局设置，再注册时间轴；或从固定的 composition 坐标推导几何 |
| `gsap_cold_seek_hidden_fromto_missing_reveal` | Error | 作者隐藏的元素用 `gsap.fromTo()` 且目标 vars 里没有 `opacity`/`autoAlpha`，冷渲染 worker 会恢复隐藏态，元素可能一直不可见 | 在目标 vars 里加 `opacity: 1`（或 `autoAlpha: 1`），让每条 seek 路径都能建立可见终态 |
| `gsap_css_transform_conflict` | Error | 元素有 CSS `transform`（如 `translateX(-50%)` 居中）又用 GSAP 动画 transform 属性，GSAP 会覆盖整个 CSS transform | 从 CSS 拿掉 transform，改用 GSAP 属性表达；带显式起始值的 `tl.fromTo` 例外 |
| `gsap_from_opacity_noop` | Error | 元素 CSS 是 `opacity: 0`，`gsap.from()` 也把 opacity 设成 0，等于从 0 到 0，永不出现 | 从 CSS/行内样式去掉 `opacity: 0`，让 `gsap.from()` 独占初始隐藏态 |
| `gsap_fullscreen_overlay_starts_visible` | Error | 满帧叠加层在第一个 GSAP opacity tween 之前就是可见的，会盖住前面的渲染帧（常表现为黑屏或白屏视频） | 在 CSS 里就写成隐藏，或在揭示 tween 前立即 `gsap.set(..., { opacity: 0 })` |
| `gsap_function_value_hazard` | Error / warning | 函数值 tween var 读了 transform 敏感几何、在 tween 初始化时测布局，或误用了第一个参数（那是序号不是元素）。transform 敏感读取与错误成员访问是 error，其它是 warning | 构建时算一次当常量传；需要逐元素逻辑用第二个参数 `(index, target) => ...` |
| `gsap_group_selector_keyframes` | Warning | 一个 GSAP tween 用共享关键帧打多个元素，在编辑器里改其中一个的关键帧会改到全部 | 拆成逐元素的 `tl.to()` 调用 |
| `gsap_infinite_repeat` | Error / warning | tween 或时间轴用 `repeat: -1`。composition 声明了有限 `data-duration` 时是 warning（seek 与导出会被裁到该时长），没有任何边界时是 error | 声明有限 `data-duration`，或把 `repeat: -1` 换成有限次数，如 `repeat: Math.max(0, Math.floor(totalDuration / cycleDuration) - 1)` |
| `gsap_non_transform_motion` | Error | GSAP 动画布局属性（`left`、`top`、`width` 等）、文字重排属性（`fontSize`、`letterSpacing` 等）或设了 `roundProps`，这些会吸附到整数设备像素，帧捕获下抖动 | 动画 transform：位置改用 `x`/`y`/`scale`；重排属性改逐字拆分或给静态终值；去掉 `roundProps` |
| `gsap_relative_value_second_writer` | Error | 相对值 tween（`+=...`）在另一个写入者还活跃时启动，不同 seek 路径会从不同基准初始化，画面会跳 | 改用绝对值，或给该属性用带显式端点的 `fromTo()`。单写入者的相对值是安全的 |
| `gsap_repeat_ceil_overshoot` | Warning | 用 `Math.ceil` 算重复次数会超出 composition 时长，动画延伸到片外 | 改用 `Math.floor`：`repeat: Math.max(0, Math.floor(totalDuration / cycleDuration) - 1)` |
| `gsap_repeat_floor_unclamped` | Warning | composition 短于一个周期时重复次数算成 `-1`，GSAP 视为无限重复 | 下限夹到 0：`repeat: Math.max(0, Math.floor(totalDuration / cycleDuration) - 1)` |
| `gsap_repeat_refresh_relative_value` | Error | `repeatRefresh: true` 配相对值会逐次累加；冷渲染 worker 直接 seek 到第 N 次迭代时没做前面的累加，结果不同 | 去掉 `repeatRefresh: true`，或把相对值换成绝对端点（`fromTo()`） |
| `gsap_repeated_fromto_without_baseline` | Warning | 多个 `tl.fromTo()` 打同一元素却没有稳定基准，最后一次写的 "from" 值成了任何「首个 tween 之前」seek 的静止状态 | 除最早的那个 `fromTo` 外，都加 `immediateRender: false` |
| `gsap_timeline_not_registered` | Error | 建了 GSAP 时间轴但没注册到 `window.__timelines`，运行时发现不了它，预览与渲染都不播 | 加 `window.__timelines = window.__timelines || {};` 并挂到 composition 的 `data-composition-id` 下 |
| `gsap_timeline_registered_before_async_build` | Error | 在 `document.fonts.ready` 里构建时间轴之前就赋值了 `window.__timelines`，先注册了空时间轴，子 composition 里会嵌套出空动画 | 把 `window.__timelines[id] = tl` 移到异步构建回调末尾、tween 加完之后 |
| `gsap_timeline_return_used_as_tween` | Error | 把 `tl.to()`/`tl.from()`/`tl.set()` 的返回值当 tween 用，其实这些返回时间轴本身，后续 `kill()` 或覆盖会打到整条时间轴 | 把那组 tween 放进嵌套时间轴（`gsap.timeline()` 用 `tl.add(nested, 0)` 加入）再 kill 它 |
| `gsap_timeline_set_initial_hide` | Warning | 用 paused 时间轴里的 `tl.set(..., 0)` 设初始隐藏态；播放头正好停在 0 时零时长 set 不会渲染，第 0 帧显示未隐藏状态 | 改用时间轴外的 `gsap.set(...)`，或直接在 CSS/属性里写隐藏态 |
| `gsap_undefined_css_variable` | Warning | GSAP 动画的 `var(--...)` 没有被任何静态 CSS 或 composition 变量声明，计算出的颜色可能非法或透明 | 在 CSS 里定义、在 `data-composition-variables` 里声明，或加 `var(--x, #fff)` 兜底 |
| `missing_gsap_plugin` | Error | tween 用了插件专属属性（如 `motionPath`）但插件没加载注册，GSAP core 会忽略该属性 | 在动画脚本前加载插件并 `gsap.registerPlugin(...)`，或用 core 的 `x`/`y` 表达运动 |
| `missing_gsap_script` | Error | composition 用了 GSAP 但没加载 GSAP 脚本 | 在动画脚本前加 GSAP `<script>` |
| `overlapping_gsap_tweens` | Warning | 两个 GSAP tween 在同一目标同一属性上重叠，后者会在中途覆盖前者 | 缩短前者、移动后者，或加 `overwrite: "auto"` |
| `svg_drawon_css_dasharray_conflict` | Error | GSAP 写 `strokeDasharray`，而 CSS 声明了多段 `stroke-dasharray`；GSAP 逐段合并，CSS 的空隙留了下来，描边动画失效 | 去掉 CSS 的 `stroke-dasharray`（装饰虚线放到另一个元素上），或在 GSAP 里给完整的两段值 |
| `svg_round_cap_hidden_dash` | Warning | 圆头/方头端点路径用 dasharray = gap = `pathLength`、dashoffset = dash 隐藏。Chrome 可能在路径末端留零长度残片，端点在某些曲线上画成一个点 | 让空隙长于路径（`stroke-dasharray="100 120"`、`stroke-dashoffset="110"`），或让元素在描边 tween 开始前保持 `visibility: hidden` |
| `svg_measure_before_path_d` | Error / warning | 在没有静态 `d` 属性的路径上调 `getTotalLength()`，返回 0，描边动画失效。任何地方都没有 `d` 赋值时是 error，`d` 只在函数体内赋值时是 warning | 构建时（顶层、测量之前）同步给路径赋 `d`，或在 HTML 里写静态 `d` |

## 媒体元素

`packages/lint/src/rules/media.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `audio_attribute_unreadable` | Error | `data-automation` 或 `data-fx-chain` 渲染解析不了：不是 JSON、没有 `"version": 1`、没有 `lanes`/`nodes` 数组、lane 缺 `target` 或 `points`、node 缺 `type`。本地与云端音频混音都会因此失败 | 写成 `{"version": 1, "lanes": [...]}`（automation）或 `{"version": 1, "nodes": [...]}`（效果链），或删掉该属性 |
| `audio_carve_ungrouped_sources` | Warning | voiceover carve 直接点名成员 clip 而不是对 `<hf-audio-group>` 做 carve，clip 一变名单就悄悄腐坏 | 把旁白 clip 分组，对组做 carve |
| `audio_group_carve_attr` | Warning | `<hf-audio-group>` 上带了 `data-fx-carve`（或派生的链），而 carve 属于底层 clip，不属于总线 | 从组上删掉 `data-fx-carve`，对底层 clip 做 carve |
| `audio_group_holds_elements` | Warning | 元素跑进了 `<hf-audio-group>` 内部。多半是写成自闭合（`<hf-audio-group id="sfx" />`），HTML 不会为自定义元素自闭合，后续兄弟全落进去。在里面不会把音频路由到总线，且组带 `data-hidden` 时这些元素在预览与渲染里都被隐藏且静音 | 用 `</hf-audio-group>` 紧跟开标签闭合，要给总线的 clip 加 `data-audio-group` |
| `audio_group_no_members` | Error | 存在没有任何 clip 加入的音频组，它的推子、效果、自动化会被静默丢弃 | 给目标 clip 加 `data-audio-group="<id>"`，或删掉这个总线 |
| `audio_group_timing_attrs` | Warning | `<hf-audio-group>` 带了定时属性（`data-start`、`data-duration`、`data-track-index`）。组没有自己的 clip 窗口，定时属于成员 | 从组上去掉定时属性，设到成员 clip 上 |
| `audio_volume_double_automation` | Warning | 元素同时有音量自动化 lane 和 GSAP 的 `volume` tween，lane 胜出，tween 在预览与渲染里被忽略 | 只留一个：要 tween 就删 lane，要在 lane 里塑形就删 tween |
| `audio_volume_tween_overrides_gain` | Warning | 元素同时有 `data-volume` 增益和 `volume` tween，tween 值是绝对值，会替换增益而不是按比例缩放 | tween 目标写成已含增益的绝对值，或把 `data-volume` 复位为 1 交给 tween |
| `base64_media_prohibited` | Error | 出现内联 base64 音频或视频。base64 媒体会撑大文件并破坏渲染 | `src` 用相对路径或 HTTPS URL，绝不内嵌 base64 |
| `color_grading_invalid_json` | Error | `<video>`/`<img>` 上的 `data-color-grading` 以 `{` 或 `[` 开头但不是合法 JSON，分级不生效 | 用合法 JSON，属性单引号、内层双引号：`data-color-grading='{"preset":"skin-soft","intensity":0.6,"adjust":{"highlights":-0.08}}'` |
| `color_grading_invalid_structure` | Error | `data-color-grading` 能解析但违反分级契约：不支持的键、类型错、数值越界。运行时静默忽略未知键，于是 HTML 里看着生效实际不渲染。每个问题一条，会点出出错路径 | 按 finding 指出的路径改正或删除该值，参考上游 Color grading 文档 |
| `color_grading_non_media` | Error | `data-color-grading` 设在 `<video>`、`<img>`、`<canvas>`、`<hf-color-group>` 之外的元素上（比如包装层或带 CSS 背景的元素）。shader 运行时只给真实 `<video>`、`<img>`、`<canvas>` 及其 grade group 成员分级，属性无效 | 把属性移到 `<video>`、`<img>` 或 `<canvas>` 元素本身 |
| `duplicate_media_discovery_risk` | Warning | 多个匹配的媒体条目共用同一源、起点与时长，编译器可能把同一媒体发现两次 | 删掉重复的媒体节点，让发现只看到一份 |
| `duplicate_media_id` | Error | 同一媒体 `id` 定义在多个元素上，预览与 producer 可能建出不同的媒体图 | 给每个媒体元素唯一 `id` |
| `imperative_media_control` | Error | 行内脚本命令式控制受管媒体（`play`、`pause`、`currentTime` 等）。播放必须归 HyperFrames 才能保证预览、时间轴与渲染一致 | 用 `data-start`/`data-duration` 和 `data-media-start` 这类媒体偏移表达时间 |
| `media_crossorigin_breaks_preview` | Error | 元素带 `crossorigin`，强制 CORS 检查的请求；宿主没给 CORS 头时浏览器编辑器预览静默加载失败（媒体空白），而渲染仍正常 | 除非你要通过 canvas、WebGL 或 WebAudio 回读媒体且确认宿主给 CORS 头，否则删掉 `crossorigin` |
| `media_missing_data_start` | Error | 媒体元素有 `src` 但没有 `data-start`，HyperFrames 无法接管播放，预览与渲染可能不一致 | 加 `data-start="0"`（或预期起点）；若要在源结束前停下再加 `data-duration` |
| `media_missing_id` | Error | 定时媒体元素没有 `id`，渲染器发现不了它，音频静音或视频冻结 | 加唯一 `id` |
| `media_missing_src` | Error | 定时媒体元素没有 `src`（元素上或 `<source>` 子节点上都没有），渲染器加载不了 | 在元素上加 `src`，或加 `<source src="...">` 子节点 |
| `media_preload_none` | Warning | 元素带 `preload="none"`，渲染器不会加载该媒体（编译器渲染时会剥掉，但预览可能异常） | 去掉 `preload="none"` 或改成 `preload="auto"` |
| `media_runtime_src_mutation` | Warning | 行内脚本改了已有受管媒体的 `src`，与媒体发现和抽帧赛跑 | 写最终静态 `src`，或把 `data-var-src` 绑到已声明变量，让源在发现前就位 |
| `media_src_kind_mismatch` | Error | 媒体 URL 的类型与元素不符（比如静态图放 `<video>`、视频 URL 放 `<img>`），元素渲染不出东西 | 元素配媒体：静图用 `<img>`，视频 URL 用 `<video>`，声音用 `<audio>` |
| `media_variable_src_no_fallback` | Warning | 元素靠 `data-var-src` 但没有兜底 `src`，没传该变量值的渲染加载不到媒体，而音频抽帧读的是作者写的 `src` | 加一个变量缺失时 composition 能渲染的兜底 `src` |
| `nested_media_start_basis_ambiguous` | Warning | 嵌套媒体元素的 `data-start` 有歧义：composition 局部时间还是旧的根全局时间戳，两者读出的帧不同 | 保持 `data-start` 为 composition 局部时间，或用 `data-hf-media-start-basis="global"` 标明旧的基准，或直接换算成局部时间 |
| `placeholder_media_url` | Error | 元素用了渲染时会 404 的占位媒体 URL | 换成真实可达的媒体 URL |
| `self_closing_media_tag` | Error | 自闭合媒体标签（`<video ... />`）是非法 HTML：浏览器不闭合该标签，后续元素全被吞成不可见兜底内容，composition 不可见 | 写显式闭合标签 `<video ...></video>`，媒体元素绝不自闭合 |
| `speed_ramp_on_non_media` | Warning | 变速 lane 放在非媒体元素上，只有 `<video>` 与 `<audio>` 能改速率，该 lane 无效 | 把 rate lane 移到视频或音频 clip 上，或改用 GSAP 时间轴重定时动画 |
| `video_audio_double_source` | Error | `<audio>` 与 `<video>` 同时指向同一源，未静音的视频本身就有声音，播放会重声 | 让视频自带声音（推荐）；画面与声音必须独立剪辑时静音视频并保留单独 `<audio>` |
| `video_missing_muted` | Error | 定时 `<video>` 既没 `muted` 也没 `data-has-audio="true"`，linter 分不清是有声还是无声素材 | 有声音就加 `data-has-audio="true"`，无声就加 `muted` |
| `video_muted_with_declared_audio` | Error | `<video>` 声明了 `data-has-audio="true"` 又带 `muted`，浏览器编辑器预览会静掉它承诺的声音 | 该有声就删 `muted`；无声视觉视频改用 `data-volume="0"` 并去掉 `data-has-audio` |
| `video_nested_in_timed_element` | Error | 带 `data-start` 的 `<video>` 嵌在另一个定时元素里；抽帧器解析视频起点时不带外层偏移，可见性却用外层窗口，clip 会显示错帧且中途消失 | 只给其一计时：去掉外层的 `data-start`，或把 `<video>` 上移为舞台的直接子元素 |

## 字幕

`packages/lint/src/rules/captions.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `caption_container_relative_position` | Error | 字幕选择器用 `position: relative`，导致溢出并破坏字幕堆叠 | 所有字幕元素用 `position: absolute` |
| `caption_exit_missing_hard_kill` | Error | 字幕退场动画（`tl.to` 到 `opacity: 0`）没有硬 `tl.set` 杀干净，冲突的卡拉 OK tween 可能把字幕留屏 | 每个退场 tween 之后加 `tl.set(groupEl, { opacity: 0, visibility: "hidden" }, group.end)` |
| `caption_fittext_scale_mismatch` | Warning | `fitTextFontSize` 的 `maxWidth` 没给放大强调词留余量，实际宽度可能溢出 composition | 缩小 `maxWidth` 给缩放系数留出余量 |
| `caption_overflow_clips_scaled_words` | Error | 字幕容器 `overflow: hidden` 而 GSAP 把词放大到 1x 以上，强调词及其辉光被裁掉 | 容器用 `overflow: visible`，靠 `fitTextFontSize` 加更小的 `maxWidth` 防溢出 |
| `caption_text_overflow_risk` | Warning | 字幕选择器 `white-space: nowrap` 但没有 `max-width`，长句会跑出画面 | 加 `max-width: 1600px`（横屏）或 `900px`（竖屏），保持 overflow visible |
| `caption_textshadow_on_group_container` | Warning | 对字幕组容器 tween `textShadow`，子元素半透明时辉光会在整组后面渲染成可见矩形 | `textShadow` 打到单个活跃词上，组的脉冲用 scale |
| `caption_transcript_not_inline` | Error | 字幕 composition 用 `fetch()` 加载 transcript，而编辑器的字幕面板需要行内 `var TRANSCRIPT = [...]` 才能识别和编辑 | 把 transcript 以 `var TRANSCRIPT = [...]` 内联，键用 JSON 引号 |

## 字体

`packages/lint/src/rules/fonts.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `font_family_without_font_face` | Error | 使用了没有 `@font-face` 声明的字体族，渲染器提供不了，文字回落到默认字体 | 用 `@font-face` 指向捕获到的 `.woff2` 声明每个族，或把字体放进 `capture/assets/fonts/`。声明里字体名加不加引号都行；`font-family` 是块内最后一个描述符时末尾分号可省；`font` 简写（`font: 500 16px/1 "PingFang SC", sans-serif`）里的族等同 `font-family` |
| `font_face_local_only` | Warning | `@font-face` 只有 `local()` 源。本地渲染用本机安装的字体，云端或另一台没有该字体的机器会回落成别的字体 | 加一个指向项目内 `.woff2` 的 `url()`，或用 Google Fonts 的字体族 |
| `system_font_will_alias` | Warning | 用了没有匹配 `@font-face` 的系统字体族；分布式渲染下系统字体捕获被禁用，渲染时会被替换、文字可能重排。**只在 linter 以 `distributed: true` 运行时报告**（面向分布式或 Lambda 渲染），本地渲染会按设计捕获系统字体，不报 | 用 `@font-face` 加项目内文件打包字体，别依赖 OS 字体 |

## 纹理

`packages/lint/src/rules/textures.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `texture_class_missing_base` | Warning | 用了纹理材质 class 但没有基础 `hf-texture-text` class，纹理管线不生效 | 基础 class 与材质 class 一起写，如 `class="hf-texture-text hf-texture-lava"` |
| `texture_class_unknown` | Error | 纹理材质 class 没有任何本地 CSS 定义，文字渲染成无纹理 | 把 Texture Mask Text 组件的 `<style>` 贴进 composition，或修掉 class 拼写 |
| `texture_drop_shadow_on_text` | Warning | 阴影直接加在纹理文字上，阴影作用于遮罩而非字形 | 包一层，把 `filter: drop-shadow(...)` 加到包装元素上，不要加在 `hf-texture-text` 元素 |
| `texture_text_missing_mask` | Warning | 用了 `hf-texture-text` 但既没有材质 class 也没有自定义 `mask-image`，没有可揭示的东西 | 加材质 class（如 `hf-texture-lava`），或在元素上设 `mask-image` 与 `-webkit-mask-image` |

## 幻灯片

`packages/lint/src/rules/slideshow.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `slideshow_invalid` | Error | 幻灯片 island 的 JSON 非法或结构不符合 manifest schema | 让 `<script type="application/hyperframes-slideshow+json">` 块是合法 JSON 并匹配 SlideshowManifest schema。`startTime`、`endTime` 若给必须是有限 JSON 数字——带引号的 `"0"`、`null`、`1e400` 这类溢出值都非法 |
| `slideshow_unresolved_ref` | Error | manifest 引用的 `sceneId` 没有对应场景元素，或场景没有显式时间窗 | 让每个 `sceneId` 对上场景元素的 `data-composition-id`，或提供显式 `startTime`/`endTime` |

## 适配器：Lottie 与 Three.js

`packages/lint/src/rules/adapters.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `missing_lottie_script` | Error | 用了 Lottie 但没加载 Lottie 脚本，动画不渲染 | 在 Lottie 代码前加 `lottie-web` 的 `<script>` |
| `missing_three_script` | Error | 用了 Three.js 但没加载 Three.js 脚本，3D 场景不渲染 | 在 Three.js 代码前加 Three.js 的 `<script>` |

## 联动 clip

`packages/lint/src/rules/linkedClips.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `linked_clip_orphan` | Warning | clip 带的 `data-link` 对应的伙伴 clip 已不存在，它被当作单人组编辑 | 去掉 `data-link`，或恢复伙伴 clip |
| `linked_clips_out_of_sync` | Warning | 共享 `data-link` 的 clip 定时属性不一致，而联动 clip 是当一个来编辑的 | 让每个成员 `data-start`、`data-duration`、`data-media-start`、`data-playback-rate` 一致，或全部去掉 `data-link` 解联 |

## 项目级检查

`packages/lint/src/project.ts`、`hyperframeLinter.ts`、`hevcPreviewLint.ts`

| 代码 | 级别 | 抓什么 | 怎么修 |
| - | - | - | - |
| `multiple_root_compositions` | Error | 多个根级 HTML 文件都带 `data-composition-id`，运行时可能发现多个入口并重复播放音频 | 只保留一个带 `data-composition-id` 的根 `index.html`，删除或重命名多余文件 |
| `blank_root_with_standalone_composition` | Error | 默认 `index.html` 没有可渲染内容，而另一个文件装着独立的定时 composition，默认命令打开的是空视频 | 把 composition 移进 `index.html`，或用 `data-composition-src` 挂载；确实要独立文件就显式渲染它 |
| `missing_or_empty_sub_composition` | Error | `data-composition-src` 指向目录、空文件或不存在的文件，挂载不可用，渲染前置检查会拒 | 指向 HTML 文件（如 `<folder>/index.html`）并确保有内容 |
| `missing_local_asset` | Error | 元素引用的本地文件在项目里不存在，渲染器静默跳过，成片缺画面 | 把文件放进项目目录，或把 `src` 改成存在的文件。常见原因是素材被改名 |
| `audio_file_without_element` | Warning | 项目里有音频文件但任何 composition 都没有 `<audio>` 元素，成片会是静音 | 加一个引用该文件的 `<audio>` 元素 |
| `audio_src_not_found` | Error | `<audio>` 元素引用的文件不在项目里，成片静音 | 补齐文件，或把 `src` 改到存在的文件 |
| `duplicate_audio_track` | Warning | 两个有声元素在同一 track index 上重叠，声音会叠 | 用不重叠的时间窗，或用不同 track index |
| `texture_mask_asset_not_found` | Error | CSS `mask-image` 或 `-webkit-mask-image` 引用的文件不在项目里，纹理遮罩加载失败。合法 CSS 里别的声明内部（如 `content` 字符串）的注释与文本会被忽略 | 补齐遮罩文件，或改 `mask-image` 的 URL |
| `missing_stylesheet_asset` | Warning | 外链本地样式表里的 `url()` 相对该样式表解析不到。浏览器按样式表自身目录解析 `url()`，字体或图片 404 后渲染静默回落；文件在父目录存在时提示会给出可用路径 | 把 `url()` 改成相对样式表（如从 `styles/main.css` 写 `../assets/fonts/x.ttf`），或补文件 |
| `inaccessible_media_url` | Error | 远程媒体 URL 对 `HEAD` 请求没返回 2xx（或请求失败/超时），媒体加载不了。**只有 `@hyperframes/lint` 的异步 `lintMediaUrls()` 导出会报**，`hyperframes lint` 与 `hyperframes check` 不跑它 | 换成有效可达的媒体 URL |
| `hevc_preview_codec` | Info | 视频文件是 HEVC/H.265。渲染不受影响（FFmpeg 预解码），预览在浏览器解不了时会自动回落到缓存的 H.264 代理 | 无需处理。预览仍失败就跑 `hyperframes doctor`，确认媒体自动代理已开 |
| `video_media_start_at_or_past_eof` | Warning | 视频的 `data-media-start` 到达或超过源可播时长，整个槽位定格在最后一帧 | 不想定格就把 `data-media-start` 裁到源时长以内 |

## Heron 用在哪

`hyperframes check` 是新闻线的版式闸。拿它失败日志里的代码回这张表定位，几点对接说明：

- **`missing_data_no_timeline`** 会让每次渲染白等 45 秒（`player-ready-timeout`），模板里静态区块记得标 `data-no-timeline`。
- **`video_missing_muted`** 是 error 级别：模板里每个定时 `<video>` 必须二选一，无声写 `muted`、有声写 `data-has-audio="true"`。
- **`clip_ends_past_root_duration`** 与 `compositions/` 的时间轴补丁直接相关：`timeComposition()` 改了音频时长却没同步根 `data-duration` 时，这条会先炸出来。
- **`non_deterministic_code`** 与 `requestanimationframe_in_composition` 是确定性渲染的底线，模板里出现 `Date.now()` 或未播种随机就会被拦下。
- **`font_family_without_font_face`** 对无头渲染尤其重要：没声明 `@font-face` 的字体族在服务器上多半不存在，会回落成默认字体。
