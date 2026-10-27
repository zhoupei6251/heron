import { randomUUID } from "node:crypto";
import { sql } from "drizzle-orm";
import { afterEach, describe, expect, it } from "vitest";
import { withAdmin } from "./admin";
import {
  applyRls,
  createTenant,
  createUser,
  createPlatformOperator,
  migrate,
  openDb,
  withTenant,
  type Db,
} from "./index";

let db: Db | undefined;
afterEach(async () => {
  await db?.close();
  db = undefined;
});

describe("append-only audit privileges", () => {
  async function seedHistory() {
    db = openDb("memory");
    await migrate(db);
    const tenant = await createTenant(db, { name: "History", slug: "history" });
    const actor = await withTenant(db, tenant, (t) =>
      createUser(t, {
        email: "actor@example.com",
        displayName: "Actor",
        passwordHash: "unusable:test",
      }),
    );
    const operator = await createPlatformOperator(db, {
      username: "operator",
      displayName: "Operator",
      passwordHash: "unusable:test",
    });
    await withAdmin(db, async (a) => {
      await a.db.execute(
        sql`INSERT INTO audit_logs (id, tenant_id, actor_id, action, created_at) VALUES (${randomUUID()}, ${tenant}, ${actor}, 'original', '2026-10-23')`,
      );
      await a.db.execute(
        sql`INSERT INTO platform_audit_logs (id, operator_id, action, created_at) VALUES (${randomUUID()}, ${operator.id}, 'original', '2026-10-23')`,
      );
    });
    return { tenant, actor, operator: operator.id };
  }

  async function expectHistory(ids: { tenant: string; actor: string; operator: string }) {
    expect(
      (await db!.execute(sql`SELECT tenant_id, actor_id, action FROM audit_logs`)).rows,
    ).toEqual([{ tenant_id: ids.tenant, actor_id: ids.actor, action: "original" }]);
    expect(
      (await db!.execute(sql`SELECT operator_id, action FROM platform_audit_logs`)).rows,
    ).toEqual([{ operator_id: ids.operator, action: "original" }]);
  }

  it("revokes indirect parent DELETE/TRUNCATE after historical grants and repeated applyRls", async () => {
    const ids = await seedHistory();
    await db!.execute(
      sql`GRANT ALL ON users, tenants, platform_operators TO heron_app, heron_owner, PUBLIC`,
    );
    await applyRls(db!);
    await applyRls(db!);
    for (const role of ["heron_app", "heron_owner"]) {
      for (const table of ["users", "tenants", "platform_operators"]) {
        const privileges = await db!.execute(
          sql`SELECT has_table_privilege(${role}, ${table}, 'DELETE') AS del, has_table_privilege(${role}, ${table}, 'TRUNCATE') AS trunc`,
        );
        expect(privileges.rows).toEqual([{ del: false, trunc: false }]);
        for (const statement of [`DELETE FROM ${table}`, `TRUNCATE ${table} CASCADE`]) {
          const execute = (context: { db: Db }) => context.db.execute(sql.raw(statement));
          await expect(
            role === "heron_app" ? withTenant(db!, ids.tenant, execute) : withAdmin(db!, execute),
          ).rejects.toMatchObject({ cause: { code: "42501" } });
          await expectHistory(ids);
        }
      }
    }
  });

  it.each(["users", "tenants", "platform_operators"])(
    "restricts privileged %s deletion rather than cascading or clearing attribution",
    async (table) => {
      const ids = await seedHistory();
      await applyRls(db!);
      await applyRls(db!);
      await expect(db!.execute(sql`DELETE FROM ${sql.identifier(table)}`)).rejects.toMatchObject({
        cause: { code: "23001" },
      });
      await expectHistory(ids);
    },
  );

  it("allows tenant audit insertion and reading, but denies alteration after repeated grants", async () => {
    db = openDb("memory");
    await migrate(db);
    const tenant = await createTenant(db, { name: "Audit", slug: "audit" });
    await db.execute(sql`GRANT ALL ON audit_logs TO heron_app, PUBLIC`);
    await applyRls(db);
    await applyRls(db);
    const id = randomUUID();
    await withTenant(db, tenant, (t) =>
      t.db.execute(
        sql`INSERT INTO audit_logs (id, tenant_id, action, created_at) VALUES (${id}, ${tenant}, 'original', '2026-10-23T00:00:00.000Z')`,
      ),
    );
    for (const statement of [
      sql`UPDATE audit_logs SET action = 'tampered'`,
      sql`DELETE FROM audit_logs`,
      sql`TRUNCATE audit_logs`,
    ]) {
      await expect(withTenant(db, tenant, (t) => t.db.execute(statement))).rejects.toMatchObject({
        cause: { code: "42501" },
      });
    }
    const rows = await withTenant(db, tenant, (t) =>
      t.db.execute(sql`SELECT action FROM audit_logs`),
    );
    expect(rows.rows).toEqual([{ action: "original" }]);
  });
  it("keeps platform runtime audit access append-only, including tenant history", async () => {
    db = openDb("memory");
    await migrate(db);
    await db.execute(sql`GRANT ALL ON audit_logs, platform_audit_logs TO heron_owner, PUBLIC`);
    await applyRls(db);
    await applyRls(db);
    const id = randomUUID();
    await withAdmin(db, (a) =>
      a.db.execute(
        sql`INSERT INTO platform_audit_logs (id, action, created_at) VALUES (${id}, 'original', '2026-10-23T00:00:00.000Z')`,
      ),
    );
    for (const table of ["audit_logs", "platform_audit_logs"]) {
      for (const statement of [
        `UPDATE ${table} SET action = 'tampered'`,
        `DELETE FROM ${table}`,
        `TRUNCATE ${table}`,
      ]) {
        await expect(withAdmin(db, (a) => a.db.execute(sql.raw(statement)))).rejects.toMatchObject({
          cause: { code: "42501" },
        });
      }
    }
    const rows = await withAdmin(db, (a) =>
      a.db.execute(sql`SELECT action FROM platform_audit_logs`),
    );
    expect(rows.rows).toEqual([{ action: "original" }]);
  });
});
