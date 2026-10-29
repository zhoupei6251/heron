import { sql } from "drizzle-orm";

import { OWNER_ROLE } from "./admin";
import type { Db } from "./driver";
import {
  TABLES_PLATFORM,
  TABLES_WITHOUT_TENANT,
  TABLES_TENANT_ROOT,
  TABLES_WITH_TENANT,
} from "./schema/index";

/** 会话级租户变量名。只有 withTenant 写它，policy 读它。 */
export const SET_TENANT_GUC = "app.tenant_id";

/** 业务角色：受 RLS 管，NOLOGIN。withTenant 每个事务 SET LOCAL ROLE 到它，见 admin.ts 的双通道表。 */
export const APP_ROLE = "heron_app";

/**
 * 子表 → 父表的租户继承关系。子表自己没有 tenant_id，策略必须顺着 FK 回到父表；
 * 这张映射与 TABLES_WITHOUT_TENANT 是同一件事的两半：漏一个键，applyRls 直接抛错，
 * 而不是建出一条谁都能读的策略。
 */
const TENANT_CHILD_PARENT: Record<
  (typeof TABLES_WITHOUT_TENANT)[number],
  { parent: string; fk: string }
> = {
  news_task_event: { parent: "news_task", fk: "task_id" },
  news_gate_result: { parent: "news_task", fk: "task_id" },
  news_render: { parent: "news_task", fk: "task_id" },
  news_script: { parent: "news_task", fk: "task_id" },
  proto_revision: { parent: "proto_project", fk: "project_id" },
};

/**
 * 从 GUC 取当前租户。写死字面量而不是拼 SET_TENANT_GUC：DDL 里不许有绑定参数。
 * 空串与未设同等对待——都是「没有租户上下文」，比较结果为 NULL，策略判假。
 */
const TENANT_FROM_GUC = sql`NULLIF(current_setting('app.tenant_id', true), '')::numeric`;

/**
 * 每张租户表三条语句：ENABLE + FORCE + policy。
 *
 * fail-closed 的实现处是 `NULLIF(current_setting('app.tenant_id', true), '')::numeric`：
 * GUC 没设 / 设成空串 → NULL → 比较结果是 NULL → policy 判假 → 一行都读不到，写入被 WITH CHECK 拒。
 * 那个 NULLIF 不是装饰，是实测补上的：自定义 GUC 只要被 set_config 定义过一次，
 * 事务结束（is_local=true 回退）后它的值就变成**空字符串而不是 NULL**，
 * 直接 `''::uuid` 会让策略求值抛 2202（invalid input syntax）——连接池复用下等于每次查询都炸。
 * 将来谁把 NULLIF 去掉、或改成 COALESCE 兜一个默认租户，前者变成报错、后者变成把隔离整个打开。
 *
 * 名字用 sql.identifier、GUC 用字面量：CREATE POLICY 是 DDL，Postgres 不许在 DDL 里出现 $1 绑定参数
 * （T2 生成迁移时踩过一次，产出的 CHECK 带着 $1,$2 根本跑不起来）。
 *
 * 两个角色在这里建、在这里授权：「谁能碰这些表」与「策略长什么样」是同一个决定的两半，
 * 分两个文件写迟早漏一半——漏掉 GRANT 的症状是特权通道 permission denied。
 */
export async function applyRls(db: Db): Promise<void> {
  await db.execute(
    sql`DO $do$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'heron_app') THEN
            CREATE ROLE heron_app NOLOGIN;
          END IF;
        END $do$`,
  );
  // 特权角色：BYPASSRLS 让跨租户调度与账单汇总看得见全部行（见 admin.ts 的双通道表）
  await db.execute(
    sql`DO $do$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'heron_owner') THEN
            CREATE ROLE heron_owner NOLOGIN BYPASSRLS;
          END IF;
        END $do$`,
  );
  for (const role of [APP_ROLE, OWNER_ROLE]) {
    await db.execute(sql`GRANT USAGE ON SCHEMA public TO ${sql.identifier(role)}`);
  }
  await db.execute(
    sql`GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO heron_owner`,
  );
  await db.execute(sql`GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO heron_owner`);
  // Remove historical blanket grants before granting only the tenant realm. This also
  // denies future platform identity sequences; platform tables themselves use UUIDs.
  await db.execute(sql`REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM heron_app`);
  for (const table of TABLES_PLATFORM) {
    await db.execute(sql`REVOKE ALL ON TABLE ${sql.identifier(table)} FROM heron_app, PUBLIC`);
  }
  for (const table of [...TABLES_WITH_TENANT, ...TABLES_TENANT_ROOT, ...TABLES_WITHOUT_TENANT]) {
    await db.execute(
      sql`GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE ${sql.identifier(table)} TO heron_app`,
    );
  }
  await db.execute(sql`REVOKE ALL ON sys_audit_log, plat_audit_log FROM heron_owner, PUBLIC`);
  await db.execute(sql`GRANT SELECT, INSERT ON sys_audit_log, plat_audit_log TO heron_owner`);
  await db.execute(sql`REVOKE ALL ON sys_audit_log FROM heron_app`);
  await db.execute(sql`GRANT SELECT, INSERT ON sys_audit_log TO heron_app`);
  // FK actions and TRUNCATE CASCADE must not erase history or attribution indirectly.
  // Runtime identities are soft-disabled; replay this after all blanket/historical grants.
  await db.execute(
    sql`REVOKE DELETE, TRUNCATE ON sys_user, sys_tenant, plat_operator FROM heron_app, heron_owner, PUBLIC`,
  );
  // Grant only sequences owned by a tenant table, never by a platform table.
  await db.execute(sql`DO $do$ DECLARE s record; BEGIN
    FOR s IN SELECT ns.nspname, c.relname FROM pg_class c
      JOIN pg_namespace ns ON ns.oid = c.relnamespace
      JOIN pg_depend d ON d.objid = c.oid AND d.deptype IN ('a', 'i')
      JOIN pg_class t ON t.oid = d.refobjid
      WHERE c.relkind = 'S' AND ns.nspname = 'public'
        AND t.relname IN (${sql.join(
          [...TABLES_WITH_TENANT, ...TABLES_WITHOUT_TENANT].map((t) => sql.raw(`'${t}'`)),
          sql`, `,
        )})
    LOOP EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %I.%I TO heron_app', s.nspname, s.relname); END LOOP;
    FOR s IN SELECT ns.nspname, c.relname FROM pg_class c
      JOIN pg_namespace ns ON ns.oid = c.relnamespace
      JOIN pg_depend d ON d.objid = c.oid AND d.deptype IN ('a', 'i')
      JOIN pg_class t ON t.oid = d.refobjid
      WHERE c.relkind = 'S' AND ns.nspname = 'public'
        AND t.relname IN (${sql.join(
          TABLES_PLATFORM.map((t) => sql.raw(`'${t}'`)),
          sql`, `,
        )})
    LOOP EXECUTE format('REVOKE ALL ON SEQUENCE %I.%I FROM heron_app, PUBLIC', s.nspname, s.relname); END LOOP;
  END $do$`);
  // 登录角色要能 SET LOCAL ROLE 到 heron_owner。超级用户本来就能，这条是为「登录角色非超管」的收紧部署准备的
  await db.execute(sql`GRANT ${sql.identifier(OWNER_ROLE)} TO CURRENT_USER`);

  for (const table of [...TABLES_WITH_TENANT, ...TABLES_TENANT_ROOT]) {
    const name = sql.identifier(table);
    // tenants 自己按 id 隔离：否则 B 能枚举出「存在一个叫 A 的租户」这件事本身
    const ownerCol = sql.identifier(table === "sys_tenant" ? "id" : "tenant_id");
    await db.execute(sql`ALTER TABLE ${name} ENABLE ROW LEVEL SECURITY`);
    // FORCE 连表 owner 也不放过；真库里迁移角色与连接角色分开，靠这条兜住漏网
    await db.execute(sql`ALTER TABLE ${name} FORCE ROW LEVEL SECURITY`);
    await db.execute(sql`DROP POLICY IF EXISTS tenant_isolation ON ${name}`);
    await db.execute(
      sql`CREATE POLICY tenant_isolation ON ${name}
          USING (${ownerCol} = ${TENANT_FROM_GUC})
          WITH CHECK (${ownerCol} = ${TENANT_FROM_GUC})`,
    );
  }

  // 子表（task_events / gate_results / renders / scripts / proto_revisions）不带 tenant_id，
  // 租户由父表的 FK 继承，所以策略也得顺着 FK 写。少了这一段，「按父 id 查子表」就是跨租户读别人的行——
  // 而仓储层里每一条这样的查询都指望这条策略，它不是可选的第二道防线。
  for (const table of TABLES_WITHOUT_TENANT) {
    const mapping = TENANT_CHILD_PARENT[table];
    if (!mapping) throw new Error(`子表 ${table} 没有声明父表，无法生成租户隔离策略`);
    const name = sql.identifier(table);
    const parent = sql.identifier(mapping.parent);
    const fk = sql`${name}.${sql.identifier(mapping.fk)}`;
    await db.execute(sql`ALTER TABLE ${name} ENABLE ROW LEVEL SECURITY`);
    await db.execute(sql`ALTER TABLE ${name} FORCE ROW LEVEL SECURITY`);
    await db.execute(sql`DROP POLICY IF EXISTS tenant_isolation ON ${name}`);
    await db.execute(
      sql`CREATE POLICY tenant_isolation ON ${name}
          USING (EXISTS (SELECT 1 FROM ${parent} WHERE ${parent}.id = ${fk}
                         AND ${parent}.tenant_id = ${TENANT_FROM_GUC}))
          WITH CHECK (EXISTS (SELECT 1 FROM ${parent} WHERE ${parent}.id = ${fk}
                              AND ${parent}.tenant_id = ${TENANT_FROM_GUC}))`,
    );
  }
}

/**
 * 仓储函数的第一个参数：一个已经站定租户、并且**已经在一个事务里**的连接。
 * 仓储只发语句、不自开事务——原子性归调用方的 withTenant。
 * 由此得记一条：闸拒绝时 advanceTask 抛错，若调用方不在回调里 catch，整个事务回滚，
 * gate_results 与 stage=failed 那点痕就留不下（「为什么被拒」是审计要求，不是日志）。
 */
export interface TenantDb {
  readonly db: Db;
  readonly tenantId: string;
}

/**
 * 把一个已经是 heron_app + 已设 GUC 的连接包成 TenantDb（调用方管着事务时用）。
 * 它**不降权、不设 GUC**——拿裸连接传进来就是「以为隔离生效了其实没有」，
 * 所以新建上下文一律走 withTenant，这里只留给确实已经站好的场合。
 */
export function forTenant(db: Db, tenantId: string): TenantDb {
  return { db, tenantId };
}

/**
 * 租户上下文只有一个入口：一个事务，开头降权到 heron_app、再把 GUC 设上。
 * 两个都是 LOCAL 语义——提交即失效，连接还回池子时既不带角色也不带租户，下一个请求不会捡到上一个的。
 *
 * 这条 `SET LOCAL ROLE` 才是 fail-closed 在生产上的落地点：进程连进来的登录角色是超级用户/表 owner，
 * **超级用户无视 RLS（FORCE 也不管他）**。只设 GUC 不降权，策略等于没写——
 * T3 的测试是靠 beforeAll 里一次 session 级 SET ROLE 才让隔离真的生效的。
 *
 * 仓储里仍要显式写 `tenant_id = ${tenantId}`（走索引、可读），RLS 是第二道防线——
 * 漏写 WHERE 不再等于跨租户泄漏，但两道防线缺一道都不算做完。
 */
export async function withTenant<T>(
  db: Db,
  tenantId: string,
  fn: (t: TenantDb) => Promise<T>,
): Promise<T> {
  return db.transaction(async (tx) => {
    // 角色名是常量，写成字面量：SET ROLE 后面接不了绑定参数
    await tx.execute(sql`SET LOCAL ROLE heron_app`);
    await tx.execute(sql`SELECT set_config('app.tenant_id', ${tenantId}, true)`);
    return fn(forTenant(tx as unknown as Db, tenantId));
  });
}
