# 表结构对齐参考项目：雪花 id · 域前缀表名 · 逻辑删除 · 审计四件套 · 对象键年月日

**日期**：2026-10-29
**分支**：`dev`（未提交）
**层级**：Tier 2+（动 schema、跨 packages/db / contracts / storage / api / worker / web）
**参考项目**：`D:\work\xinyue\aigc_platfrom_back`（RuoYi-Vue-Plus 系，`org.xywh`）
**状态**：设计定稿，等用户点头开工

---

## 1. 目标

把 heron 的库结构对齐到用户自己在用的那套规范，五件事一起做：

1. **主键换成雪花 id**（参考项目用 MyBatis-Plus `idType: ASSIGN_ID` = 雪花 + `bigint`）。
2. **表名按域前缀 + 单数**（参考项目 `sys_user` / `bf_model` / `aigc_task` / `sj_job`）。
3. **业务实体表加逻辑删除**（参考项目 `del_flag` 全库 158 处）。
4. **每张表都有审计四件套**：`create_by` / `create_time` / `update_by` / `update_time`，
   列名照参考项目（`create_time` 161 处、`update_time` 149 处、`create_by` 125 处、`update_by` 112 处）。
5. **上传目录按年月日分层**（对象键多一层 `年/月/日`）。

**同时把代码写干净**：不是「为了实现而实现」。见 §9「代码规范」与 §4.7「让结构保证规范」——
本次的核心手法是**用列构造器把四件套、软删列、主键列一次性造出来**，而不是在 22 张表里复制粘贴 22 遍。

## 2. 已确认的决策

| # | 议题 | 决定 |
|---|---|---|
| 1 | 表名 | **按域前缀 + 单数**：`sys_` / `news_` / `plat_` / `proto_` / `usage_` |
| 2 | 逻辑删除 | **只加在业务实体上**：证据链与流水表不加 |
| 3 | 雪花 id 落库 | **`numeric` 列 + JS 侧仍是字符串**（node-postgres 原样返回，精度零损失） |
| 4 | 对象键日期层 | **租户 → 年/月/日 → 任务**：`tenants/<租户>/2026/10/29/tasks/<任务>/v1/mp4.mp4` |
| 5 | 审计列 | **每张表都补齐 `created_at` + `updated_at`** |
| 6 | 审计列命名 + 操作人 | **四件套照参考项目命名**：`create_by` / `create_time` / `update_by` / `update_time`；列名、TS 属性名、JSON 字段名三者一致 |

**关于其他时间列的后缀**（本次自己定的口径，写死免得以后又漂）：领域时间列沿用 `_at`
（`confirmed_at` / `evaluated_at` / `revoked_at` / `last_seen_at` …），**不改名**。

- 理由：它们是「某事件发生的时刻」（确认、撤销、抓取），与「这一行是什么时候被创建/修改」不是一回事；
  参考项目本身也是混着用的（`create_time` 与 `trigger_at` / `next_trigger_at` 并存）。
- 代价：库里会有 `_time` 与 `_at` 两种后缀。为了不漂，规则是**只有两个意思**：
  `_time` = 行级审计时刻；`_at` = 业务事件时刻。**不允许出现第三种后缀**。
- 副作用：通用那一对要改名 —— `created_at` → `create_time`、`updated_at` → `update_time`，
  TS 属性 `createdAt` → `createTime`、`updatedAt` → `updateTime`，JSON 字段同改。

## 3. 现状对照

| 项 | 参考项目 | heron 现状 | 差距 |
|---|---|---|---|
| 表名 | `<域>_<实体单数>`：`sys_user` / `bf_model` / `aigc_task` / `sj_job` | 复数、无域前缀：`users` / `tasks` / `renders` / `aigc_posture` | 22 张全改 |
| 主键 | `bigint` + 雪花（`DefaultIdentifierGenerator`，本机 IP 作 datacenter） | 20 张 `uuid` + 2 张序列（`news_task_event` / `usage_record`），应用层 `randomUUID()` 生成 | 126 处生成点 + 全部 FK 列 |
| 逻辑删除 | `del_flag char(1) DEFAULT '0'`，158 处 | **零**。且 `rls.ts:97` 已 REVOKE 掉 `sys_user` / `sys_tenant` / `plat_operator` 的 DELETE —— 这三张表物理上删不掉，却没有软删列 | 9 张表加 `del_flag` |
| 审计列 | `create_by` / `create_time` / `update_by` / `update_time` 全表覆盖（+`create_dept`） | 只有 `created_at` / `updated_at` 且不统一（5 张表才齐）；**没有操作人列** | 22 张表补齐四件套 |
| 操作人列的约束 | `create_by bigint`，**无外键** | 无 | 照抄「无外键」：审计列要活得比人久（§4.4） |
| 租户列 | `tenant_id varchar(20)` | `tenant_id uuid` + RLS（`::uuid` 转换） | 类型跟着主键改 |
| 时间列类型 | MySQL `datetime` | Postgres `text`（ISO-8601 UTC，项目口径见 `packages/db/src/time.ts`） | **沿用 text**，不引类型变更（见 §10 盲点） |
| 索引/约束命名 | `idx_<表>_<列>` / `uk_` / `ck_` | `ix_<表>_<列>` / `u_<表>_<列>` / `ck_<表>_<语义>` | 规则已一致，只需跟着表名改名 |

## 4. 设计

### 4.1 雪花 id

**生成器**（新增 `packages/db/src/id.ts`，从 `@heron/db` 与 `@heron/db/pg` 导出）：

- 标准 64 位布局：`41 bit 毫秒（epoch 2024-01-01）+ 10 bit 节点 + 12 bit 序列` = 63 位。
- 节点号：`HERON_NODE_ID`（0–1023）；未给时取 `(hash(本机 hostname) + pid) % 1024`
  —— 不能只用 hostname：api 与 worker 在同一台机器上 hostname 相同，会撞号；pid 保证同机并存进程不同号。
- 时钟回拨：`now < lastTs` 时沿用 `lastTs` 并递增序列（不回退），序列溢出则等到下一毫秒。
- 返回**十进制字符串**（`8xxxxxxxxxxxxxxx` 量级，> 2^53，不能走 JS number）。
- **格式规则只有一份**：`packages/contracts` 导出 `SNOWFLAKE_ID_PATTERN` 与 `z.snowflakeId()`，
  生成器在 `packages/db` 里 import 同一个 pattern 做自检——两处各写一遍正则，迟早会飘。

**落库**：`numeric(19,0)`。

- `numeric(19,0)` 而不是裸 `numeric`：固定精度 19 位、**标度 0**。
  裸 `numeric` 会保留运算产生的标度——哪天有人写 `id / 2` 或 `id + 0.0`，
  存进去就变成 `1839203847563.0`，JS 侧读出来是个带小数点的字符串，id 就废了。
  标度钉死 0 之后，任何算术结果都会落回整数。
- drizzle 的 `numeric()` 默认 `mode: "string"`，node-postgres 也原样把 numeric 读成字符串 →
  **JS 侧 id 仍是字符串**，与今天的 uuid 完全同构，contracts 与前端不用改类型。
- 主键列与全部 FK 列必须**同一个 typmod**（都是 `numeric(19,0)`），否则 Postgres 拒绝建外键。
  这一条由列构造器保证（§4.7），不靠人记。
- 与参考项目的 `bigint` 只差一列存储类型；将来要换 `bigint`，再迁一次列类型即可。
- 排序与索引按数值走（numeric 是精确数值类型），不是字符串排序。

**存量 uuid → numeric 的映射**（迁移 0012，确定性、无数据丢失）：

```
uuid 'a1b2c3d4-....'  →  ('x' || substr(replace(id::text,'-',''), 1, 13))::bit(52)::bigint::numeric
```

- 取十六进制前 13 位 = 52 bit，值域 < 4.5e15；新雪花今天在 ~8.9e17（≈2^59.6），
  **两段值域不重叠**，存量行不会与新行撞号。
- 映射是 uuid 的纯函数 → 同一个 uuid 在任何表、任何 FK 上都映射成同一个数，FK 不用手工修补。
- **一个动态 DO 块统一处理**：遍历 `public` schema 下所有 `uuid` 列（主键 + 外键一起），
  先收集并 `DROP` 依赖这些列的外键约束 → 逐列 `ALTER COLUMN … TYPE numeric(19,0) USING …` →
  按原定义重建外键。理由：`uuid → numeric` 不是二进制可强转的，列上有外键时直接改类型会被 Postgres 拒绝；
  而手写 33 条外键的 drop/recreate 是最容易抄错的地方（漏一条 = 库结构与代码不一致，且不报错）。

**两条序列主键改成雪花**：`news_task_event.id`（`integer identity`）与 `usage_record.id`（`bigint identity`）
改为应用层雪花、删序列——用户要的是「id 都用雪花」。

**契约**：`packages/contracts` 里 33 处 `z.uuid()` → `z.snowflakeId()`
（`/^\d{1,19}$/` + `1 ≤ v ≤ 2^63-1` 的 BigInt 校验）。实现时逐处确认是 id 而不是别的 uuid 字段。

**`DEFAULT_TENANT_ID`**：从 `"00000000-0000-4000-8000-000000000001"` 改成哨兵值 `"1"`
（它不是雪花 id，但必须是个稳定的 numeric 串；注释写明它是保留哨兵）。

**RLS**：`TENANT_FROM_GUC` 的 `::uuid` → `::numeric`；GUC 里存的就是租户 id 的十进制串。
（`rls.ts` 里那段「NULLIF 不是装饰」的注释继续有效：`''::numeric` 同样会抛 2202。）

### 4.2 表名（22 张）

「四件套缺口」列记的是**相对新规范**还差什么（`by` 指 `create_by` + `update_by`）。

| 旧 | 新 | 域 | 逻辑删除 | 四件套缺口 |
|---|---|---|---|---|
| `tenants` | `sys_tenant` | sys | ✅ | 缺 update_time、by |
| `users` | `sys_user` | sys | ✅ | 缺 by |
| `sessions` | `sys_session` | sys | ❌ | 缺 update_time、by |
| `audit_logs` | `sys_audit_log` | sys | ❌ | 缺 update_time、by |
| `platform_accounts` | `plat_account` | plat | ❌ | 缺 update_time、by |
| `platform_operators` | `plat_operator` | plat | ✅ | 缺 update_time、by |
| `platform_audit_logs` | `plat_audit_log` | plat | ❌ | 缺 update_time、by |
| `platform_settings` | `plat_setting` | plat | ❌ | 缺 create_time、by |
| `settings` | `news_setting` | news | ✅ | 缺 create_time、by |
| `feed_sources` | `news_feed_source` | news | ✅ | 缺 update_time、by |
| `candidates` | `news_candidate` | news | ✅ | 缺 by |
| `tasks` | `news_task` | news | ✅ | 缺 by |
| `task_events` | `news_task_event` | news | ❌ | 缺 update_time、by |
| `gate_results` | `news_gate_result` | news | ❌ | 缺两列、by |
| `renders` | `news_render` | news | ❌ | 缺 update_time、by |
| `scripts` | `news_script` | news | ✅ | 缺 update_time、by |
| `confirmations` | `news_confirmation` | news | ❌ | 缺两列、by |
| `publishes` | `news_publish` | news | ❌ | 缺 by |
| `aigc_posture` | `news_posture` | news | ❌ | 缺两列、by |
| `usage_records` | `usage_record` | usage | ❌ | 缺两列、by |
| `proto_projects` | `proto_project` | proto | ✅ | 缺 by |
| `proto_revisions` | `proto_revision` | proto | ✅ | 缺 update_time、by |

**索引与约束同步改名**（规则：`ix_<表>_<列>` / `u_<表>_<列>` / `ck_<表>_<语义>`）：

| 旧 | 新 |
|---|---|
| `u_renders_task_version` | `u_news_render_task_version` |
| `u_scripts_task` | `u_news_script_task` |
| `u_confirmations_render_fingerprint` | `u_news_confirmation_render_fingerprint` |
| `u_publishes_tenant_idempotency` | `u_news_publish_tenant_idempotency` |
| `u_aigc_posture_single_current` | `u_news_posture_single_current` |
| `u_platform_accounts_external` | `u_plat_account_external` |
| `u_platform_operators_username` | `u_plat_operator_username` |
| `u_proto_revisions_project_version` | `u_proto_revision_project_version` |
| `u_feed_sources_tenant_url` | `u_news_feed_source_tenant_url` |
| `u_candidates_tenant_norm` | `u_news_candidate_tenant_norm` |
| `u_users_tenant_email` | `u_sys_user_tenant_email` |
| `ck_renders_*` / `ck_confirmations_*` / `ck_publishes_*` / `ck_aigc_posture_*` / `ck_tasks_*` / `ck_task_events_*` / `ck_gate_results_*` / `ck_usage_records_*` / `ck_feed_sources_*` / `ck_candidates_*` / `ck_users_*` / `ck_tenants_*` | `ck_<新表名>_*` |

**改名落点**（单一事实源）：

- `packages/db/src/pg/schema/*.ts`：`pgTable("新名", …)`。
- `packages/db/src/pg/schema/index.ts`：**四张清单**（`TABLES_WITH_TENANT` / `TABLES_WITHOUT_TENANT` /
  `TABLES_TENANT_ROOT` / `TABLES_PLATFORM`）——`rls.ts` 的授权与策略、`schema.test.ts` 对
  information_schema 的对账，都以这四张清单为准。
- `packages/db/src/pg/rls.ts:90-98`：`REVOKE` / `GRANT` 的硬编码表名。
- `packages/db/src/pg/proto.ts:145`：一条裸 SQL（`max(r.version)`）。
- 测试里的裸 SQL：`audit-permissions.test.ts` / `driver.test.ts` / `management.test.ts` /
  `platform-settings.test.ts`。
- `packages/db/src/tools/migrate-sqlite.ts`：旧 SQLite 导入器的表名是历史 schema 的，**不动**。

### 4.3 逻辑删除

- 列：`del_flag char(1) NOT NULL DEFAULT '0'`，`'0'` = 存活、`'1'` = 已删。与参考项目同名同形，
  跨项目查表时不必再翻译一次。
- 加在 9 张业务实体表：`sys_tenant` / `sys_user` / `plat_operator` /
  `news_feed_source` / `news_candidate` / `news_task` / `news_script` / `proto_project` / `proto_revision`。
  （`plat_operator` 是补的：DB 层已经 REVOKE 掉它的 DELETE，不给这一列就永远删不掉。
  `news_setting` 是键值表、主键就是键本身，见 §12 第 2 条。）
- 不加在证据与流水表：`sys_audit_log` / `plat_audit_log` / `news_task_event` / `news_gate_result` /
  `news_confirmation` / `news_publish` / `news_render` / `usage_record` / `sys_session`
  —— 人工确认、闸门结果、审计日志是「人审 A、系统发 B」的对照物，删了就没法对账。

**唯一索引必须改成 partial（这次最容易漏、后果最隐蔽的一处）**：

软删之后，被软删的行**仍然占着唯一键**。不处理的话，「删掉一个叫 A 的 feed 源，再建一个同名同 URL 的」
会撞唯一索引，用户看到的现象是「明明删了却建不回来」，而且报错信息完全指不到软删上。

| 唯一索引 | 改成 |
|---|---|
| `u_news_feed_source_tenant_url` | `… ON (tenant_id, url) WHERE del_flag = '0'` |
| `u_news_candidate_tenant_norm` | `… ON (tenant_id, norm_key) WHERE del_flag = '0'` |
| `u_sys_user_tenant_email` | `… ON (tenant_id, email) WHERE del_flag = '0'` |
| `u_news_script_task` | `… ON (task_id) WHERE del_flag = '0'` |
| `u_proto_revision_project_version` | `… ON (project_id, version) WHERE del_flag = '0'` |
| `sys_tenant.slug` 的 unique | `… ON (slug) WHERE del_flag = '0'` |

（其余唯一索引所在表不在软删集合里，保持原样。）

- 读路径：这些表的查询统一调 `alive(t.delFlag)`（内部就是 `eq(delFlag, '0')`），不各处手写字符串比较。
- 写路径：目前全库只有一处真正的 DELETE（`platform-settings.ts:129` 换平台设置时删旧行），
  它是配置表，不在软删集合里，保持硬删。其余「删除」能力目前不存在——软删列是**地基**，不是新功能。
- **为什么是 `del_flag` 而不是时间戳软删**：`deleted_at` 能多表达「何时删的」，代价是与参考项目的
  形状不一致——同一套命名规范在两个仓库里各说各话，跨项目查表、写迁移都要多一层翻译。
  这里选一致性：`'0'` / `'1'` 足够表达「这一行还算不算数」，「何时删的」本来就有 `update_time`。

### 4.4 审计四件套（22 张表全部有）

| 列 | 类型 | 语义 | 谁写 |
|---|---|---|---|
| `create_by` | `numeric(19,0)`，可空 | 创建这一行的**人** | 应用层写；系统写入 = `NULL` |
| `create_time` | `text`，NOT NULL | 这一行落库的时刻 | 应用层写（沿用现在的 `nowIso()`） |
| `update_by` | `numeric(19,0)`，可空 | 最后一次改动这一行的**人** | 应用层写；系统写入 = `NULL` |
| `update_time` | `text`，NOT NULL | 最后一次改动这一行的时刻 | **数据库触发器**写 |

**操作人列不加外键**（照参考项目：`create_by bigint` 无 FK）。理由：

- 审计列要活得比人久。人被删了（软删）之后，审计行里那个 id 还得留着，否则「谁做的」就永远查不到了。
  加 FK 就得选 `onDelete: set null`（把证据抹掉）或 `restrict`（人永远删不掉）——两个都不好。
- 子表（`news_script` / `proto_revision` / `news_render`）没有 `tenant_id`，
  「操作人必须属于本租户」这件事用复合外键表达不了，要表达就得给每张表再加一套约束。
  而 actor id 永远来自已认证会话，不是用户输入的，DB 层再查一遍收益极低、复杂度极高。
- 想更强的话，正确的做法是给 RLS policy 加一条 `WITH CHECK (… EXISTS (SELECT 1 FROM sys_user …))`。
  **本次不做**：`rls.ts` 是全项目最要命的 fail-closed 代码，那段 `NULLIF` 少一个字就是隔离全开，
  不为了一个「理论上越权」的场景去动它。写成 §11 的待办。

**`NULL` 的统一含义**：不是「不知道是谁」，而是「**不是人做的**（系统写的）或该行没记录」。
界面要能区分「无人操作」与「操作人被删」——后者靠 id 查不到人来判断。

**更新路径必须带 `update_by`**：**43 处** UPDATE 语句要加一个 `actorId` 参数
（`packages/db/src/pg/` 39 处 + `apps/api/src/proto/proto.service.ts` 与 `apps/worker/src/proto.ts` 4 处）。
- 规则：`actorId` 是**显式参数**（`actorId: string | null`），不给默认值——强制调用方表态，
  忘了就编译不过，而不是静默写个 `NULL` 上去。
- worker 驱动的更新传 `NULL`（系统）。

**`update_time` 由触发器维护**（对齐参考项目 MySQL 的 `ON UPDATE CURRENT_TIMESTAMP`）：

```sql
CREATE OR REPLACE FUNCTION heron_set_update_time() RETURNS trigger AS $$
BEGIN
  NEW.update_time := to_char(now() at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"');
  RETURN NEW;
END $$ LANGUAGE plpgsql;
```

- 写入形状与 `nowIso()` **逐字符一致**（24 位 ISO-8601 UTC 带毫秒）——`time.ts` 的注释说过
  「两边不一致时前端 `new Date()` 会差一个时区」，触发器是第三处写入口，必须同一口径。
- 22 张表各挂一个 `BEFORE UPDATE` 触发器。应用层不必记得改这一列——忘了就是静默的错时间。
- 触发器**不碰 `update_by`**：触发器不知道是谁改的，这两件事分开。

**存量行回填**：加 `update_time` 的表，用 `UPDATE … SET update_time = create_time` 回填一次
（存量行从来没被改过，两个值本就相等）。加 `create_time` 的表，从 `created_at` 改名即可。

**`news_confirmation` 的冗余**：`create_by` 与既有 `confirmed_by` 同值（都是确认人）。
`confirmed_by` 保留：规格书 §5 的三样（确认人、确认时间、指纹）按名取列，不能被泛化列吃掉。

### 4.5 对象键（年月日层）

```
tenants/<tenantId>/<yyyy>/<mm>/<dd>/tasks/<taskId>/v<n>/<kind>.<ext>
例：tenants/1839203847562/2026/10/29/tasks/1839203847563/v1/mp4.mp4
```

- 日期取**这次渲染完成那一刻的 UTC 日期**，且**只算一次**：pipeline 算出 key 之后，
  这个 key 会一起写进 `news_render` 行；重试时如果沿用同一个渲染版本，就必须沿用同一个 key，
  否则会在旧日期段留下一个库里已经没有记录的孤儿对象（cleanup 按 DB 里的 key 删，孤儿就删不掉了）。
  → 规则：**key 由「渲染批次」决定，不由「上传时刻」决定**。跨零点重试时沿用原 key，
  版本号变了才换 key（换版本 = 新对象，旧的仍在库里，cleanup 照常删）。
- 租户前缀仍在最外层：按租户扫前缀仍是一次前缀扫；日期段可整段配生命周期规则（按天过期）。
- `segment()` 的校验不变（`/`、`\`、`..` 一律拒绝）；`key.ts` 注释里「理论上都是 uuid」改成「雪花 id」。
- 本地盘路径（`data/videos/<task-id>/`）**不动**：本地路径是发布与诊断的现场，与对象存储布局无关。

### 4.6 契约与前端

- `packages/contracts`：33 处 `z.uuid()` → `z.snowflakeId()`；`createdAt` / `updatedAt` →
  `createTime` / `updateTime`（16 处）。
- `apps/web/src`：19 处 `createdAt` / `updatedAt` 改名，涉及 14 个文件（11 个 Vue 视图 + 2 个测试 + 1 个 store）——机械改名，界面不动。
  已确认 `apps/web/src` 里 **0 处** uuid 引用，前端只把 id 当不透明字符串用。
- **操作人列不进 API 出参**：这次只落库。「谁改的」要做成界面是另一件事（要 join 用户表取昵称、
  要改 11 个视图的表格列），不在本次范围（§11）。

### 4.7 让结构保证规范（本次的代码优雅点）

**问题**：四件套要在 22 张表上出现，手写就是 22 份复制粘贴——下次加表的人忘了加，就是一张没有
`create_time` 的表，而且没有任何东西会报错。

**做法**：新增 `packages/db/src/pg/schema/columns.ts`，把列的「形状」收成构造器：

```ts
/** 主键列：标准 64 位雪花。标度钉 0，避免运算结果变成 "123.0" 这种废 id。 */
export function idCol() {
  return numeric("id", { precision: 19, scale: 0 }).primaryKey();
}

/** 外键列：与主键同 typmod（Postgres 要求 FK 两端类型完全一致）。 */
export function fkCol(name: string, target: () => AnyPgColumn) {
  return numeric(name, { precision: 19, scale: 0 }).references(target);
}

/** 审计四件套：22 张表一致，谁也不能少。 */
export function auditCols() {
  return {
    createBy: numeric("create_by", { precision: 19, scale: 0 }),
    createTime: tsCol("create_time"),
    updateBy: numeric("update_by", { precision: 19, scale: 0 }),
    updateTime: tsCol("update_time"),
  };
}

/** 业务实体表的逻辑删除标记：'0' 存活 / '1' 已删。证据与流水表不挂它。 */
export function softDeleteCols() {
  return { delFlag: char("del_flag").notNull().default("0") };
}

/** 读「还算数的行」只有这一种写法：alive(t.delFlag)，别在业务代码里散落 eq(x, "0")。 */
export function alive(delFlag: SQLWrapper) {
  return eq(delFlag, "0");
}
```

- 表定义因此变得很短：

```ts
export const newsTask = pgTable(
  "news_task",
  {
    ...auditCols(),
    id: idCol(),
    tenantId: fkCol("tenant_id", () => sysTenant.id),
    ...
  },
  ...
);
```

- **收益**：「每张表都有四件套」「主键都是 numeric(19,0)」「FK 与主键同型」这三条从「靠人记得」
  变成「靠结构」。再加一条 `schema.test.ts` 的 information_schema 断言做运行时兜底（§7）。
- **不抽接口**：只有一个实现、一个调用方，不存在「接口 + 实现」的分层必要。

**其余三处结构性收敛**：

1. **雪花生成器单例**：`packages/db/src/id.ts` 导出一个进程级实例的 `snowflakeId()`；
   单调状态在模块里，不散在调用点。
2. **格式规则单点**：`SNOWFLAKE_ID_PATTERN` 与 `z.snowflakeId()` 同在 `packages/contracts`，
   生成器 import 同一个 pattern 自检。
3. **清单单点**：带不带 `tenant_id`、是不是平台表、能不能软删，都写在
   `packages/db/src/pg/schema/index.ts` 的四张清单 + 一张 `SOFT_DELETE_TABLES` 里。

### 4.8 已实测的两段 SQL（pglite 真跑，脚本跑完即删）

方案里最巧的两段不能只写在纸上。2026-10-29 用 pglite（PG 17）实跑了一遍：

| 验证项 | 结果 |
|---|---|
| uuid → `numeric(19,0)` 映射表达式 | `'a1b2c3d4-e5f6-4789-a0b1-c2d3e4f50617'` → `2844626588163940`（52 bit，< 4.5e15 ✓） |
| 外键在改类型后仍然成立 | join 命中同一行，**孤儿行 0**（先 DROP constraint → 改列 → 重建 constraint） |
| `numeric(19,0)` 的标度钉 0 | `(1234567890123::numeric(19,0) / 2)::numeric(19,0)::text` → `617283945062`，**没有 `.0`** |
| 触发器写的时间形状 | `2026-10-29T03:04:10.755Z` —— 与 `nowIso()` 逐字符同形（24 位、毫秒、Z 结尾） |

（触发器用的是 **UTC**：本机当时是 11:04，库里写的是 03:04 —— 这正是 `nowIso()` 的口径。）

## 5. 迁移与快照

| 迁移 | 内容 |
|---|---|
| `0012_snowflake_ids.sql` | 一个 DO 块：收集并 DROP 依赖 uuid 列的外键 → 把 `public` schema 下所有 uuid 列转成 `numeric(19,0)`（确定性映射）→ 重建外键；两条 identity 序列列改常量 + 删序列；默认租户 id 改 `"1"` |
| `0013_domain_table_names.sql` | 22 张表 `ALTER TABLE … RENAME TO …`；索引 / 唯一约束 / CHECK 约束按 §4.2 同步改名；6 个唯一索引改 partial |
| `0014_audit_columns.sql` | `created_at → create_time`、`updated_at → update_time` 改名；按 §4.2 补齐缺失列；加 `create_by` / `update_by`；9 张业务表加 `del_flag char(1) NOT NULL DEFAULT '0'`；存量行回填 `update_time`；装 `heron_set_update_time()` 触发器（22 张表各挂一个） |

**drizzle 快照维护**（不做的话下一次 `drizzle-kit generate` 会把整库当成要重建）：

1. 改 `packages/db/src/pg/schema/*.ts`；
2. `drizzle-kit generate` 得到「drop/create」版本；
3. 手改成上面的 ALTER 版本（保数据），**同步手改 `drizzle/meta/*_snapshot.json`** 的表名 / 列类型 / 新增列；
4. 再跑一次 `drizzle-kit generate`：**应当输出无差异**——这是快照维护正确的自检，写进验证断言。

## 6. 影响面（证据）

### 触及的代码（from search_graph / trace_path）

| 符号 | qualified_name | 现有行数 | 角色 |
|---|---|---|---|
| `objectKey` | `D-zhoupei-heron.packages.storage.src.key.objectKey` | 11–22 | 直接改（加日期层） |
| `segment` | `D-zhoupei-heron.packages.storage.src.key.segment` | 28–32 | 直接改（注释） |
| `createObjectStore` | `D-zhoupei-heron.packages.storage.src.index.createObjectStore` | 16–19 | 不改（接口不变） |
| `listRenderObjectKeys` | `D-zhoupei-heron.packages.db.src.pg.artifacts.listRenderObjectKeys` | 226–249 | 不改（按 DB 列取 key） |
| `applyRls` / `withTenant` | `packages/db/src/pg/rls.ts` | 56–210 | 直接改（转换类型 + 表名） |

### 影响面（from trace_path / search_code）

- **`objectKey` 的 inbound callers**（2 个）：
  `apps.worker.src.pipeline.uploadArtifacts`（1 跳）、`apps.worker.src.pipeline.onEnter`（2 跳）。
- **`withTenant` 的 inbound callers**：**55 处**，横跨 `apps/api`（auth / events / manage / proto / review /
  settings / tasks）、`apps/worker`（cleanup / collect / p0 / proto / tick）、`packages/db`（seed 工具）。
  改的是 GUC 转换类型（`::uuid → ::numeric`）与表名，调用点签名不变。
- **`randomUUID`**：27 个文件、126 处（`packages/db` 54、`apps` 20），全部改成 `snowflakeId()`。
- **`z.uuid()`**：33 处（`z.uuid()` 22 处 + `z.string().uuid()` 11 处，全在 `packages/contracts/src/index.ts`），改成 `z.snowflakeId()`。
- **`createdAt` / `updatedAt` 属性改名**：全仓 **167 + 104** 处（contracts / api / worker / web / db），
  其中 `apps/web/src` 占 19 处、涉及 14 个文件（11 个 Vue 视图 + 2 个测试 + 1 个 store）。
- **UPDATE 语句加 `update_by`**：**43 处**（`packages/db/src/pg/` 39 处 +
  `apps/api/src/proto/proto.service.ts` 与 `apps/worker/src/proto.ts` 4 处）。
- **外键约束**：**33 条**分布在 22 张表上 → 由 0012 的动态 DO 块统一 drop/recreate，不手工枚举。

## 7. 验证断言（逐条核）

- [ ] 22 张表在 `information_schema` 里都是新名，且每张都有 `create_by` / `create_time` / `update_by` / `update_time`。
- [ ] 所有主键列与外键列都是 `numeric(19,0)`（`information_schema.columns.udt_name = 'numeric'` 且 `numeric_precision = 19`）。
- [ ] 通用审计列名是 `create_time` / `update_time`，领域列仍是 `_at`；库里不存在第三种后缀。
- [ ] 列名、drizzle 属性名、JSON 字段名三者一致（`create_time` ↔ `createTime` ↔ `createTime`）。
- [ ] 6 个唯一索引是 partial（`pg_index.indpred IS NOT NULL`），软删后同名同 URL 的 feed 源可以重建。
- [ ] `update_time` 由触发器维护：直接 UPDATE 一行后 `update_time` 前进、`create_time` 不动，
      且新值与 `nowIso()` 逐字符同形（`/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/`）。
- [ ] 操作人为 `NULL` 的行（系统写入）与有值的行（人工操作）都能落库并读回。
- [ ] 9 张业务表有 `del_flag char(1) NOT NULL DEFAULT '0'`，查询不到软删行；证据类表没有这一列。
- [ ] 主键列类型是 `numeric`，存量 uuid 已映射且 FK 全部对得上（无孤儿行）。
- [ ] 新建 id 单调递增、同一毫秒内不重复；跨进程（不同 `HERON_NODE_ID`）不撞号。
- [ ] `withTenant` 的租户隔离仍然 fail-closed：没设 GUC 时读不到任何行，设错租户读不到别人的行。
- [ ] `z.snowflakeId()` 拒绝 uuid、拒绝 0、拒绝超 2^63-1。
- [ ] `objectKey()` 产出 `tenants/<租户>/2026/10/29/tasks/<任务>/v1/mp4.mp4` 形状，且拒绝含 `..` 的段。
- [ ] 同一个渲染版本重试两次，key 相同（不因跨零点换日期而留下孤儿对象）。
- [ ] `drizzle-kit generate` 连跑两次无差异（快照与 schema 一致）。
- [ ] `pnpm check` 全绿（format → lint → typecheck → test）。

## 8. 分批计划

| 批 | 内容 | 验收 |
|---|---|---|
| **A** | 雪花 id：生成器 + 契约 + 列构造器 + schema 类型 + 126 处生成点 + RLS 转换 + 迁移 0012 | A 的断言 + `pnpm check` |
| **B** | 表改名：22 张 + 四张清单 + 裸 SQL + 索引/约束改名 + 6 个唯一索引改 partial + 迁移 0013 | B 的断言 + `pnpm check` |
| **C** | 审计四件套 + 逻辑删除：改名 + 补列 + 操作人 + 触发器 + 查询过滤 + 迁移 0014 | C 的断言 + `pnpm check` |
| **D** | 对象键年月日：`key.ts` + `pipeline.ts` 调用方 + 单测 | D 的断言 + `pnpm check` |

每批单独提交（提交需用户点头），批末把证据追加到 verification-lite 文档。

## 9. 代码规范（本次的硬约束）

1. **三层同名**：DB 列名 ↔ drizzle 属性名 ↔ JSON 字段名。下划线列名一律映射成 camelCase 属性，
   `create_time` ↔ `createTime`；不允许出现「列叫 A、字段叫 B」。
2. **结构保证优先于约定**：能用构造器表达的（四件套、软删列、主键列、外键列）就不手写；
   能用类型表达的就不靠注释提醒（§4.7）。
3. **两张清单是唯一事实源**：带不带 `tenant_id`、是不是平台表、能不能软删，都写在
   `packages/db/src/pg/schema/index.ts` 的清单里，不散落在各处 if 里。
4. **UPDATE 必须显式带 `actorId`**：不设默认值，忘了就编译不过，不靠「当前登录人」的隐式全局。
5. **注释只写「为什么」**：写清楚为什么这么设计、踩过什么坑，不复述代码在做什么。
6. **不静默**：缺列、类型不符、快照与 schema 不一致，都要报错，不许静默兜底。
7. **时间列只有两种后缀**：`_time`（行级审计）/ `_at`（业务事件），不出现第三种。
8. **每个迁移之后跑一次 `drizzle-kit generate`**：必须输出无差异，否则快照没跟上，后面每个迁移都在放大偏差。
9. **测试跟着改**：改名类改动必须有单测钉住新名字（`schema.test.ts` 的 information_schema 对账、
   `key.test.ts` 的键形状、`rls.test.ts` 的隔离），改名不是「顺手全局替换」。

## 10. 不可验证的盲点（主动声明）

- **生产库没有**：迁移只在 pglite 与本地 Postgres 上验过；真库上跑 `ALTER TYPE` 的锁与耗时未实测。
- **存量数据的雪花语义**：旧的 uuid 行映射成的是「低位随机数」，不是时间序；它们与新雪花行混排时，
  按 id 排序不等于按时间排序（按 `create_time` 排序不受影响）。
- **`numeric` 与 `bigint` 的差异**：参考项目用 `bigint`；本次用 `numeric(19,0)` 是为了 JS 侧零精度损失。
  性能上 numeric 比 bigint 略慢（十进制定点），主键索引在本项目的数据量级（十万行级）下无感，未实测。
- **时间列仍是 text**：参考项目用 `datetime`，heron 沿用自己的 ISO-8601 text 口径。
  好处是不动 API 形状；代价是排序依赖「同一格式定长」，且不能直接做时间算术。
  改成 `timestamptz` 会让 drizzle 行类型从 `string` 变 `Date`，波及所有 API 出参与契约 —— 属于本次之外的债。
- **触发器与批量更新**：`update_time` 由触发器写，批量 UPDATE 一次会多一次触发器开销；未做压测。
- **前端改名**：11 个 Vue 视图里的 `createdAt` / `updatedAt` 改成 `createTime` / `updateTime` 是机械改名，
  但**没有在浏览器里看过**渲染结果。
- **跨实例**：雪花 id 的节点号靠环境变量分配；多副本部署若忘了配 `HERON_NODE_ID`，会退化成
  `(hostname+pid) % 1024` 的兜底——同一副本重启后 pid 变化，不撞号，但**两副本 hostname 相同**的
  自建部署要显式配。文档要写死这条。

## 11. 本次不做的事（范围边界，写下来免得实现时顺手扩张）

- **操作人列不进 API 出参**：只落库。「谁改的」的界面是另一件事（要 join 用户表取昵称、改 11 个视图的表格列）。
- **不改 `timestamptz`**：时间列仍是 ISO-8601 text（§10 的理由）。
- **不做软删的界面与恢复功能**：软删列是地基，删除入口以后单独做。
- **不给操作人列加外键**：审计列要活得比人久（§4.4）。
- **不改 RLS policy 的结构**：只在 `::uuid → ::numeric` 这一处动它，其余不碰（fail-closed 的代码少动一行是一行）。
- **不改本地盘路径布局**：`data/videos/<task-id>/` 与对象存储布局无关。
- **不引软删插件**：drizzle 没有官方软删方案，手写 `alive(t.delFlag)` 过滤比引入一层抽象更直白。
- **不做数据回填脚本**：存量 uuid → numeric 由迁移里的映射表达式完成，无需人工脚本。

## 12. 实现中的偏离与理由（实现完成后补记）

实现过程中发现的问题与本文原设计有出入的地方，逐条写下来，免得后来的人对着文档找不着。

| # | 原设计 | 实际做法 | 为什么 |
|---|---|---|---|
| 1 | 三条迁移（0012 类型 / 0013 表名 / 0014 列） | **一条** `0012_domain_schema.sql` | 三条会分三次提交事务，中间那条失败就留下「表名改了、列类型没改、外键指向半路」的库。一条迁移是一个事务：要么全成，要么全不成。 |
| 2 | 10 张业务表加 `del_flag` | **9 张**（去掉 `news_setting`） | 它是键值表，主键就是键本身。软删的行仍然占着这个键，再写同名的设置就撞主键——软删在这里没有意义（设置的「删掉」就是写回一个值或退回默认）。 |
| 3 | 导出名与表名同名（`tenants` → `sysTenant`） | **导出名不动** | 导出名同时是 API 出参的来源（`{ tasks: [...] }` 这类载荷键）。改名会连 API 契约与 12 个视图一起改，而这次要改的是**存储布局**。表名与领域概念名分开，是有意的。 |
| 4 | 操作人列参考项目加外键 | **不加外键** | 审计列要活得比人久：人软删之后，审计行里那个 id 还得留着，否则「谁做的」永远查不到。加外键只能在「抹掉证据」与「人永远删不掉」之间二选一。参考项目的 `create_by bigint` 同样没有外键。 |
| 5 | 软删表的读路径统一过滤 | **只过滤「现在是什么」类** | 去重判据、额度统计、审计、发布记录这些是「发生过什么」：软删一条已发布的任务，不代表它没发布过——那些查询要连软删行一起看，否则同一条热点会被发第二遍。 |
| 6 | 时间列不给 DB 默认值（沿用旧约定） | **给默认值** | 旧约定是「漏写就炸」，那是因为列少、写入点少。现在有 44 个审计列 × 30 多个写入点，手写就是几十处「必须记得改」的地方。默认值与 `nowIso()` 逐字符同形（pglite 实测 `2026-10-29T03:04:10.755Z`），不违背「口径一致」这条老规矩。 |
| 7 | `update_time` 由应用层写 | **由触发器写** | 参考项目用的是 MySQL 的 `ON UPDATE CURRENT_TIMESTAMP`，Postgres 没有内置等价物。触发器写时间、应用层写操作人，两件事分开。 |
| 8 | `bigint` 列 | **`numeric(19,0)`** | 已在 §4.1 说过：JS 侧零精度损失，id 继续是不透明字符串。标度钉 0 是必须的——裸 `numeric` 会把 `id / 2` 存成 `...563.0`。 |
| 9 | `DEFAULT_TENANT_ID` 用一个雪花 id | **保留哨兵 `"1"`** | 第一个租户是种子数据，它的 id 必须固定可引用。哨兵与任何雪花 id 都不会撞（雪花至少 8 位、远大于 1）。迁移里把老哨兵 uuid 映射后的值（4）归一到 1。 |
| 10 | 软删列是 `deleted_at`（时间戳，`null` = 存活） | **`del_flag char(1) NOT NULL DEFAULT '0'`** | 与参考项目、与这套表命名规范保持同一个形状：同一套规范在两个仓库里各说各话，跨项目查表、写迁移都要多一层翻译。代价是丢掉「何时删的」——但 `update_time` 本来就在答这个问题。实际改动是列名 + 四处谓词（`alive()`、6 个 partial 索引、2 个 `onConflictDoUpdate` 的 `targetWhere`、迁移 DDL），快照按旧办法重生成后 `drizzle-kit generate` 仍报无差异。 |

### 实现中还踩到的三个坑（都写进了注释）

1. **SQL 字符串里的引号要成对加倍**：迁移里那段映射表达式的 `'x'` 写成 `'x''` 会让字面量提前结束，Postgres 报的是「括号不匹配」——离真正的原因隔了三层。
2. **PL/pgSQL 的循环变量会遮蔽表别名**：循环变量叫 `con`，子查询里再写 `pg_constraint con` 就取不到字段（报「record con has no field conindid」）。子查询要另起别名。
3. **改列类型会重写整张表**：堆顺序随之改变，「靠插入顺序」的断言会飘（本项目踩到一次：会话审计的顺序断言）。修法是给查询加 `ORDER BY`。
