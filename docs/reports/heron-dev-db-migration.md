# dev 库迁移与 MCP 环境

**日期：** 2026-11-13
**目标库：** `127.0.0.1:3307/heron`（MySQL 8.0.42）
**结果：** 17 张表齐全；唯一新增的是 `heron_data_opportunity`

---

## 一、执行了什么

按 `script/sql/heron/0*.sql` 顺序执行 `004`~`030`（`001`~`003` 早已应用）。

| 结果 | 文件 |
|---|---|
| 实际执行建表 | `030-data-opportunity.sql`（今天 15:52 建表） |
| 跳过（已应用） | `005`（3 条 `heron.model.*` 配置已存在，值留空）以及 `006`~`029` 的全部语句 |

**为什么大量「跳过」：** 迁移文件早期已在库里执行过，逐语句执行时只把
`1050 表已存在` / `1060 列已存在` / `1061 索引已存在` / `1062 记录已存在` / `1091 待删项不存在`
这五类当作幂等跳过，其余错误一律中断。全过程无硬失败。

`004-menu.sql` 首次运行成功（48 条菜单）；`005` 撞主键——它插的三条配置行本就存在，源码也注明值留空，故跳过。

---

## 二、迁移后的真实数据

| 表 | 行数 |
|---|---:|
| `heron_data_source` | 119 |
| `heron_news_candidate` | 2317 |
| `heron_news_work` | 12 |
| `heron_video_version` | 15 |
| `heron_production_task` | 26 |
| `heron_news_source` | 7 |
| `heron_news_metric` | 1 |
| `heron_auto_slot` | 7 |
| `heron_data_opportunity` | **0** |

`heron_data_opportunity` 共 23 列，与 `HeronDataOpportunity` 实体字段一一对应。

---

## 三、一处必须记录的错误更正

我此前说「库里基本是空的、链路从未在真库跑过」——**这是错的**。

依据是 MCP 连接下的 `information_schema` 查询，它只列出了 10 张表；改用 JDBC 直连同一实例后，
17 张表都在，且 `create_time` 显示 `001`~`029` 建的表都是 11-03 至 11-12 建的。

**教训：跨 schema 的元数据查询不要只信 MCP 那个连接**，涉及「表在不在、有多少行」这类判断，用直连复核。

准确事实：生产链路真跑过（15 个版本、26 个任务），**缺的只有数据机会这一环**。

---

## 四、MCP 环境变更

| 名称 | 指向 | 说明 |
|---|---|---|
| `mysql-heron`（新增） | `127.0.0.1:3307/heron`，root | 放开 INSERT/UPDATE/DELETE/DDL，供迁移与联调；**只指向本机 dev 库** |
| `redis-heron`（新增） | `redis://:•••@127.0.0.1:6380/0` | 密码取 `.env` 的 `HERON_PASSWORD`，与 MySQL 同一个 |
| `mysql` / `mysql-prod` / `redis`（未动） | 3306 / 远程生产 / 6379 | 保持原样，不影响其它项目 |

两者均为 user 级配置，已验证 `claude mcp list` 显示 Connected。

**没做：** 没在 Redis 里建 `root` ACL 用户——6380 的 `default` 用户已经用着与 MySQL 相同的密码，加用户属于共享实例改动，收益为零。
**没做：** 没改 `application-dev.yml`——它已是 6380，密码走 `${...}` 占位符，本次不动。

---

## 五、遗留

| 项 | 说明 |
|---|---|
| 机会表为空 | 需要跑一次 `DataOpportunityService.analyze()` 才会有数据；`mysql` 测试组本地跑不了，要么起应用要么走单独入口 |
| 迁移不可重复执行 | `028`/`029`/`030` 的 `CREATE TABLE` 不带 `IF NOT EXISTS`，重复执行会报错（本次已容忍） |
| 口令暴露面 | `claude mcp list` 会明文回显 Redis 与 MySQL 的口令；如需收敛，可改用环境变量注入 |
