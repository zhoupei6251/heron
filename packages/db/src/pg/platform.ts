import { randomUUID } from "node:crypto";
import {
  CreateTenantBodySchema,
  PageQuerySchema,
  PlatformLoginBodySchema,
  ProfileBodySchema,
  type CreateTenantBody,
  type Page,
  type PageQuery,
  type PlatformAuditLog,
  type PlatformTenant,
  type PlatformUsageSummary,
  type PlatformUser,
  type Tenant,
} from "@heron/contracts";
import { and, asc, count, desc, eq, ilike, isNull, or, sql } from "drizzle-orm";
import { nowIso } from "../time";
import { withAdmin, type AdminDb } from "./admin";
import { ManagementError, readPage, rethrowConflict, searchPattern } from "./console-query";
import type { Db } from "./driver";
import {
  platformAuditLogs,
  platformOperators,
  tasks,
  tenants,
  usageRecords,
  users,
} from "./schema/index";

/** Auth-service only: never return this shape from an HTTP endpoint. */
export interface PgPlatformOperator extends PlatformUser {
  passwordHash: string;
  sessionVersion: number;
  disabledAt: string | null;
  createdAt: string;
}
export interface NewPlatformOperator {
  email: string;
  displayName: string;
  passwordHash: string;
}
export type ProvisionTenantInput = Omit<CreateTenantBody, "ownerPassword"> & {
  ownerPasswordHash: string;
};

export async function findPlatformOperatorByEmail(
  db: Db,
  email: string,
): Promise<PgPlatformOperator | null> {
  return withAdmin(db, async (a) => {
    const [row] = await a.db
      .select()
      .from(platformOperators)
      .where(eq(platformOperators.email, email.trim().toLowerCase()))
      .limit(1);
    return row ?? null;
  });
}
export async function findPlatformOperatorById(
  db: Db,
  id: string,
): Promise<PgPlatformOperator | null> {
  return withAdmin(db, async (a) => {
    const [row] = await a.db
      .select()
      .from(platformOperators)
      .where(eq(platformOperators.id, id))
      .limit(1);
    return row ?? null;
  });
}

/** Explicit bootstrap entry point, not a tenant operation. No default identity or secret. */
export async function createPlatformOperator(
  db: Db,
  input: NewPlatformOperator,
): Promise<PlatformUser> {
  const { email } = PlatformLoginBodySchema.omit({ password: true }).parse(input);
  const { displayName } = ProfileBodySchema.parse(input);
  try {
    return await withAdmin(db, async (a) => {
      const id = randomUUID();
      await a.db
        .insert(platformOperators)
        .values({ id, email, displayName, passwordHash: input.passwordHash, createdAt: nowIso() });
      return { id, email, displayName };
    });
  } catch (error) {
    rethrowConflict(error);
  }
}

/** Hold the operator row through the mutation so a concurrent disable cannot pass a stale check. */
async function requireOperator(a: AdminDb, operatorId: string): Promise<void> {
  const [operator] = await a.db
    .select({ id: platformOperators.id })
    .from(platformOperators)
    .where(and(eq(platformOperators.id, operatorId), isNull(platformOperators.disabledAt)))
    .for("update");
  if (!operator) throw new ManagementError("forbidden", "An active platform operator is required");
}

async function audit(
  a: AdminDb,
  operatorId: string,
  action: string,
  targetId: string,
  detail: Record<string, unknown> = {},
): Promise<void> {
  await a.db
    .insert(platformAuditLogs)
    .values({ id: randomUUID(), operatorId, action, targetId, detail, createdAt: nowIso() });
}

export async function revokePlatformSessions(db: Db, operatorId: string): Promise<number> {
  return withAdmin(db, async (a) => {
    await requireOperator(a, operatorId);
    const [row] = await a.db
      .update(platformOperators)
      .set({ sessionVersion: sql`${platformOperators.sessionVersion} + 1` })
      .where(eq(platformOperators.id, operatorId))
      .returning({ version: platformOperators.sessionVersion });
    if (!row) throw new ManagementError("not_found", "Platform operator not found");
    await audit(a, operatorId, "sessions.revoked", operatorId);
    return row.version;
  });
}

export async function listPlatformTenants(
  db: Db,
  input: Partial<PageQuery> = {},
): Promise<Page<PlatformTenant>> {
  const q = PageQuerySchema.parse(input);
  return withAdmin(db, (a) => {
    // Preaggregate each relation independently: a users x tasks join inflates counts.
    const members = a.db
      .select({ tenantId: users.tenantId, total: count().as("user_count") })
      .from(users)
      .groupBy(users.tenantId)
      .as("members");
    const jobs = a.db
      .select({ tenantId: tasks.tenantId, total: count().as("task_count") })
      .from(tasks)
      .groupBy(tasks.tenantId)
      .as("jobs");
    const pattern = searchPattern(q.q);
    const where = or(ilike(tenants.name, pattern), ilike(tenants.slug, pattern));
    return readPage(
      q,
      a.db
        .select({
          id: tenants.id,
          name: tenants.name,
          slug: tenants.slug,
          suspendedAt: tenants.suspendedAt,
          createdAt: tenants.createdAt,
          userCount: sql<number>`coalesce(${members.total}, 0)::int`,
          taskCount: sql<number>`coalesce(${jobs.total}, 0)::int`,
        })
        .from(tenants)
        .leftJoin(members, eq(members.tenantId, tenants.id))
        .leftJoin(jobs, eq(jobs.tenantId, tenants.id))
        .where(where)
        .orderBy(desc(tenants.createdAt), asc(tenants.id))
        .limit(q.pageSize)
        .offset((q.page - 1) * q.pageSize),
      a.db.select({ total: count() }).from(tenants).where(where),
    );
  });
}

export async function provisionTenant(
  db: Db,
  operatorId: string,
  input: ProvisionTenantInput,
): Promise<Tenant> {
  try {
    return await withAdmin(db, async (a) => {
      await requireOperator(a, operatorId);
      const safe = CreateTenantBodySchema.omit({ ownerPassword: true }).parse(input);
      const at = nowIso();
      const tenant: Tenant = {
        id: randomUUID(),
        name: safe.name,
        slug: safe.slug,
        suspendedAt: null,
        createdAt: at,
      };
      await a.db.insert(tenants).values(tenant);
      const ownerId = randomUUID();
      await a.db.insert(users).values({
        id: ownerId,
        tenantId: tenant.id,
        email: safe.ownerEmail,
        displayName: safe.ownerDisplayName,
        passwordHash: input.ownerPasswordHash,
        role: "owner",
        createdAt: at,
        updatedAt: at,
      });
      await audit(a, operatorId, "tenant.provisioned", tenant.id, {
        name: tenant.name,
        slug: tenant.slug,
        ownerId,
      });
      return tenant;
    });
  } catch (error) {
    rethrowConflict(error);
  }
}

export async function changeTenantStatus(
  db: Db,
  operatorId: string,
  tenantId: string,
  suspended: boolean,
): Promise<Tenant> {
  return withAdmin(db, async (a) => {
    await requireOperator(a, operatorId);
    const [tenant] = await a.db
      .update(tenants)
      .set({ suspendedAt: suspended ? nowIso() : null })
      .where(eq(tenants.id, tenantId))
      .returning();
    if (!tenant) throw new ManagementError("not_found", "Workspace not found");
    // Suspension revokes existing tickets permanently; resuming must not revive them.
    if (suspended) {
      await a.db
        .update(users)
        .set({ sessionVersion: sql`${users.sessionVersion} + 1`, updatedAt: nowIso() })
        .where(eq(users.tenantId, tenantId));
    }
    await audit(a, operatorId, "tenant.status_changed", tenantId, { suspended });
    return tenant;
  });
}

export async function platformUsage(db: Db): Promise<PlatformUsageSummary[]> {
  return withAdmin(db, (a) =>
    a.db
      .select({
        tenantId: tenants.id,
        tenantName: tenants.name,
        kind: usageRecords.kind,
        unit: usageRecords.unit,
        quantity: sql<string>`sum(${usageRecords.quantity})::text`,
      })
      .from(usageRecords)
      .innerJoin(tenants, eq(tenants.id, usageRecords.tenantId))
      .groupBy(tenants.id, tenants.name, usageRecords.kind, usageRecords.unit)
      .orderBy(asc(tenants.id), asc(usageRecords.kind), asc(usageRecords.unit)),
  );
}

export async function listPlatformAudit(
  db: Db,
  input: Partial<PageQuery> = {},
): Promise<Page<PlatformAuditLog>> {
  const q = PageQuerySchema.parse(input);
  return withAdmin(db, (a) => {
    const where = ilike(platformAuditLogs.action, searchPattern(q.q));
    return readPage(
      q,
      a.db
        .select({
          id: platformAuditLogs.id,
          operatorId: platformAuditLogs.operatorId,
          operatorName: platformOperators.displayName,
          action: platformAuditLogs.action,
          targetId: platformAuditLogs.targetId,
          detail: platformAuditLogs.detail,
          createdAt: platformAuditLogs.createdAt,
        })
        .from(platformAuditLogs)
        .leftJoin(platformOperators, eq(platformOperators.id, platformAuditLogs.operatorId))
        .where(where)
        .orderBy(desc(platformAuditLogs.createdAt), asc(platformAuditLogs.id))
        .limit(q.pageSize)
        .offset((q.page - 1) * q.pageSize),
      a.db.select({ total: count() }).from(platformAuditLogs).where(where),
    );
  });
}
