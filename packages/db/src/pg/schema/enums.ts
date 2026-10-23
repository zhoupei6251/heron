import { sql, type SQLWrapper } from "drizzle-orm";

/**
 * 枚举 CHECK 一律从 contracts 的那份常量生成：状态机、闸名单、台账状态只有一份，
 * 改 contracts 这里跟着红，不许存储层与业务层各写一遍字面量。
 *
 * 值必须拼成字面量而不是绑定参数——CHECK 进的是 CREATE TABLE，Postgres 的 DDL 里不许有 $1
 * （T2 生成迁移时踩过一次：产出的 CHECK 带着 $1,$2，迁移根本跑不起来）。
 * 取值全部来自仓库常量而非用户输入，单引号加倍转义只是把这条路也堵死。
 */
export function inEnum(column: SQLWrapper, values: readonly string[]) {
  return sql`${column} IN (${sql.join(values.map(lit), sql`,`)})`;
}

/** SQL 字符串字面量：值全部来自仓库常量，单引号加倍只是把注入这条路也堵死。 */
export function lit(value: string) {
  return sql.raw(`'${value.replaceAll("'", "''")}'`);
}
