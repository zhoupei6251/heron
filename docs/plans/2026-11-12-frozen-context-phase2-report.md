# 冻结上下文 · Phase 2 实施报告

> 基线 `dev` @ `104a4ab03`（Phase 1 收尾）。Phase 2 = 建立真正的 Frozen Context：
> 一份不可变、可校验、带版本链的生产输入。
> 未触碰 Render / FFmpeg / HyperFrames / MinIO，未改任何既有列。

## 1. 交付内容

**新增**

| 文件 | 作用 |
| --- | --- |
| `script/sql/heron/028-production-context.sql` | `heron_production_context` 表 |
| `domain/HeronProductionContext.java` | 实体（`BaseEntity` 五件套，无新增约定） |
| `mapper/HeronProductionContextMapper.java` | `BaseMapperPlus`，无自定义 SQL |
| `domain/FrozenContext.java` | 契约版本、规范化、摘要、行视图 |
| `service/impl/FrozenContextService.java` | `freeze` / `byTask` / `verify`，**没有 update/delete** |

**接入**

- `NewsProductionDomain.reserve()`：快照组装完成后先 `freeze(...)` 落独立上下文行，再把同一份字节写给 `heron_video_version.input_snapshot`；`schemaVersion` 改用 `FrozenContext.SCHEMA`。
- `NewsProductionPipeline.execute()`：读输入前 `contexts.verify(taskId, inputJson)`，上下文行存在但 hash 对不上即 `news_context_mismatch`（409），不会进入取数/写稿。
- `HeronProductionConfiguration`：流水线 bean 多注入 `FrozenContextService`。
- `NewsMysqlTestSupport`：隔离库建 028 表、每例 DROP。

## 2. 关键设计（为什么这样做）

1. **hash 不取写库字符串**。MySQL `json` 列会重新规范化存储（去空白、键重排），读回来的文本和写进去的不逐位相等。所以 `FrozenContext.hash()` 走「解析 → 递归排序键 → sha256」，写入侧与校验侧同一条路径。`FrozenContextTest` 里有一条专门用「键序不同 + 带缩进」的两段文本断言 hash 相等。
2. **不可变靠没有写入口**，不靠调用方自觉：`FrozenContextService` 只有 insert 与 select；改内容只能由上层新建版本（新 `context_no` + `parent_context_id`）。
3. **不新增既有表的列**：上下文行以 `version_id`/`task_id` 反查，`heron_video_version` 与 `heron_production_task` 的 JSON 形状一个字没动，前端与既有只读路径零影响。
4. **兼容老任务**：没有上下文行的 task 视为迁移前任务，`verify` 返回 null 放行；一旦有行就必须吻合——否则历史在途任务会集体失败。
5. **版本门禁**：上下文 `schema_version` 不在 `{1.0}` 直接 `news_context_schema`，避免用未知契约的内容生产。

## 3. 表结构

```
heron_production_context
  context_id PK / work_id / version_id (uk) / task_id (ix)
  context_no (uk with work_id)   -- 与版本号同源
  parent_context_id              -- 上一版；首版为空
  revision_reason                -- reserve
  schema_version / content_hash (ix) / content_json
  + 审计五件套
```

## 4. 验证

```powershell
.\mvnw.cmd -o -pl ruoyi-modules/ruoyi-heron -am test
→ Tests run: 240, Failures: 0, Errors: 0, Skipped: 5 — BUILD SUCCESS
```

新增用例：`FrozenContextTest`(5)、`FrozenContextServiceTest`(5) 已执行；`ProductionContextTest`(3) 属 `@Tag("mysql")` 组，**本机未配 `HERON_TEST_MYSQL_URL`，未执行**——只做到编译通过。这三条覆盖的正是「落库 hash 与 MySQL 规范化后一致」「第二版指向第一版」「篡改后被拒」，属于本次改动的核心断言，必须在有隔离库的环境补跑。

## 5. 上线顺序（重要）

1. 先执行 `028-production-context.sql`；
2. 再发应用。

反过来会让 `reserve()` 在插入上下文时报表不存在——即**所有生产提交直接失败**（不是静默降级）。回滚同理：代码回退后旧行留在库里，不影响旧逻辑。

## 6. 留给后续

- **Phase 3**：文章正文、dataset 响应、图片等 Artifact 冻结（当前仍现抓，`NewsModelScriptAuthor` 与 `DatasetFetchService` 未动）。
- **Phase 4**：写稿 prompt 消费 `editorialIntent` / `reason` / `storyAngle`，稿件与审稿绑定 context/script hash。
- **Phase 5**：claim 成功 submit 失败仍无补偿；`heron_auto_slot` 无 fallback 列；重试仍复用同一 task（幂等键未细化到阶段）。
- 上下文行本身没有 retention/cleanup 策略，等 Artifact 清理一起设计。
