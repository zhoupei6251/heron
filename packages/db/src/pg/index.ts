/**
 * 多租户数据面（Postgres + Drizzle + RLS）的导出面，对外是 `@heron/db/pg` 这个子路径。
 * 包根只剩与数据面无关的共用件（见 ../index.ts）。
 *
 * 为什么是子路径而不是并进包根：`openDb("memory")` 与 `openDb(DATABASE_URL)` 的差别
 * 不在类型上，而在「这进程连的是哪个库」。子路径让「要开库」这件事写在 import 语句上，
 * 顺手 import 一个路径常量不会把驱动拖进来。
 *
 * 这里**故意不导出 ./admin**：特权通道（heron_owner / BYPASSRLS）只能按深路径 import，
 * eslint 的 no-restricted-imports 因此能按路径把「业务代码偷偷拿特权连接」挡住。
 * 需要它的只有两处：packages/db 内部，和跨租户调度的 apps/worker/src/scheduler.ts。
 */
export * from "./artifacts";
export * from "./candidates";
export * from "./driver";
export * from "./identity";
export * from "./migrate";
export * from "./rls";
export * from "./settings";
export * from "./schema/index";
export * from "./tasks";
export * from "./tenants";
export * from "./usage";
export * from "./management";
// Narrow platform operations only; withAdmin remains private to its deep module.
export * from "./platform";
