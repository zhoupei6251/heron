import { snowflakeId } from "../id";
import { eq, sql } from "drizzle-orm";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import * as repo from "./index";
import type { Db } from "./index";

const input = {
  name: "Acme",
  slug: "acme",
  ownerEmail: " Owner@Acme.COM ",
  ownerDisplayName: "Owner",
  ownerPasswordHash: "sensitive-owner-hash",
};

describe("platform console repositories (real PGlite)", () => {
  let db: Db;
  beforeEach(async () => {
    db = repo.openDb("memory");
    await repo.migrate(db);
  });
  afterEach(async () => {
    await db.close();
  });
  async function operator() {
    expect(repo.createPlatformOperator).toBeTypeOf("function");
    return repo.createPlatformOperator(db, {
      username: " Ops.Admin ",
      displayName: "Ops",
      passwordHash: "sensitive-operator-hash",
    });
  }

  it("seeds a separate normalized operator, exposes hash only in auth lookup and revokes sessions", async () => {
    const op = await operator();
    expect(op).toMatchObject({ username: "ops.admin", displayName: "Ops" });
    expect(op).not.toHaveProperty("passwordHash");
    expect(await repo.findPlatformOperatorByUsername(db, " OPS.ADMIN ")).toMatchObject({
      id: op.id,
      passwordHash: "sensitive-operator-hash",
      sessionVersion: 0,
    });
    expect(await repo.revokePlatformSessions(db, op.id)).toBe(1);
    expect(await repo.findPlatformOperatorById(db, op.id)).toMatchObject({ sessionVersion: 1 });
    expect(await repo.findPlatformOperatorById(db, snowflakeId())).toBeNull();
    await expect(repo.revokePlatformSessions(db, snowflakeId())).rejects.toMatchObject({
      code: "forbidden",
    });
    await expect(
      repo.createPlatformOperator(db, {
        username: op.username.toUpperCase(),
        displayName: "Duplicate",
        passwordHash: "secret",
      }),
    ).rejects.toMatchObject({ code: "conflict" });
  });

  it("atomically provisions tenant + owner + safe audit and rolls back duplicates/invalid owner", async () => {
    const op = await operator();
    const tenant = await repo.provisionTenant(db, op.id, input);
    const members = await repo.withTenant(db, tenant.id, (t) => repo.listMembers(t));
    expect(members.items[0]).toMatchObject({ email: "owner@acme.com", role: "owner" });
    expect(members.total).toBe(1);
    await expect(repo.provisionTenant(db, op.id, input)).rejects.toMatchObject({
      code: "conflict",
    });
    await expect(
      repo.provisionTenant(db, op.id, {
        ...input,
        slug: "bad-owner",
        ownerPasswordHash: undefined as unknown as string,
      }),
    ).rejects.toThrow();
    expect((await repo.listPlatformTenants(db)).total).toBe(1);
    const audit = await repo.listPlatformAudit(db);
    expect(audit.total).toBe(1);
    expect(audit.items[0]).toMatchObject({
      operatorId: op.id,
      operatorName: "Ops",
      targetId: tenant.id,
    });
    expect(JSON.stringify(audit)).not.toMatch(
      /password|sensitive-owner-hash|sensitive-operator-hash/,
    );
  });

  it("requires an existing active platform operator for every mutation", async () => {
    const op = await operator();
    const tenant = await repo.provisionTenant(db, op.id, input);
    const userId = (await repo.withTenant(db, tenant.id, (t) => repo.listMembers(t))).items[0]!.id;
    await expect(
      repo.provisionTenant(db, userId, { ...input, slug: "forged" }),
    ).rejects.toMatchObject({ code: "forbidden" });
    await expect(repo.changeTenantStatus(db, userId, tenant.id, true)).rejects.toMatchObject({
      code: "forbidden",
    });
    await expect(repo.changeTenantStatus(db, op.id, snowflakeId(), true)).rejects.toMatchObject({
      code: "not_found",
    });
    expect((await repo.changeTenantStatus(db, op.id, tenant.id, true)).suspendedAt).not.toBeNull();
    expect((await repo.changeTenantStatus(db, op.id, tenant.id, false)).suspendedAt).toBeNull();
    await db
      .update(repo.platformOperators)
      .set({ disabledAt: "2026-10-22T00:00:00.000Z" })
      .where(eq(repo.platformOperators.id, op.id));
    await expect(repo.changeTenantStatus(db, op.id, tenant.id, true)).rejects.toMatchObject({
      code: "forbidden",
    });
    await expect(repo.revokePlatformSessions(db, op.id)).rejects.toMatchObject({
      code: "forbidden",
    });
  });

  it("pages joined tenant counts without join fanout and groups usage by tenant/kind/unit", async () => {
    const op = await operator();
    const tenant = await repo.provisionTenant(db, op.id, input);
    await repo.provisionTenant(db, op.id, { ...input, slug: "beta", name: "Beta" });
    await repo.withTenant(db, tenant.id, async (t) => {
      await repo.createUser(t, {
        email: "extra@acme.com",
        displayName: "Extra",
        passwordHash: "secret",
      });
      await repo.createTask(t, { capability: "news", candidateId: null });
      await repo.createTask(t, { capability: "news", candidateId: null });
      await repo.recordUsage(t, { kind: "render_seconds", unit: "seconds", quantity: "1.2" });
      await repo.recordUsage(t, { kind: "render_seconds", unit: "seconds", quantity: "0.1" });
    });
    expect((await repo.listPlatformTenants(db, { q: "acme" })).items[0]).toMatchObject({
      id: tenant.id,
      userCount: 2,
      taskCount: 2,
    });
    const page = await repo.listPlatformTenants(db, { page: 2, pageSize: 1 });
    expect(page).toMatchObject({ total: 2, page: 2, pageSize: 1 });
    expect(page.items).toHaveLength(1);
    expect(await repo.platformUsage(db)).toEqual([
      {
        tenantId: tenant.id,
        tenantName: "Acme",
        kind: "render_seconds",
        unit: "seconds",
        quantity: "1.3",
      },
    ]);
    expect((await repo.listPlatformAudit(db, { page: 2, pageSize: 1 })).items).toHaveLength(1);
    expect((await repo.listPlatformAudit(db, { q: "tenant.provisioned" })).total).toBe(2);
  });

  it("records both ends of a plan change and stays silent on a no-op", async () => {
    const op = await operator();
    const tenant = await repo.provisionTenant(db, op.id, { ...input, slug: "plan-co" });
    expect(tenant.plan).toBe("free");
    expect((await repo.listPlatformTenants(db)).items[0]).toMatchObject({ plan: "free" });
    expect(await repo.changeTenantPlan(db, op.id, tenant.id, "standard")).toMatchObject({
      plan: "standard",
    });
    // 两个值都记：只有一个新档位的话，事后对不出「原来是什么」
    expect((await repo.listPlatformAudit(db, { q: "tenant.plan_changed" })).items[0]).toMatchObject(
      {
        operatorId: op.id,
        targetId: tenant.id,
        detail: { from: "free", to: "standard" },
      },
    );
    // 同档位不改：审计里不该多出一条 free → free 的噪声
    await repo.changeTenantPlan(db, op.id, tenant.id, "standard");
    expect((await repo.listPlatformAudit(db, { q: "tenant.plan_changed" })).total).toBe(1);
    await expect(repo.changeTenantPlan(db, op.id, snowflakeId(), "free")).rejects.toMatchObject({
      code: "not_found",
    });
    // 租户成员的 id 不是平台操作员，不能给自己升档
    await expect(repo.changeTenantPlan(db, tenant.id, tenant.id, "free")).rejects.toMatchObject({
      code: "forbidden",
    });
  });

  it("denies all platform table access under withTenant, even after RLS reapplication", async () => {
    const op = await operator();
    const tenant = await repo.provisionTenant(db, op.id, input);
    // Simulate an inherited/default grant and a future identity sequence. Reapplying
    // RLS must remove both direct app privileges and privileges inherited from PUBLIC.
    await db.execute(
      sql`ALTER TABLE plat_operator ADD COLUMN test_sequence bigint GENERATED ALWAYS AS IDENTITY`,
    );
    await db.execute(sql`GRANT ALL ON plat_operator, plat_audit_log TO heron_app, PUBLIC`);
    await db.execute(
      sql`GRANT ALL ON SEQUENCE plat_operator_test_sequence_seq TO heron_app, PUBLIC`,
    );
    await repo.applyRls(db);
    await expect(
      repo.withTenant(db, tenant.id, (t) =>
        t.db.execute(sql`SELECT nextval('plat_operator_test_sequence_seq')`),
      ),
    ).rejects.toMatchObject({ cause: { code: "42501" } });
    for (const table of repo.TABLES_PLATFORM) {
      // UPDATE 必须指名一列才走得到权限判定（列不存在会先报 42703）；主键从目录里取，
      // 平台表不都是 id 一把 UUID 主键，platform_settings 折成了 key/value。
      const primaryKey = await db.execute(
        sql`SELECT a.attname FROM pg_index i
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
            WHERE i.indrelid = ${table}::regclass AND i.indisprimary`,
      );
      const pk = (primaryKey.rows[0] as { attname: string }).attname;
      for (const statement of [
        `SELECT * FROM ${table}`,
        `DELETE FROM ${table}`,
        `UPDATE ${table} SET ${pk} = ${pk}`,
        `INSERT INTO ${table} DEFAULT VALUES`,
      ]) {
        await expect(
          repo.withTenant(db, tenant.id, (t) => t.db.execute(sql.raw(statement))),
        ).rejects.toMatchObject({ cause: { code: "42501" } });
      }
      const rights = await db.execute(
        sql`SELECT has_table_privilege('heron_app', ${table}, 'TRUNCATE') AS truncate, has_table_privilege('heron_app', ${table}, 'REFERENCES') AS references, has_table_privilege('heron_app', ${table}, 'TRIGGER') AS trigger`,
      );
      expect(rights.rows[0]).toEqual({ truncate: false, references: false, trigger: false });
    }
    // Platform tables use UUIDs, not sequences; future identity additions must stay privileged.
    const sequences = await db.execute(
      sql`SELECT c.relname FROM pg_class c JOIN pg_depend d ON d.objid = c.oid JOIN pg_class t ON t.oid = d.refobjid WHERE c.relkind = 'S' AND t.relname IN ('plat_operator', 'plat_audit_log') AND CASE WHEN c.relkind = 'S' THEN has_sequence_privilege('heron_app', c.oid, 'USAGE,SELECT,UPDATE') ELSE false END`,
    );
    expect(sequences.rows).toEqual([]);
  });
});
