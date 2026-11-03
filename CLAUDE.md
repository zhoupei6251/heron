# heron 后端（RuoYi-Vue-Plus 6.0.0）

> 项目级指令，覆盖 `~/.claude/CLAUDE.md` 中冲突的默认习惯；全局的 L1–L4 自检（能配置/能复用/一条命令解决就不写代码）仍然有效。

## 这是什么

Heron 业务管理系统，基座 RuoYi-Vue-Plus 6.0.0（Spring Boot 4.1.2 / JDK 21 / MyBatis-Plus / Sa-Token / Jetty）。
业务模块 `ruoyi-modules/ruoyi-heron`，包 `org.dromara.heron`，由 `ruoyi-admin` 统一启动。
业务规格（唯一有效版）：`docs/plans/2026-10-29-heron-business-spec.md`。单租户自部署，两条业务线：新闻视频生产线、原型工坊。
前端在 `../heron-ui`（plus-ui）。

## 写代码前必读（不得凭记忆）

1. Read `.codex/skills/ruoyi-plus-ai-coding/SKILL.md`
2. 后端任务读 `.codex/skills/ruoyi-plus-ai-coding/references/backend.md`；配套前端页面读 `references/frontend.md`
3. 不确定任务边界或需要标准用法时再读 `references/examples.md`

`.claude/agents/*.md` 是分场景子代理定义：`backend-crud`、`backend-query-permission`、`backend-module-enhancement`、
`backend-common-infrastructure`、`backend-javadoc`、`backend-engineering`。

## 取样顺序（冲突时从高到低）

1. 当前模块内最近似的业务代码
2. 仓库公共基础模块约定（`ruoyi-common-*`）
3. 代码生成器模板 `ruoyi-modules/ruoyi-gen/src/main/resources/fm/` —— **是 `fm/`，不是 `vm/`**
4. 通用 Spring Boot / MyBatis-Plus 习惯

## 不可妥协的约定

分层固定 `domain` / `domain.bo` / `domain.vo` / `mapper` / `service` / `service.impl` / `controller`：不发明新分层，不把业务堆进 controller。

- entity 继承 `org.dromara.common.mybatis.core.domain.BaseEntity`，`@TableName` + `@TableId`；有 `delFlag` 保留 `@TableLogic`，有乐观锁保留 `@Version`
- mapper 继承 `BaseMapperPlus<Entity, Vo>`；`selectVoList` / `selectVoPage` 不够才补 XML 或自定义方法
- BO `@AutoMapper(target = Entity.class, reverseConvertGenerate = false)`；VO `@AutoMapper(target = Entity.class)`；请求/查询专用字段（含 `params`）放 BO，展示派生字段和 `@Translation` 放 VO
- BO 转 entity 一律 `MapstructUtils.convert(bo, Entity.class)`
- 查询用 `QueryBuilder.lambda(Entity.class)` / `BaseMapperPlus#lambda()` + `eqIfText` / `likeIfText` / `eqIfPresent` / `inIfNotEmpty` / `betweenParams`；日期范围从 `bo.getParams()` 取 `begin*/end*`；不手写 SQL、不自造 wrapper
- 分页：`mapper.selectVoPage(pageQuery.build(), lqw)` → `PageResult.build(records, total)`；分页对象只用 `PageQuery` / `PageResult`
- controller 继承 `BaseController` 返回 `R<T>`；权限 `@SaCheckPermission("${module}:${business}:${action}")`；写/导出接口配 `@Log(businessType = ...)`，附近有防重就配 `@RepeatSubmit`；分组校验用 `AddGroup` / `EditGroup`
- 业务失败抛 `ServiceException`；不吞异常、不静默重试、不静默降级
- 多表写操作 `@Transactional(rollbackFor = Exception.class)`；模块已有 `@Cacheable` / `CacheUtils` 的，写操作要同步失效缓存
- 新增工具类或通用辅助方法前，先按**能力类别**检索同模块、`ruoyi-common` 与已有依赖（不能只搜拟新增的方法名），并核对为空/非法输入/默认值/异常语义；满足需求就复用，不写无业务语义的包装
- 优先使用 `MapstructUtils`、`StringUtils`、`StreamUtils`、`ValidatorUtils`、`SpringUtils`、`RedisUtils` 等项目工具

## 验证

- 构建：`mvn -pl ruoyi-admin -am package`，产物 `ruoyi-admin/target/heron.jar`
- 改 Java 至少保证目标模块编译通过；只改注释/文档不跑构建，也不做无关格式化或 import 重排
- 运行前提：MySQL 库 `heron`（先导 `script/sql/ry_vue.sql`），**Redis 必需**（Sa-Token / Redisson）
- 格式：UTF-8、LF、4 空格（JSON/YAML 2 空格）

## 边界

- 不引入多租户、套餐、配额、计费、自助注册、微服务、第二套 RBAC；账号、审计、存储、定时任务、在线会话、代码生成都用基座
- 发布与交付必须人确认；任何"自动跳过闸 / 无人值守直发"的实现都不接受
- 每个失败必须能回答：哪条闸 + 哪段输入 + 为什么拒
