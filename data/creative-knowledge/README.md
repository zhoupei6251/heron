# creative-knowledge · 镜头创作知识底座与弹药库

> 2026-11-26 由模板产线拆除迁移（拆除清单 `docs/plans/2026-11-26-video-director-demolition.md` C 类）。
> 定位：**知识与参照资产，不是可直接渲染的模板**。导演系统（ADR
> `docs/architecture/heron-video-director-adr.md` §七）按阶段过滤注入 prompt；
> 镜头创作 Agent 从这里取参照，生成代码必须适配 VisualDesignProfile 令牌，不许自带皮肤。

## 目录角色

| 目录 | 角色 | 来源与许可 |
| --- | --- | --- |
| `behaviors/` | 已过闸的动效手法实现（chart/race/compare/globe/focus/headline/mood/fill + skeleton.js，配 mood.card.md、race.card.md 卡片）。旧 `data/templates/_gen/behaviors/` 原样迁入。生成代码可适配复用，须适配 `data/profiles/<id>/tokens.css` 令牌 | heron 自研（仓库自有） |
| `reference-cards/` | 参考配方母本：`hyperframes-registry/`（31 件官方组件，镜头组件弹药库，优先 `components/`）、`video-shotcraft/`（125 张镜头配方卡）、`huashu-art-motion/`（57 张风格配方 + 9 份解说语法）。旧 `data/reference-cards/` 整目录迁入 | Apache-2.0（hyperframes、video-shotcraft）与 MIT（huashu-art-motion），每目录随附 LICENSE，收录义务见其 `ATTRIBUTION.md` |
| `styles/` | 风格归属与叙事词汇：`template-styles.json` 为旧 `data/templates/_shared/styles.json` 原样收录——五套风格的 name/label/role 与 narratives 归属（`DatasetStoryContract.NARRATIVES` 六项词汇的模板侧登记）。叙事词汇正演进并入 `NarrativeTreatment`/`Storyboard` 契约语义层，`sceneType` 退役 | heron 自研（仓库自有） |

## 使用约束

- `behaviors/` 与 `hyperframes-registry/` 进镜头创作时必须适配 Profile 令牌（`@import` tokens.css、本地 `@font-face`），禁止硬编码色值/字号/间距——HyperFrames 硬性契约见 `docs/plans/2026-11-26-video-director-contracts.md` §五。
- 后续演进（参考片逆向沉淀、发布表现反哺）经人工审核后入本目录，见 ADR §七。
