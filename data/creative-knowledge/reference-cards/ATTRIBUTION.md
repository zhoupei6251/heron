# 参考配方库（reference-cards）来源与授权

> 2026-11-26 收录。这里是**参考母本**，不是可直接渲染的模板——采纳路径见
> `docs/plans/2026-11-26-visual-recipe-field-design.md` §四建工路径。

## 目录一览

| 目录 | 来源 | 许可 | 内容 |
| --- | --- | --- | --- |
| `hyperframes-registry/` | [heygen-com/hyperframes](https://github.com/heygen-com/hyperframes) 官方 registry（经 `hyperframes add` 拉取） | Apache-2.0 | 16 个 block + 15 个 component，自包含 HTML |
| `video-shotcraft/` | [Vincentwei1021/video-shotcraft](https://github.com/Vincentwei1021/video-shotcraft) `references/shots/` | Apache-2.0 | 125 张镜头配方卡（md）+ 分类体系 + 审美规则 |
| `huashu-art-motion/style-recipes/` | [alchaincyf/huashu-art-motion](https://github.com/alchaincyf/huashu-art-motion) `references/风格配方/` | MIT | 57 张艺术风格配方卡（每卡 = 风格提示词 + 代码实现记录 + 坑） |
| `huashu-art-motion/animation-grammar/` | 同上 `references/动画语法/` | MIT | 9 份解说语法（3b1b / Kurzgesagt / Vox / 白板 / kinetic type 等） |

## hyperframes-registry 子目录内容

blocks（整景，自画布尺寸）与 components（片段，继承宿主尺寸——**heron 竖屏优先看 components**，
见 `docs/plans/2026-11-17-video-visual-upgrade-design.md` §4.6 的既定结论）：

- **数据类**：bar-chart-race、data-chart、chart-story、mk-line-graph、mk-progress-stat、
  animated-bar-chart、decline-chart、conic-progress-ring、count-up、number-wheel、world-map、us-map、spain-map
- **信息条 / 结尾卡**（talkcraft 人物互动卡的 Apache 等价物）：news-ticker、lower-third-bild、
  lt-clean-bar、lt-kicker-name、lt-side-rule、lt-soft-pill、lt-stack-bars、lt-dark-card、
  lt-accent-underline、social-proof-card、cta-lockup
- **对比 / 文本**：before-after-wipe、comparison-split、caption-pill-karaoke、beat-timeline、
  svg-stroke-trace、soft-blur-in、headline-slam

## 许可义务

- **Apache-2.0**（hyperframes、video-shotcraft）：保留 LICENSE 副本（已随目录收录）；
  改写后分发需保留版权与许可声明。产品代码使用无需开源 heron 本身。
- **MIT**（huashu-art-motion）：保留 LICENSE 副本；使用需保留版权声明。
- **禁收来源**：video-talkcraft 与 anything2explainer 为 **PolyForm Noncommercial 1.0.0**——
  商业使用需作者事先授权，**不收录其任何文件**。卡格式知识（字段集、已知坑写法、动效范围结构）
  属方法范畴，已通过设计文档消化（`2026-11-26-visual-recipe-field-design.md`）。

## 与 ADR 的关系

这些卡是 `data/recipes/*.json`（VisualRecipe）的**实现母本池**：建工 = 从这里选卡 → 按锚定模板
重皮 + 建内容接口 + 改时序 + 过骨架闸 → 同笔提交配方 JSON 与场景实现。components 继承宿主尺寸，
是竖屏模板的优先采纳形态；blocks 需要重排画布。
