# 数据库迁移

应用启动时自动执行本目录下**尚未应用**的迁移，执行记录写进 `flyway_schema_history` 表。
不需要再手工跑 SQL。

## 新变更怎么写

按序号新建文件，**从 V46 开始**（V1~V45 对应手工迁移时代的 001~045，已作为基线跳过）：

```
V46__add_something.sql
V47__add_another.sql
```

规则：

- 序号连续递增，不复用、不跳号回填
- **已应用的脚本不要改**：Flyway 会比对校验和，改动会让应用启动失败（这是有意的，
  防止「本地改了脚本但库里没改」的静默漂移）
- 一个迁移只做一件事，便于定位失败点
- 写清楚回滚方式，放在文件末尾注释里（Flyway 社区版不自动回滚）

## 历史脚本

`script/sql/heron/001~045` 是手工迁移时代的产物，**不再执行**，仅作历史查阅。
新库初始化仍需要先跑它们（见根目录 `CLAUDE.md` 的运行前提），之后交由 Flyway 接管。

`script/sql/heron/` 里的编号**停在 045**，不会再往后加文件。046 之后的结构变更
一律写在本目录下：同一件事只能有一份可执行定义，两条活路径迟早漂移。

被本目录取代的手工脚本：

| 被取代的手工脚本 | 本目录里的对应迁移 |
| --- | --- |
| `script/sql/heron/046-news-publish-attempt.sql` | `V47__news_publish_attempt.sql` |
| `script/sql/heron/047-candidate-research-link.sql` | `V46__candidate_research_id.sql`（加列）+ `V48__news_candidate_research_index.sql`（加索引） |

这两份手工脚本已从仓库删除。历史里仍可查到（`git show`），但**不要再执行**：
它们与对应迁移重复，跑在同一个库上会撞「Duplicate column / table already exists」。

## 为什么基线是 45

已有环境的表结构就是 001~045 累计出来的。如果让 Flyway 从头执行这些脚本，
`ALTER TABLE ... ADD COLUMN` 这类语句会在已有库上直接报错。
`baseline-version: 45` 告诉 Flyway：45 及之前算已完成，只跑 46 之后的。

## 手工库初始化顺序

全新空库（没有 `flyway_schema_history`）的初始化顺序：

1. 导 `script/sql/ry_vue.sql`（基座表）
2. 依次跑 `script/sql/heron/001~045`
3. 启动应用，由 Flyway 建立基线 45 并执行 V46 及之后的迁移

第 3 步之后不要再手工跑任何 `script/sql/heron/` 下的文件。
