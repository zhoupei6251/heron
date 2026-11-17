# 动效与转场

来源：`hyperframes-animation/` 的 `rules/`（48 条原子规则）、`blueprints/`（22 个场景蓝图）、`transitions/`（13 类 CSS 转场）、`techniques.md`；`hyperframes-creative/references/beat-direction.md`。

## 动效动词

给每个元素一个动词。命名不出动词，说明这个元素还没被设计。

| 物理性格 | 动词 |
| - | - |
| 冲击 / 重量 | SLAMS、CRASHES、PUNCHES、STAMPS、SHATTERS、DROPS |
| 方向 / 果断 | SLIDES、PUSHES、PULLS、WIPES、CUTS |
| 揭示 / 生长 | DRAWS、FILLS、GROWS、EXPANDS、ASSEMBLES、COUNTS UP |
| 有机 / 环境 | FLOATS、DRIFTS、BREATHES、PULSES、ORBITS、MORPHS |
| 机械 / 精准 | TYPES ON、CLICKS、LOCKS IN、SNAPS、STEPS |

动词要跟着这一拍的**概念**走，不是查「高能量拍用什么词」。一个讲「放下」的慢拍，也可以有东西**坠落**。

## 原子规则：按用途分组

原包 48 条，每条形如 `SKILL.md` 短描述 + 完整配方 + 参数表 + 硬约束。数据看板线用得上的：

**数据类**
`counting-dynamic-scale`（数字滚动 + 缩放）· `stat-bars-and-fills`（条/环/星级）· `chart-scrub-readout`（图表刮擦读数）· `vertical-spring-ticker`（竖向弹性滚动条）· `svg-path-draw`（折线/图标描绘）· `svg-icon-enrichment`（图标丰富化）

**文字类**
`discrete-text-sequence`（逐词浮现）· `gradient-text-sweep`（渐变扫光）· `kinetic-beat-slam`（节奏重击）· `css-marker-patterns`（荧光笔/圈选/涂鸦标注）· `asr-keyword-glow`（关键词高亮——需词级时间戳）

**入场 / 编排**
`spring-pop-entrance`（弹入）· `waterfall-entry`（瀑布式入场）· `center-outward-expansion`（中心向外扩散）· `depth-scatter-assemble`（纵深散开再聚合）· `grid-card-assemble`（卡片阵列装配）

**转场 / 变形**
`scale-swap-transition` · `theme-crossfade-morph`（主题色渐变过渡）· `card-morph-anchor`（卡片变形锚定）· `chromatic-glitch`（色差故障）

**镜头 / 纵深**
`multi-phase-camera`（多段镜头）· `3d-camera-flight`（3D 飞行）· `orbit-3d-entry`（环绕入场）· `depth-of-field-blur`（景深）· `coordinate-target-zoom`（坐标定位推近）

**UI / 交互感**
`cursor-click-ripple` · `context-sensitive-cursor` · `control-target-sync` · `multi-cursor-choreography` · `ai-tracking-box`（AI 追踪框）

**氛围**
`ambient-glow-bloom` · `particle-burst` · `sine-wave-loop` · `motion-blur-streak` · `reactive-displacement`

其余规则（3D 页面滚动、光标拖拽、物理按压、黑客翻转等）与数据看板线关联弱，用时回原包查。

## 22 个场景蓝图

蓝图是多阶段的完整场景模板，比原子规则大一圈，含节拍划分与元素编排。原包路径：`hyperframes-animation/blueprints/`，索引在 `blueprints-index.md`。

数据看板线可直接用的：

| 蓝图 | 用途 |
| - | - |
| `dataviz-countup` | 数字滚动 + 图表揭示的完整数据场景 |
| `comparison-split` | 左右对比（同比、竞品、方案 A/B） |
| `kinetic-type-beats` | 动态字体踩节拍，适合数据结论强断言 |
| `titlecard-reveal` | 标题卡揭示 |
| `typewriter-reveal` | 打字机揭示，适合「正在生成」的信息感 |
| `ticker-takeover` | 滚动条占领画面 |
| `zoom-out-workspace-reveal` | 从细节拉远到全局 |
| `camera-journey` | 镜头旅程，串联多段数据 |
| `spatial-pan-stations` | 横向站点平移，适合时间线/阶段 |
| `fixed-anchor-cycle` | 固定锚点循环切换 |

其余（Logo 装配、设备展示、光标演示、提示词生成、进度剧场等）偏产品演示，按需查。

## 转场

### 先选类型

| 用 Shader 转场 | 用 CSS 转场 | 用硬切 |
| - | - | - |
| 揭示、大反应镜头、产品/Logo 亮相、能量转折、wow 时刻 | 连续镜头感的段落，像一镜被切成几段 | 快节奏罗列、踩点剪辑、喜剧节奏 |
| 音乐/旁白重音落点在那一刻 | 用共享动词语汇缓入下一段 | 连续 3 个以上快切 |
| 转场本身就是视觉主体 | 极简/编辑式节奏 | 任何 0.3–0.8s 都嫌慢的时候 |

经验法则：这一拍是**重心**就用 Shader 转进去；是**衔接组织**就 CSS 交叉淡化。5–7 拍的品牌片通常只需要 1–2 个 Shader 转场（主角揭示 + CTA），多了反而互相削弱。

### CSS 转场：13 类

| 类别 | 代表 | 运动性格 |
| - | - | - |
| Push / slide | 推入、竖向推、弹性推、挤压 | 内容像在连续表面上穿过画面 |
| Scale / zoom | 穿越缩放、拉远 | 透视位移 |
| Radial / clip | 圆形光圈、菱形光圈、对角分割 | 几何揭示 |
| 3D | 卡片翻转 | 物理翻转 |
| Dissolve | 交叉淡化、模糊淡化、焦点拉动、色彩下沉 | 两场景在转场中同时存在 |
| Cover / blinds | 错落色块、百叶窗（6/12 条） | 内容被切片覆盖 |
| Light | 漏光、过曝、胶片灼烧 | 光溢出画面 |
| Distortion | 故障、色差、涟漪、VHS 带 | 画面本身失灵 |
| Blur | 模糊穿越、定向模糊 | 虚焦过渡 |
| Mechanical | 快门、钟表擦除 | 机械逻辑可见 |
| Grid | 网格溶解（12/120 格） | 画面碎成块 |
| Destruction | 页面燃烧 | 上一场景被摧毁 |
| Other | 重力下坠、圆形变形 | 其他物理/形状运动 |

### 常用快捷键

- **速度匹配上推**：出 `y:-150, blur:30px, 0.33s power2.in` → 入 `y:150→0, blur:30px→0, 1.0s power2.out`
- **甩镜**：出 `x:-400, blur:24px, 0.3s power3.in` → 入 `x:400→0, blur:24px→0, 0.3s power3.out`
- **模糊穿越**：出 `blur:20px, 0.3s` → 入 `blur:20px→0, 0.25s power3.out`
- **穿越缩放**：出 `scale:1→1.2, blur:20px, 0.2s power3.in` → 入 `scale:0.75→1, blur:20px→0, 0.5s expo.out`

时长预设：干脆 0.2s、顺滑 0.4s、温和 0.6s、戏剧 0.5s、即刻 0.15s、奢华 0.7s。

### Shader 转场：14 种内置

`domain-warp`（有机 FBM 溶解）· `ridged-burn`（燃烧揭示）· `whip-pan`（甩镜运动模糊）· `sdf-iris`（圆形光圈 + 光晕环）· `ripple-waves`（径向涟漪）· `gravitational-lens`（引力透镜）· `cinematic-zoom`（RGB 分离径向缩放）· `chromatic-split`（色道分离）· `swirl-vortex`（漩涡）· `thermal-distortion`（热浪扭曲）· `flash-through-white`（白闪）· `cross-warp-morph`（双向形变）· `light-leak`（漏光）· `glitch`（故障）

安装用 `npx hyperframes add <name>`，**块名和 shader 名不一致**，安装前查 `hyperframes-registry/references/discovery.md`。

框架能跑什么浏览器就能跑什么——内置的不够，可以自己写 GLSL 或改造 ShaderToy 上的。

### 速度匹配

出场的加速缓动（`power2.in`/`power3.in`）+ 模糊渐强，入场用减速缓动（`power2.out`/`power3.out`）+ 模糊消退。两条曲线的**最快点在剪切处相遇**，观众感知到的是连续镜头运动，而不是两段独立动画。出入速度差控制在 5% 以内。
