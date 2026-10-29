import { eq, sql, type SQLWrapper } from "drizzle-orm";
import { char, numeric, text, type AnyPgColumn } from "drizzle-orm/pg-core";

/**
 * 列的「形状」收在这一处：主键、外键、审计四件套、软删标记、时间列。
 *
 * 为什么要有这一层：四件套要出现在 22 张表上，手写就是 22 份复制粘贴——
 * 下次加表的人忘了加，就是一张没有 `create_time` 的表，而且没有任何东西会报错。
 * 收成构造器之后，「每张表都有四件套」「主键都是 numeric(19,0)」「外键两端同型」
 * 从「靠人记得」变成「靠结构」。
 */

/**
 * 时间列的默认值：UTC 的 ISO-8601，和 packages/db/src/time.ts 的 `nowIso()` 逐字符同形。
 *
 * 为什么是数据库默认值而不是应用层写：审计列有 44 个（22 表 × 2），
 * 每个 insert 都写一遍就是 44 处必须记得改的地方；漏一处就是一行没有时间戳的记录。
 * 数据库给默认值，漏写也不会漏时间；应用层愿意写就写（两者同形，不会打架）。
 */
export const TS_DEFAULT = sql`to_char(now() at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')`;

/**
 * 主键列：标准 64 位雪花 id。
 *
 * 标度钉 0 是有意的：裸 numeric 会保留运算产生的标度，哪天有人写 `id / 2`，
 * 存进去就成了 `1839203847563.0`，JS 侧读出来是个带小数点的字符串，id 当场废掉。
 * 钉死 0 之后任何算术结果都会落回整数。
 */
export function idCol() {
  return numeric("id", { precision: 19, scale: 0 }).primaryKey();
}

/**
 * 外键列：与主键同 typmod。
 * Postgres 要求外键两端的类型完全一致（typmod 也要一样），所以这一列只能由构造器产出——
 * 手写一次 `numeric("task_id")`（无 typmod）就会在建外键时被 Postgres 拒掉。
 */
export function fkCol(
  name: string,
  target: () => AnyPgColumn,
  options?: { onDelete?: "cascade" | "restrict" | "set null" | "no action" },
) {
  return numeric(name, { precision: 19, scale: 0 }).references(target, options);
}

/** 时间列：ISO-8601 UTC 文本。 */
export function tsCol(name: string) {
  return text(name);
}

/**
 * 审计四件套：谁建的、什么时候建的、谁最后改的、什么时候改的。
 *
 * 列名照参考项目（`create_by` / `create_time` / `update_by` / `update_time`）。
 * `create_by` / `update_by` **不加外键**：审计列要活得比人久——
 * 人被软删之后，审计行里那个 id 还得留着，否则「谁做的」永远查不到。
 * 加了外键就只能在「抹掉证据」与「人永远删不掉」之间二选一，两个都不好。
 * 参考项目的 `create_by bigint` 同样没有外键。
 *
 * `update_time` 由触发器维护（应用层不必记得改）；`update_by` 只能由应用层写——
 * 触发器不知道是谁改的。
 */
export function auditCols() {
  return {
    createBy: numeric("create_by", { precision: 19, scale: 0 }),
    createTime: tsCol("create_time").notNull().default(TS_DEFAULT),
    updateBy: numeric("update_by", { precision: 19, scale: 0 }),
    updateTime: tsCol("update_time").notNull().default(TS_DEFAULT),
  };
}

/**
 * 逻辑删除标记：只给业务实体表用（证据与流水表不加，删了就没法对账）。
 *
 * 形状照参考项目：`del_flag char(1)`，'0' 存在、'1' 已删，列本身带 NOT NULL DEFAULT '0'。
 * 用标志位而不是时间戳，是因为它要与参考项目一致（那边的 MyBatis-Plus 拦截器只认这一种），
 * 也因为「删了没有」这件事比「什么时候删的」更常用——后者要另开一列。
 */
export function softDeleteCols() {
  return { delFlag: char("del_flag").notNull().default("0") };
}

/**
 * 活着的行：软删表的「现在是什么」类读路径统一带上它。
 *
 * 只在「现在是什么」类查询上用——列表、详情、登录、按 id 查。
 * 「发生过什么」类查询（去重判据、额度统计、审计、发布记录）**不要**加：
 * 软删一条已发布的任务，不代表它没发布过——那些地方要连软删行一起看，
 * 否则同一条热点会被发第二遍。
 */
export function alive(delFlag: SQLWrapper) {
  return eq(delFlag, "0");
}
