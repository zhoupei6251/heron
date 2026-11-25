# 删除原型线 · 视频四线拆枚举 · music_video 占位

**日期**：2026-11-25
**状态**：设计待实施
**范围**：heron 后端 + heron-ui 前端 + heron 数据库

---

## 0. 一句话

原型工坊整线作废：代码、前端、菜单权限、两张表、历史任务全部删除。
`ProductionBizType` 从「新闻视频 / 原型」两个值，改为「视频四线各一个值 + music_video」，
让业务线清单在枚举里看得见。music_video 先占位，没有流水线。

---

## 1. 已核实的事实（决定了实现方式）

| 事实 | 位置 | 影响 |
| --- | --- | --- |
| `biz_type` 是 `varchar(16)` | `001-production.sql:3,15` | `knowledge_explainer` 19 字符，**迁移必须先加宽**，否则严格模式报 1406 |
| 线的选择今天在 `reserve()` 时冻结，晚于 `submit()` 写 `biz_type` | `NewsProductionDomain:205`、`ProductionSubmitBo` | 拆枚举后线必须在 `submit()` 前算出来 |
| 自动线里 `picked.selection()` 先于 `submit()` 确定 | `NewsAutoService:243,268` | 自动线可以在提交前已知线，不需要回退 |
| 手工线 `generate()` 提交时不带 selection | `NewsWorkServiceImpl:352` | 手工路径的线只能从**已冻结的上一版快照**取 |
| 存量任务 `input_snapshot.kind` 是 `article`/`data_board`/`trend`/`knowledge_explainer` | `NewsProductionDomain:93-111` | 回填是确定性映射，不是猜 |
| 四线的产物清单与闸门靠快照 `kind` 分支 | `NewsProductionDomain:441-452` | `kind` 必须留在快照里，不能拿枚举替换 |
| `axure/` 未进 git | `git ls-files axure` = 0 | 本地目录，需人工删，不在提交内 |
| `001~045` 是手工迁移历史，Flyway 基线 45 | `db/migration/README.md` | 只能新增迁移删数据，不能改历史脚本 |

---

## 2. 枚举设计

```java
package org.dromara.heron.domain;

/**
 * 固定生产业务类型，不接受任意业务标识。
 * 视频四线共用新闻流水线，靠冻结快照的 kind 决定产物与闸门。
 */
public enum ProductionBizType {
    news_explainer,
    data_board,
    trend_insight,
    knowledge_explainer,
    music_video;

    /** 共用新闻流水线的四条线。 */
    public static List<ProductionBizType> videoLanes() {
        return List.of(news_explainer, data_board, trend_insight, knowledge_explainer);
    }

    /** 冻结快照 kind → 业务线。唯一映射点，别处不许再写 if。 */
    public static ProductionBizType fromKind(String kind) {
        return switch (kind == null ? "" : kind) {
            case "data_board" -> data_board;
            case "trend" -> trend_insight;
            case "knowledge_explainer" -> knowledge_explainer;
            default -> news_explainer;
        };
    }

    /** 冻结快照里 `article` 是文稿解说线的 kind，枚举侧叫 news_explainer。 */
    public String pipelineBean() {
        return this == music_video ? null : "newsProductionPipeline";
    }

    public String domainBean() {
        return this == music_video ? null : "newsProductionDomain";
    }

    public String generatePermission() {
        return this == music_video ? "heron:musicVideo:generate" : "heron:newsWork:generate";
    }

    public String allPermission() {
        return this == music_video ? "heron:musicVideo:all" : "heron:newsWork:all";
    }
}
```

### 关键取舍

**`kind` 留在冻结快照里，不删。** 四线的产物清单（`KINDS` / `DATA_KINDS` / `TREND_KINDS` /
`KNOWLEDGE_KINDS`）和闸门集合本来就靠它分支。拿枚举替换会动 `NewsProductionDomain.validateManifest`
和 `NewsProductionPipeline` 两个大文件，跟删原型无关。枚举只负责「业务线是谁」，
`kind` 只负责「这条线要交什么产物」。

**映射方向单向。** 只要 `kind → ProductionBizType`。落库的 `biz_type` 从此是枚举名，
不再需要反向推导。`NewsProductionDomain` 里四处 `ProductionBizType.news_video.name().equals(task.getBizType())`
改为 `ProductionBizType.valueOf(task.getBizType()).videoLanes().contains(...)` 太啰嗦，
直接改成 `!= ProductionBizType.music_video`。

---

## 3. 线在提交前怎么确定

`submit()` 写 `biz_type`，`reserve()` 才冻结线——拆枚举把这个先后关系顶到了台面上。
三条入口分别处理：

| 入口 | 线的来源 | 改法 |
| --- | --- | --- |
| 自动线 `generateForAuto` | `selection.line()` 已在手上 | `ProductionBizType.fromKind(kindOf(selection))` |
| 手工线 `generate` | 提交时不带 selection | 读当前版本快照的 `$.kind`；无当前版本时按 `news_explainer` |
| 策划结论改写 `override` | override 里有 selection 节点 | `fromKind(kindOfLine(override.selectionNode()))` |

`NewsProductionDomain.reserve()` 里现有的
`command.bizType() != ProductionBizType.news_video` 改成一个断言：
线必须与快照 `kind` 推出的一致，不一致就抛 `409 news_line_conflict`。
这是新增的一道自洽校验，成本一行，挡的是「biz_type 写 data_board 但快照是 article」这种
会让 lane 容量统计和产物闸门对不上的错配。

手工线读上一版快照取 kind 的写法，**在 `NewsWorkServiceImpl` 里加一个私有方法**，
不要暴露成公共 API：

```java
/** 手工路径没有 selection，线只能取已冻结的上一版；没有上一版就按解说线。 */
private ProductionBizType lineOf(HeronNewsWork work) {
    HeronVideoVersion version = currentVersionOf(work);
    if (version == null) return ProductionBizType.news_explainer;
    return ProductionBizType.fromKind(JsonUtils.getJsonMapper()
        .readTree(version.getInputSnapshot()).path("kind").asString(""));
}
```

---

## 4. 原型线删除清单

### 4.1 后端整文件删除（20 主 + 8 测试）

```
controller/ProtoController.java
domain/HeronProtoProject.java
domain/HeronProtoVersion.java
domain/bo/ProtoDeliverBo.java
domain/bo/ProtoGenerateBo.java
domain/bo/ProtoNativeVerificationBo.java
domain/bo/ProtoProjectBo.java
domain/bo/ProtoProjectQuery.java
domain/bo/ProtoReviewBo.java
domain/vo/ProtoDeliveryVo.java
domain/vo/ProtoNativeVerificationVo.java
domain/vo/ProtoProjectVo.java
domain/vo/ProtoVersionVo.java
mapper/HeronProtoProjectMapper.java
mapper/HeronProtoVersionMapper.java
service/IProtoProjectService.java
service/ProtoRp1Author.java
service/impl/ProtoModelRp1Author.java
service/impl/ProtoProductionDomain.java
service/impl/ProtoProductionPipeline.java
service/impl/ProtoProjectServiceImpl.java
resources/prompt/prototype/            （整个目录：rp1-authoring-contract.md 等）
```

测试：

```
service/ProtoAccessTest.java
service/ProtoDeliveryTest.java
service/ProtoEnginePipelineTest.java
service/ProtoRp1AuthorTest.java
service/ProtoVersionTransactionTest.java
service/impl/ProtoManifestMediaTest.java
support/ProtoMysqlTestConfiguration.java
support/ProtoMysqlTestSupport.java
```

### 4.2 后端必须改组（删分支，不是删文件）

| 文件 | 改动 |
| --- | --- |
| `ProductionBizType.java` | 按 §2 重写 |
| `HeronAccessService.java` | 删两个 `static` 权限方法，改调枚举实例方法；调用点同步 |
| `ProductionServiceImpl.java` | 删 `PROTOTYPE_CAPACITY` 与容量判断；`domain(type)` 走 `type.domainBean()`；`queryPageList` 的 `allNews`/`allProto` 两分支改为遍历枚举 |
| `ProductionExecutionServiceImpl.java:516` | `type.domainBean()` |
| `ProductionRecoveryService.java:142` | `type.pipelineBean()` |
| `ProductionRunner.java:206` | `type.pipelineBean()`；`pipeline(null)` 返回 null，music_video 自然不轮询 |
| `ProductionArtifactService.java` | 删 `prototypeMaxBytes` 分支（统一 `newsMaxBytes`）；删 `versionNo` 五参重载与全部调用；`finalDirectory` 固定 `videos` 与 `run.versionId()`；`storageKey` 只认 `videos/` |
| `ProductionCapabilityService.java` | 删原型能力项；`resolvable()` 删 `zee-rp` 分支 |
| `ProductionCapabilitiesVo.java` | `(Capability news, Capability musicVideo)`，**删 `targetAxureVersion` 字段** |
| `HeronProductionConfiguration.java` | 删 `prototypeProductionPipeline` 与 `prototypeCapabilitySnapshot` 两个 bean 及 `heron.prototype.engine-assets` 全部校验 |
| `HeronProductionProperties.java` | 删 `prototypeMaxBytes`、`prototypeTemplate`、`zeeRp` |
| `ProductionController.java:62` | `requireAnyPermission("heron:newsWork:query", "heron:musicVideo:query")` |
| `HeronHealthServiceImpl.java:70,73` | 今日任务数按 `videoLanes()` 的 `in` 统计，不是单 `news_video` |
| `EditorialLineEditor.java:496` | `decisionRun()` 的 `news_video` → `news_explainer` |
| `QualityRepairService.java:230,335` | 同上，按工作当前版本的线取枚举 |
| `NewsWorkServiceImpl.java` | 删 `TYPE` 常量，`generate` / `generateForAuto` / story 改写三处改为计算出的线；新增 `lineOf()` |
| `NewsProductionDomain.java` | 删 `prototype` 相关三元；`requireTask` 的 `news_video` 判断改 `!= music_video`；`reserve` 加线自洽断言 |

### 4.3 配置

`ruoyi-modules/ruoyi-heron/src/main/resources/heron-production.yml`：删 `prototype-max-bytes`、`zee-rp`、`prototype-template`，删整个 `heron.prototype:` 块。

`ruoyi-admin/src/main/resources/application-dev.yml`、`application-test.yml`：同样三处 + 整个 `prototype:` 块（含 `engine-enabled`、`engine-assets`）。

### 4.4 前端

删除：

```
src/api/heron/proto/          （整个目录：index.ts、types.ts）
src/views/heron/proto/        （整个目录：index.vue）
```

改：

| 文件 | 改动 |
| --- | --- |
| `src/api/heron/production/types.ts` | `ProductionBizType` 改五值；`ProductionCapabilities` 的 `prototype` 改 `musicVideo`；删 `targetAxureVersion` |
| `src/views/heron/production/index.vue:17` | 下拉项五条；`:242` 能力提示改为遍历 `['news','musicVideo']` |
| `src/views/heron/production/task-state.ts:26` | 业务线文案表改五条 |

文案：`news_explainer` 文稿解说、`data_board` 数据看板、`trend_insight` 趋势洞察、
`knowledge_explainer` 知识解释、`music_video` 音乐视频。

---

## 5. 数据库迁移

新增**一个**迁移文件 `VyyyyMMddHHmmssSSS__drop_prototype_split_video_lanes.sql`。
`003-prototype.sql` 与 `004-menu.sql` 属基线历史，**不改**——新库先由它们建出原型结构，
再由这个迁移删掉，净效果为零，同一件事仍只有一处可执行定义。

### 5.1 语句顺序（顺序不能换）

```sql
-- 1. 加宽：knowledge_explainer 19 字符，varchar(16) 装不下
ALTER TABLE heron_production_lane MODIFY biz_type varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
ALTER TABLE heron_production_task MODIFY biz_type varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

-- 2. 回填：news_video 按冻结快照的 kind 拆到四条线
UPDATE heron_production_task
SET biz_type = CASE JSON_UNQUOTE(JSON_EXTRACT(input_snapshot, '$.kind'))
    WHEN 'data_board'          THEN 'data_board'
    WHEN 'trend'               THEN 'trend_insight'
    WHEN 'knowledge_explainer' THEN 'knowledge_explainer'
    ELSE 'news_explainer'
  END
WHERE biz_type = 'news_video';

-- 3. 清原型任务：先子表后主表，否则主表删了子表就成孤儿
DELETE FROM heron_task_step           WHERE task_id IN (SELECT id FROM heron_production_task WHERE biz_type = 'prototype');
DELETE FROM heron_usage_record        WHERE task_id IN (SELECT id FROM heron_production_task WHERE biz_type = 'prototype');
DELETE FROM heron_production_context  WHERE task_id IN (SELECT id FROM heron_production_task WHERE biz_type = 'prototype');
DELETE FROM heron_production_artifact WHERE task_id IN (SELECT id FROM heron_production_task WHERE biz_type = 'prototype');
DELETE FROM heron_production_task     WHERE biz_type = 'prototype';

-- 4. 丢原型表
DROP TABLE IF EXISTS heron_proto_version;
DROP TABLE IF EXISTS heron_proto_project;

-- 5. 容量锁行：五条线各一行
DELETE FROM heron_production_lane;
INSERT INTO heron_production_lane (biz_type, create_time, update_time) VALUES
  ('news_explainer',      UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('data_board',          UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('trend_insight',       UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('knowledge_explainer', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)),
  ('music_video',         UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 6. 原型菜单与角色授权（004-menu.sql 的固定保留段 2100-2110）
DELETE FROM sys_role_menu WHERE menu_id BETWEEN 1761400000000002100 AND 1761400000000002110;
DELETE FROM sys_menu      WHERE menu_id BETWEEN 1761400000000002100 AND 1761400000000002110;
```

回滚方式写在文件末尾注释：第 4 步 DROP 的两张表不可恢复，回滚只能重建空表；
第 2、3 步的数据删除同样不可逆。执行前需备份。

### 5.2 为什么回填安全

`uk_task_version (biz_type, version_id)` 里的 `version_id` 是雪花主键，全局唯一，
拆成四线后不可能在两线之间撞唯一键。

### 5.3 迁移外的人工动作（不进 git）

- 删除本地 `axure/` 目录（`blank-rp9.rp`、`rp9-app/`、`build-*`）——未跟踪，不在提交内
- 删除 `data/protos/` 下的历史产物目录
- 若工作区平级还留着 `zee-rp-v0.2.1`，按需删

---

## 6. music_video 的占位行为

有枚举值、有 lane 行、有权限名，**没有 domain bean、没有 pipeline bean**。

- `ProductionRunner.tick()` 遍历到 `music_video` → `pipelineBean()` 为 null → `pipeline()` 返回 null → `poll()` 返回 false。不轮询、不报错。
- `submit(music_video)` → `lockLane` 通过 → `domain(type)` 抛 `503 production_capability_missing`。**确定性失败，不是静默成功**，与今天 prototype 未开引擎时的行为一致。
- `ProductionCapabilityService` 对 `music_video` 恒报 `pipeline_missing`，前端显示「音乐视频线未就绪：pipeline_missing」。

**已知缺口（有意留下）**：`heron:musicVideo:*` 两个权限没有菜单种子行，
任何角色都拿不到，流水线真接上也提交不了。这是「先占位」的必然结果——
接流水线时必须同时补菜单行，否则会卡在一个 404 上查半天。在 `ProductionBizType`
的 `music_video` 分支上方写一行注释说明这件事。

---

## 7. 验证

1. `mvn -pl ruoyi-admin -am package` 通过
2. 迁移在干净库（`heron_dev`）上跑通：`001~045` → 启动 → Flyway 执行新迁移 → `flyway_schema_history` 有一条新记录
3. 迁移在**已有数据**的库上跑通：先造几条 `biz_type='news_video'` 的 task（不同 kind），执行后确认 `biz_type` 已按 kind 拆开
4. 回归测试：`ProductionAccessTest`、`ProductionCleanupTest`、`ProductionTransactionTest`、`ProductionWorkerTest`、`ProductionCapabilityTest`、`ProductionArtifactTest` 里所有 `ProductionBizType.prototype` 断言改为对应视频线或删除
5. `production-fixture.sql:7` 的 `'prototype'` 行改为 `'news_explainer'`
6. 前端 `npm run build`（或 dev 启动）通过，生产任务页下拉五项，能力提示不再出现原型

---

## 8. 不做的事

- 不把 `kind` 从冻结快照里去掉
- 不改 `ProductionSubmitBo` 的 record 结构（它已经有 `bizType`，只是值变了）
- 不动 `data/templates/**`、`data/tools/**`
- 不动 `.impeccable/config.json` 等本次无关的已修改文件
- 不为 music_video 写 domain / pipeline / 输入契约 / 模板
- 不重构 `NewsProductionPipeline`（2845 行，与本任务无关）

---

## 9. 风险

| 风险 | 说明 | 处置 |
| --- | --- | --- |
| 数据不可逆 | 第 2、3 步与 DROP 表都不可恢复 | 执行前备份 `heron_production_*`、`heron_proto_*` |
| 在途原型任务 | 迁移时若有 `prototype` 任务在跑，任务行被删后 worker 仍持有 runToken | 迁移前停 worker（`worker-enabled: false`），确认无在途任务 |
| 手工线取线依赖上一版快照 | 全新作品首次手工生成时没有上一版，一律按解说线 | 有意的默认：解说线是唯一不依赖数据源的线，与 `kindOf` 现有兜底口径一致 |
| 历史 `news_video` 任务的 kind 缺失 | `input_snapshot` 里没有 `$.kind` 时回填为 `news_explainer` | 与 `kindOf(null)` 的现有兜底一致，不新增语义 |
