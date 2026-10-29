import { randomUUID } from "node:crypto";
import {
  CreateMemberBodySchema,
  FeedSourceBodySchema,
  ModelSettingsBodySchema,
  PageQuerySchema,
  ProfileBodySchema,
  RetentionBodySchema,
  UpdateMemberBodySchema,
  WorkspaceBodySchema,
  type WorkspaceOverview,
  type ArtifactKind,
  type Asset,
  type AuditLog,
  type Candidate,
  type CreateMemberBody,
  type FeedSource,
  type FeedSourceBody,
  type Member,
  type ModelSettingsBody,
  type Page,
  type PageQuery,
  type ProfileBody,
  type PublishSummary,
  type TaskSummary,
  type Tenant,
  type UpdateMemberBody,
  type UsageSummary,
  type WorkspaceBody,
} from "@heron/contracts";
import { and, asc, count, desc, eq, ilike, isNull, or, sql } from "drizzle-orm";
import { can, exceeds, excessMessage, type Action } from "@heron/domain";
import { nowIso } from "../time";
import { ManagementError, readPage, rethrowConflict, searchPattern } from "./console-query";
import { monthlyUsage, tenantPlan } from "./quota";
import type { TenantDb } from "./rls";
import { setRetentionDays, setModelSettings } from "./settings";
import {
  auditLogs,
  candidates,
  feedSources,
  publishes,
  renders,
  sessions,
  tasks,
  tenants,
  usageRecords,
  users,
} from "./schema/index";

export { ManagementError } from "./console-query";
export type NewManagedMember = Omit<CreateMemberBody, "password"> & { passwordHash: string };

const memberColumns = {
  id: users.id,
  email: users.email,
  displayName: users.displayName,
  role: users.role,
  disabledAt: users.disabledAt,
  createdAt: users.createdAt,
};
const feedColumns = {
  id: feedSources.id,
  name: feedSources.name,
  url: feedSources.url,
  frequencySec: feedSources.frequencySec,
  enabled: feedSources.enabled,
  lastFetchAt: feedSources.lastFetchAt,
  lastAttemptAt: feedSources.lastAttemptAt,
  lastError: feedSources.lastError,
  createdAt: feedSources.createdAt,
};

export async function getMember(tdb: TenantDb, id: string): Promise<Member> {
  const [member] = await tdb.db
    .select(memberColumns)
    .from(users)
    .where(and(eq(users.tenantId, tdb.tenantId), eq(users.id, id)))
    .limit(1);
  if (!member) throw new ManagementError("not_found", "Member not found in this workspace");
  return member;
}

export async function listMembers(
  tdb: TenantDb,
  input: Partial<PageQuery> = {},
): Promise<Page<Member>> {
  const q = PageQuerySchema.parse(input);
  const pattern = searchPattern(q.q);
  const where = and(
    eq(users.tenantId, tdb.tenantId),
    or(ilike(users.email, pattern), ilike(users.displayName, pattern)),
  );
  return readPage(
    q,
    tdb.db
      .select(memberColumns)
      .from(users)
      .where(where)
      .orderBy(desc(users.createdAt), asc(users.id))
      .limit(q.pageSize)
      .offset((q.page - 1) * q.pageSize),
    tdb.db.select({ total: count() }).from(users).where(where),
  );
}

/** All identity mutations take this lock BEFORE reading actor/target. Under READ COMMITTED
 * concurrent owner mutations then see the committed winner, not a stale role/session. */
async function lockWorkspace(tdb: TenantDb): Promise<void> {
  const [tenant] = await tdb.db
    .select({ id: tenants.id, suspendedAt: tenants.suspendedAt })
    .from(tenants)
    .where(eq(tenants.id, tdb.tenantId))
    .for("update");
  if (!tenant) throw new ManagementError("not_found", "Workspace not found");
  if (tenant.suspendedAt !== null) throw new ManagementError("forbidden", "Workspace is suspended");
}

/** Recheck the actor AFTER the workspace lock, within the same transaction as the write. */
export async function requireActiveActor(
  tdb: TenantDb,
  actorId: string,
  action?: Action,
): Promise<Member> {
  await lockWorkspace(tdb);
  const [actor] = await tdb.db
    .select(memberColumns)
    .from(users)
    .where(and(eq(users.tenantId, tdb.tenantId), eq(users.id, actorId), isNull(users.disabledAt)));
  if (!actor || (action !== undefined && !can(actor.role, action))) {
    throw new ManagementError(
      "forbidden",
      "An active workspace member with the required role is required",
    );
  }
  return actor;
}

/** Callers pass allowlisted details, never request bodies, credentials, or hashes. */
async function audit(
  tdb: TenantDb,
  actorId: string,
  action: string,
  targetId: string,
  detail: Record<string, unknown> = {},
): Promise<void> {
  await tdb.db.insert(auditLogs).values({
    id: randomUUID(),
    tenantId: tdb.tenantId,
    actorId,
    action,
    targetId,
    detail,
    createdAt: nowIso(),
  });
}

export async function createManagedMember(
  tdb: TenantDb,
  actorId: string,
  input: NewManagedMember,
): Promise<Member> {
  await requireActiveActor(tdb, actorId, "user.manage");
  const safe = CreateMemberBodySchema.omit({ password: true }).parse(input);
  // 座位额度在写库之前判：先插进去再回滚也能「拦住」，但错误就成了数据库的而不是额度的。
  // 判据只读 seats 那一格，渲染/发布传 0 表示这次不问它们（不然加个人会被渲染数拖住）。
  const plan = await tenantPlan(tdb);
  const seatExcess = exceeds(plan, {
    seats: (await monthlyUsage(tdb)).seats,
    renders: 0,
    publishes: 0,
  });
  if (seatExcess) throw new ManagementError("conflict", excessMessage(plan, seatExcess));
  const at = nowIso();
  const id = randomUUID();
  try {
    await tdb.db.insert(users).values({
      ...safe,
      id,
      tenantId: tdb.tenantId,
      passwordHash: input.passwordHash,
      createdAt: at,
      updatedAt: at,
    });
  } catch (error) {
    rethrowConflict(error);
  }
  await audit(tdb, actorId, "member.created", id, {
    email: safe.email,
    displayName: safe.displayName,
    role: safe.role,
  });
  return getMember(tdb, id);
}

export async function updateMember(
  tdb: TenantDb,
  actorId: string,
  targetId: string,
  input: UpdateMemberBody,
): Promise<Member> {
  await requireActiveActor(tdb, actorId, "user.manage");
  const patch = UpdateMemberBodySchema.parse(input);
  const target = await getMember(tdb, targetId);
  const removesOwner =
    patch.disabled === true || (patch.role !== undefined && patch.role !== "owner");
  if (actorId === targetId && removesOwner) {
    throw new ManagementError("forbidden", "Owners cannot disable or demote themselves");
  }
  if (target.role === "owner" && target.disabledAt === null && removesOwner) {
    const [owners] = await tdb.db
      .select({ total: count() })
      .from(users)
      .where(
        and(eq(users.tenantId, tdb.tenantId), eq(users.role, "owner"), isNull(users.disabledAt)),
      );
    if ((owners?.total ?? 0) <= 1)
      throw new ManagementError("conflict", "The last active owner must remain");
  }
  const at = nowIso();
  await tdb.db
    .update(users)
    .set({
      displayName: patch.displayName,
      role: patch.role,
      disabledAt: patch.disabled === undefined ? undefined : patch.disabled ? at : null,
      // Re-enabling must not revive tokens issued before disable; role changes revoke too.
      sessionVersion:
        patch.disabled !== undefined || patch.role !== undefined
          ? sql`${users.sessionVersion} + 1`
          : undefined,
      updatedAt: at,
    })
    .where(and(eq(users.tenantId, tdb.tenantId), eq(users.id, targetId)));
  if (patch.disabled !== undefined || patch.role !== undefined) {
    await markSessionsRevoked(tdb, targetId);
  }
  await audit(tdb, actorId, "member.updated", targetId, { ...patch });
  return getMember(tdb, targetId);
}

export async function getWorkspace(tdb: TenantDb): Promise<Tenant> {
  const [tenant] = await tdb.db.select().from(tenants).where(eq(tenants.id, tdb.tenantId));
  if (!tenant) throw new ManagementError("not_found", "Workspace not found");
  return tenant;
}

export async function updateWorkspace(
  tdb: TenantDb,
  actorId: string,
  input: WorkspaceBody,
): Promise<Tenant> {
  await requireActiveActor(tdb, actorId, "settings.write");
  const { name } = WorkspaceBodySchema.parse(input);
  await tdb.db.update(tenants).set({ name }).where(eq(tenants.id, tdb.tenantId));
  await audit(tdb, actorId, "workspace.updated", tdb.tenantId, { name });
  return getWorkspace(tdb);
}

export async function updateProfile(
  tdb: TenantDb,
  actorId: string,
  input: ProfileBody,
): Promise<Member> {
  await requireActiveActor(tdb, actorId);
  const { displayName } = ProfileBodySchema.parse(input);
  await tdb.db
    .update(users)
    .set({ displayName, updatedAt: nowIso() })
    .where(and(eq(users.id, actorId), eq(users.tenantId, tdb.tenantId)));
  await audit(tdb, actorId, "profile.updated", actorId, { displayName });
  return getMember(tdb, actorId);
}

/**
 * 整户撤销（改密码、一键踢全部）时把会话行一起标掉：token 那边靠 session_version 失效，
 * 但列表读的是这张表——不标，人已经被踢下线，页面还写着「在线」。
 */
async function markSessionsRevoked(tdb: TenantDb, userId: string): Promise<void> {
  await tdb.db
    .update(sessions)
    .set({ revokedAt: nowIso() })
    .where(
      and(
        eq(sessions.tenantId, tdb.tenantId),
        eq(sessions.userId, userId),
        isNull(sessions.revokedAt),
      ),
    );
}

/** Verification belongs to the service. CAS rejects a concurrent password replacement. */
export async function changePassword(
  tdb: TenantDb,
  userId: string,
  expectedHash: string,
  newHash: string,
): Promise<number> {
  await requireActiveActor(tdb, userId);
  const [row] = await tdb.db
    .update(users)
    .set({
      passwordHash: newHash,
      sessionVersion: sql`${users.sessionVersion} + 1`,
      updatedAt: nowIso(),
    })
    .where(
      and(
        eq(users.tenantId, tdb.tenantId),
        eq(users.id, userId),
        eq(users.passwordHash, expectedHash),
      ),
    )
    .returning({ version: users.sessionVersion });
  if (!row) throw new ManagementError("conflict", "Password changed since verification");
  await markSessionsRevoked(tdb, userId);
  await audit(tdb, userId, "password.changed", userId);
  return row.version;
}

export async function revokeUserSessions(tdb: TenantDb, userId: string): Promise<number> {
  await requireActiveActor(tdb, userId);
  const [row] = await tdb.db
    .update(users)
    .set({ sessionVersion: sql`${users.sessionVersion} + 1`, updatedAt: nowIso() })
    .where(and(eq(users.tenantId, tdb.tenantId), eq(users.id, userId)))
    .returning({ version: users.sessionVersion });
  if (!row) throw new ManagementError("not_found", "Member not found in this workspace");
  await markSessionsRevoked(tdb, userId);
  await audit(tdb, userId, "sessions.revoked", userId);
  return row.version;
}

export async function listFeedSources(
  tdb: TenantDb,
  input: Partial<PageQuery> = {},
): Promise<Page<FeedSource>> {
  const q = PageQuerySchema.parse(input);
  const pattern = searchPattern(q.q);
  const where = and(
    eq(feedSources.tenantId, tdb.tenantId),
    or(ilike(feedSources.name, pattern), ilike(feedSources.url, pattern)),
  );
  return readPage(
    q,
    tdb.db
      .select(feedColumns)
      .from(feedSources)
      .where(where)
      .orderBy(desc(feedSources.createdAt), asc(feedSources.id))
      .limit(q.pageSize)
      .offset((q.page - 1) * q.pageSize),
    tdb.db.select({ total: count() }).from(feedSources).where(where),
  );
}

export async function createFeedSource(
  tdb: TenantDb,
  actorId: string,
  input: FeedSourceBody,
): Promise<FeedSource> {
  await requireActiveActor(tdb, actorId, "settings.write");
  const safe = FeedSourceBodySchema.parse(input);
  const id = randomUUID();
  let row: FeedSource | undefined;
  try {
    [row] = await tdb.db
      .insert(feedSources)
      .values({ ...safe, id, tenantId: tdb.tenantId, createdAt: nowIso() })
      .returning(feedColumns);
  } catch (error) {
    rethrowConflict(error);
  }
  if (!row) throw new ManagementError("not_found", "Feed was not created");
  await audit(tdb, actorId, "feed.created", id, {
    name: safe.name,
    enabled: safe.enabled,
    frequencySec: safe.frequencySec,
  });
  return row;
}

export async function updateFeedSource(
  tdb: TenantDb,
  actorId: string,
  id: string,
  input: FeedSourceBody,
): Promise<FeedSource> {
  await requireActiveActor(tdb, actorId, "settings.write");
  const safe = FeedSourceBodySchema.parse(input);
  let row: FeedSource | undefined;
  try {
    [row] = await tdb.db
      .update(feedSources)
      .set(safe)
      .where(and(eq(feedSources.tenantId, tdb.tenantId), eq(feedSources.id, id)))
      .returning(feedColumns);
  } catch (error) {
    rethrowConflict(error);
  }
  if (!row) throw new ManagementError("not_found", "Feed not found in this workspace");
  await audit(tdb, actorId, "feed.updated", id, {
    name: safe.name,
    enabled: safe.enabled,
    frequencySec: safe.frequencySec,
  });
  return row;
}

export async function listAudit(
  tdb: TenantDb,
  input: Partial<PageQuery> = {},
): Promise<Page<AuditLog>> {
  const q = PageQuerySchema.parse(input);
  const where = and(
    eq(auditLogs.tenantId, tdb.tenantId),
    ilike(auditLogs.action, searchPattern(q.q)),
  );
  return readPage(
    q,
    tdb.db
      .select({
        id: auditLogs.id,
        actorId: auditLogs.actorId,
        actorName: users.displayName,
        action: auditLogs.action,
        targetId: auditLogs.targetId,
        detail: auditLogs.detail,
        createdAt: auditLogs.createdAt,
      })
      .from(auditLogs)
      .leftJoin(users, and(eq(users.id, auditLogs.actorId), eq(users.tenantId, tdb.tenantId)))
      .where(where)
      .orderBy(desc(auditLogs.createdAt), asc(auditLogs.id))
      .limit(q.pageSize)
      .offset((q.page - 1) * q.pageSize),
    tdb.db.select({ total: count() }).from(auditLogs).where(where),
  );
}

export async function setAuditedRetention(
  tdb: TenantDb,
  actorId: string,
  days: number,
): Promise<number> {
  await requireActiveActor(tdb, actorId, "settings.write");
  RetentionBodySchema.parse({ days });
  await setRetentionDays(tdb, days);
  await audit(tdb, actorId, "retention.updated", tdb.tenantId, { days });
  return days;
}

/** 审计里只记地址与模型名，密钥本身不进 audit_logs——台账会长期留存，密钥不该跟着扩散。 */
export async function setAuditedModel(
  tdb: TenantDb,
  actorId: string,
  input: ModelSettingsBody,
): Promise<ModelSettingsBody> {
  await requireActiveActor(tdb, actorId, "settings.write");
  const body = ModelSettingsBodySchema.parse(input);
  await setModelSettings(tdb, body);
  await audit(tdb, actorId, "model.updated", tdb.tenantId, {
    baseUrl: body.baseUrl,
    model: body.model,
    keyChanged: Boolean(body.apiKey),
  });
  return body;
}

export async function workspaceOverview(tdb: TenantDb): Promise<WorkspaceOverview> {
  const [candidateCount] = await tdb.db
    .select({ total: count() })
    .from(candidates)
    .where(eq(candidates.tenantId, tdb.tenantId));
  const [memberCount] = await tdb.db
    .select({ total: count() })
    .from(users)
    .where(eq(users.tenantId, tdb.tenantId));
  const stages = await tdb.db
    .select({ stage: tasks.stage, total: count() })
    .from(tasks)
    .where(eq(tasks.tenantId, tdb.tenantId))
    .groupBy(tasks.stage);
  return {
    candidates: candidateCount?.total ?? 0,
    members: memberCount?.total ?? 0,
    tasks: stages.reduce((total, row) => total + row.total, 0),
    review: stages.find((row) => row.stage === "review")?.total ?? 0,
    published: stages.find((row) => row.stage === "published")?.total ?? 0,
    failed: stages.find((row) => row.stage === "failed")?.total ?? 0,
    recentTasks: (await listTaskSummaries(tdb, { pageSize: 5 })).items,
  };
}

export async function listUsage(tdb: TenantDb): Promise<UsageSummary[]> {
  return tdb.db
    .select({
      kind: usageRecords.kind,
      unit: usageRecords.unit,
      quantity: sql<string>`sum(${usageRecords.quantity})::text`,
    })
    .from(usageRecords)
    .where(eq(usageRecords.tenantId, tdb.tenantId))
    .groupBy(usageRecords.kind, usageRecords.unit)
    .orderBy(asc(usageRecords.kind), asc(usageRecords.unit));
}

export async function listCandidates(
  tdb: TenantDb,
  input: Partial<PageQuery> = {},
): Promise<Page<Candidate>> {
  const q = PageQuerySchema.parse(input);
  const where = and(
    eq(candidates.tenantId, tdb.tenantId),
    ilike(candidates.title, searchPattern(q.q)),
  );
  return readPage(
    q,
    tdb.db
      .select({
        id: candidates.id,
        title: candidates.title,
        summary: candidates.summary,
        sourceId: candidates.sourceId,
        sourceName: feedSources.name,
        heat: candidates.heat,
        rankPeak: candidates.rankPeak,
        hits: candidates.hits,
        status: candidates.status,
        firstSeenAt: candidates.firstSeenAt,
        lastSeenAt: candidates.lastSeenAt,
        createdAt: candidates.createdAt,
        updatedAt: candidates.updatedAt,
      })
      .from(candidates)
      .leftJoin(
        feedSources,
        and(eq(feedSources.id, candidates.sourceId), eq(feedSources.tenantId, tdb.tenantId)),
      )
      .where(where)
      .orderBy(desc(candidates.lastSeenAt), asc(candidates.id))
      .limit(q.pageSize)
      .offset((q.page - 1) * q.pageSize),
    tdb.db.select({ total: count() }).from(candidates).where(where),
  );
}

export async function listTaskSummaries(
  tdb: TenantDb,
  input: Partial<PageQuery> = {},
): Promise<Page<TaskSummary>> {
  const q = PageQuerySchema.parse(input);
  const join = and(eq(candidates.id, tasks.candidateId), eq(candidates.tenantId, tdb.tenantId));
  const where = and(
    eq(tasks.tenantId, tdb.tenantId),
    q.q ? ilike(candidates.title, searchPattern(q.q)) : undefined,
  );
  return readPage(
    q,
    tdb.db
      .select({
        id: tasks.id,
        capability: tasks.capability,
        candidateId: tasks.candidateId,
        title: candidates.title,
        stage: tasks.stage,
        attempts: tasks.attempts,
        claimedBy: tasks.claimedBy,
        claimedAt: tasks.claimedAt,
        errorReason: tasks.errorReason,
        createdAt: tasks.createdAt,
        updatedAt: tasks.updatedAt,
      })
      .from(tasks)
      .leftJoin(candidates, join)
      .where(where)
      .orderBy(desc(tasks.createdAt), asc(tasks.id))
      .limit(q.pageSize)
      .offset((q.page - 1) * q.pageSize),
    tdb.db.select({ total: count() }).from(tasks).leftJoin(candidates, join).where(where),
  );
}

const assetColumns = {
  id: renders.id,
  taskId: renders.taskId,
  title: candidates.title,
  version: renders.version,
  pack: renders.pack,
  fingerprint: renders.fingerprint,
  mp4Sha256: renders.mp4Sha256,
  mp4Bytes: renders.mp4Bytes,
  width: renders.width,
  height: renders.height,
  fps: renders.fps,
  durationMs: renders.durationMs,
  hasMp4: sql<boolean>`${renders.mp4Path} IS NOT NULL`,
  hasContactSheet: sql<boolean>`${renders.contactSheetPath} IS NOT NULL`,
  hasAigcSidecar: sql<boolean>`${renders.aigcSidecarPath} IS NOT NULL`,
  createdAt: renders.createdAt,
};

export async function listAssets(
  tdb: TenantDb,
  input: Partial<PageQuery> = {},
): Promise<Page<Asset>> {
  const q = PageQuerySchema.parse(input);
  const join = and(eq(candidates.id, tasks.candidateId), eq(candidates.tenantId, tdb.tenantId));
  const where = and(
    eq(tasks.tenantId, tdb.tenantId),
    q.q ? ilike(candidates.title, searchPattern(q.q)) : undefined,
  );
  return readPage(
    q,
    tdb.db
      .select(assetColumns)
      .from(renders)
      .innerJoin(tasks, eq(tasks.id, renders.taskId))
      .leftJoin(candidates, join)
      .where(where)
      .orderBy(desc(renders.createdAt), asc(renders.id))
      .limit(q.pageSize)
      .offset((q.page - 1) * q.pageSize),
    tdb.db
      .select({ total: count() })
      .from(renders)
      .innerJoin(tasks, eq(tasks.id, renders.taskId))
      .leftJoin(candidates, join)
      .where(where),
  );
}

export async function listPublishes(
  tdb: TenantDb,
  input: Partial<PageQuery> = {},
): Promise<Page<PublishSummary>> {
  const q = PageQuerySchema.parse(input);
  const join = and(eq(candidates.id, tasks.candidateId), eq(candidates.tenantId, tdb.tenantId));
  const where = and(
    eq(publishes.tenantId, tdb.tenantId),
    eq(tasks.tenantId, tdb.tenantId),
    q.q ? ilike(candidates.title, searchPattern(q.q)) : undefined,
  );
  return readPage(
    q,
    tdb.db
      .select({
        id: publishes.id,
        taskId: tasks.id,
        renderId: renders.id,
        title: candidates.title,
        renderVersion: renders.version,
        platform: publishes.platform,
        status: publishes.status,
        declaration: publishes.declaration,
        postUrl: publishes.postUrl,
        verifiedAt: publishes.verifiedAt,
        createdAt: publishes.createdAt,
        updatedAt: publishes.updatedAt,
      })
      .from(publishes)
      .innerJoin(renders, eq(renders.id, publishes.renderId))
      .innerJoin(tasks, eq(tasks.id, renders.taskId))
      .leftJoin(candidates, join)
      .where(where)
      .orderBy(desc(publishes.createdAt), asc(publishes.id))
      .limit(q.pageSize)
      .offset((q.page - 1) * q.pageSize),
    tdb.db
      .select({ total: count() })
      .from(publishes)
      .innerJoin(renders, eq(renders.id, publishes.renderId))
      .innerJoin(tasks, eq(tasks.id, renders.taskId))
      .leftJoin(candidates, join)
      .where(where),
  );
}

/** INTERNAL serving result. Do not serialize to the browser. Latest version only. */
export interface TaskArtifact {
  taskId: string;
  renderId: string;
  version: number;
  kind: ArtifactKind;
  /** 本地盘副本。保留清理之后为 null——那时只有 key 还在回答「这一版存在过」。 */
  path: string | null;
  /** 对象存储里的键。没配存储（本地驱动）时为 null。 */
  key: string | null;
  contentType: string;
  sha256: string | null;
  bytes: number | null;
  fingerprint: string;
}
/** Omit requiredVersion for legacy latest-only reads; otherwise reject stale versions before paths. */
export async function getArtifactForTask(
  tdb: TenantDb,
  taskId: string,
  kind: ArtifactKind,
  requiredVersion?: number,
): Promise<TaskArtifact> {
  const [row] = await tdb.db
    .select({ render: renders })
    .from(renders)
    .innerJoin(tasks, eq(tasks.id, renders.taskId))
    .where(and(eq(tasks.tenantId, tdb.tenantId), eq(tasks.id, taskId)))
    .orderBy(desc(renders.version))
    .limit(1);
  const render = row?.render;
  if (render && requiredVersion !== undefined && render.version !== requiredVersion)
    throw new ManagementError("conflict", "Artifact version changed");
  const path =
    kind === "mp4"
      ? render?.mp4Path
      : kind === "contact-sheet"
        ? render?.contactSheetPath
        : kind === "aigc-sidecar"
          ? render?.aigcSidecarPath
          : null;
  const key =
    kind === "mp4"
      ? render?.mp4Key
      : kind === "contact-sheet"
        ? render?.contactSheetKey
        : kind === "aigc-sidecar"
          ? render?.aigcSidecarKey
          : null;
  // 两份都没有才算不存在：本地盘被清理掉了但桶里还有，这一版仍然可读。
  if (!render || (!path && !key))
    throw new ManagementError("not_found", "Artifact not found in this workspace");
  return {
    taskId,
    renderId: render.id,
    version: render.version,
    kind,
    path: path ?? null,
    key: key ?? null,
    contentType:
      kind === "mp4" ? "video/mp4" : kind === "contact-sheet" ? "image/png" : "application/json",
    sha256: kind === "mp4" ? render.mp4Sha256 : null,
    bytes: kind === "mp4" ? render.mp4Bytes : null,
    fingerprint: render.fingerprint,
  };
}
