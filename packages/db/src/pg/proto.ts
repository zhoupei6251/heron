import type {
  Page,
  PageQuery,
  ProtoFailureKind,
  ProtoRevision,
  ProtoStage,
  ProtoTarget,
  ProtoTheme,
} from "@heron/contracts";
import { PageQuerySchema } from "@heron/contracts";
import {
  and,
  asc,
  count,
  desc,
  eq,
  ilike,
  inArray,
  isNotNull,
  isNull,
  lt,
  notInArray,
  or,
  sql,
} from "drizzle-orm";

import { snowflakeId } from "../id";
import { nowIso } from "../time";
import type { AdminDb } from "./admin";
import { ManagementError, readPage, searchPattern } from "./console-query";
import type { Db } from "./driver";
import { requireTenantUser } from "./identity";
import type { TenantDb } from "./rls";
import { protoProjects, protoRevisions, tenants, users, alive } from "./schema/index";

/**
 * 原型工坊的仓储。与 tasks.ts 同一套规矩：第一个参数是通道句柄（业务 TenantDb / 调度 AdminDb），
 * 这里只发语句、不自开事务——原子性归调用方的 withTenant/withAdmin。
 */

const TERMINAL: ProtoStage[] = ["delivered", "failed"];
/** worker 只领这两种：draft 是待生成，approved 是等人确认后待交付（交付只是一次落库）。 */
const CLAIMABLE: ProtoStage[] = ["draft", "approved"];
/**
 * 计入口径并不等于「所有非终态」：review 在等人点确认，不占引擎也不算一条并发生成。
 * 把 review 算进去，一个躺着等人审的项目会永久吃掉一个并发名额。
 */
const INFLIGHT: ProtoStage[] = ["draft", "scripting", "compiling", "approved"];

/** heartbeat 是 text 列（UTC ISO 带 Z），字典序即时间序；cutoff 在 TS 侧算，见 tasks.ts。 */
function leaseCutoff(leaseTtlSec: number): string {
  return nowIso(new Date(Date.now() - leaseTtlSec * 1000));
}

export type ProtoProjectRecord = typeof protoProjects.$inferSelect;
export type ProtoRevisionRecord = typeof protoRevisions.$inferSelect;

const revisionColumns = {
  id: protoRevisions.id,
  projectId: protoRevisions.projectId,
  version: protoRevisions.version,
  model: protoRevisions.model,
  tokensIn: protoRevisions.tokensIn,
  tokensOut: protoRevisions.tokensOut,
  engineVersion: protoRevisions.engineVersion,
  failureKind: protoRevisions.failureKind,
  failureReason: protoRevisions.failureReason,
  rpSha256: protoRevisions.rpSha256,
  rpBytes: protoRevisions.rpBytes,
  previewSha256: protoRevisions.previewSha256,
  hasSource: sql<boolean>`${protoRevisions.rp1Source} IS NOT NULL`,
  hasAudit: sql<boolean>`${protoRevisions.audit} IS NOT NULL`,
  hasPreview: sql<boolean>`${protoRevisions.previewSha256} IS NOT NULL`,
  hasRp: sql<boolean>`${protoRevisions.rpSha256} IS NOT NULL`,
  confirmedBy: protoRevisions.confirmedBy,
  confirmedByName: users.displayName,
  confirmedAt: protoRevisions.confirmedAt,
  axureVerified: protoRevisions.axureVerified,
  axureVerifiedAt: protoRevisions.axureVerifiedAt,
  deliveredAt: protoRevisions.deliveredAt,
  createTime: protoRevisions.createTime,
};

/**
 * 并发上限的判据要在租户行上排队，否则两个标签页同时提交时都先读到「还差一个」再各自插入。
 * 锁顺序固定为「租户行 → 项目行」，create 与 regenerate 都照这个顺序，不会互相咬死。
 */
async function lockTenant(tdb: TenantDb): Promise<void> {
  await tdb.db
    .select({ id: tenants.id })
    .from(tenants)
    .where(and(eq(tenants.id, tdb.tenantId), alive(tenants.delFlag)))
    .for("update");
}

async function assertCapacity(tdb: TenantDb, maxInflight: number): Promise<void> {
  await lockTenant(tdb);
  const total = await countProtoInflight(tdb);
  if (total >= maxInflight) {
    throw new ManagementError(
      "conflict",
      `同时最多 ${String(maxInflight)} 个原型任务在途，当前已占用 ${String(total)} 个`,
    );
  }
}

export async function countProtoInflight(tdb: TenantDb): Promise<number> {
  const [row] = await tdb.db
    .select({ total: count() })
    .from(protoProjects)
    .where(
      and(
        eq(protoProjects.tenantId, tdb.tenantId),
        inArray(protoProjects.stage, INFLIGHT),
        alive(protoProjects.delFlag),
      ),
    );
  return row?.total ?? 0;
}

/** 列表投影：不含需求原文（8000 字 × 20 条会把表格接口变成下载接口）。 */
export interface ProtoProjectSummaryRecord {
  id: string;
  title: string;
  target: ProtoTarget;
  theme: ProtoTheme;
  stage: ProtoStage;
  errorReason: string | null;
  leaseOwner: string | null;
  heartbeat: string | null;
  attempts: number;
  createBy: string | null;
  createTime: string;
  updateTime: string;
  latestVersion: number | null;
}

export async function listProtoProjects(
  tdb: TenantDb,
  input: Partial<PageQuery> = {},
): Promise<Page<ProtoProjectSummaryRecord>> {
  const q = PageQuerySchema.parse(input);
  const where = and(
    eq(protoProjects.tenantId, tdb.tenantId),
    ilike(protoProjects.title, searchPattern(q.q)),
    alive(protoProjects.delFlag),
  );
  // 相关子查询里的外层列必须写全 `proto_projects.id`：drizzle 把 ${protoProjects.id} 拼成裸的 "id"，
  // 在子查询作用域里那会解析成 r.id（子表自己的主键），版本永远数不出来。
  const latestVersion = sql<number | null>`(
    SELECT max(r.version) FROM proto_revision r WHERE r.project_id = proto_project.id
  )`;
  return readPage(
    q,
    tdb.db
      .select({
        id: protoProjects.id,
        title: protoProjects.title,
        target: protoProjects.target,
        theme: protoProjects.theme,
        stage: protoProjects.stage,
        errorReason: protoProjects.errorReason,
        leaseOwner: protoProjects.leaseOwner,
        heartbeat: protoProjects.heartbeat,
        attempts: protoProjects.attempts,
        createBy: protoProjects.createBy,
        createTime: protoProjects.createTime,
        updateTime: protoProjects.updateTime,
        latestVersion,
      })
      .from(protoProjects)
      .where(where)
      .orderBy(desc(protoProjects.createTime), asc(protoProjects.id))
      .limit(q.pageSize)
      .offset((q.page - 1) * q.pageSize),
    tdb.db.select({ total: count() }).from(protoProjects).where(where),
  );
}

export async function getProtoProject(
  tdb: TenantDb,
  projectId: string,
): Promise<ProtoProjectRecord | null> {
  const [row] = await tdb.db
    .select()
    .from(protoProjects)
    .where(
      and(
        eq(protoProjects.id, projectId),
        eq(protoProjects.tenantId, tdb.tenantId),
        alive(protoProjects.delFlag),
      ),
    )
    .limit(1);
  return row ?? null;
}

export async function requireProtoProject(
  tdb: TenantDb,
  projectId: string,
): Promise<ProtoProjectRecord> {
  const project = await getProtoProject(tdb, projectId);
  if (!project) throw new ManagementError("not_found", "原型项目不存在或不在当前工作区");
  return project;
}

/**
 * 建项目即排一次生成：stage=draft，worker 会领走。需求原文就在这里落库，
 * 之后每次生成都读这一份，不接受「这次生成换一段需求」。
 *
 * 幂等（需求 P6）：同标题 + 同需求且还没走到终态，视为同一次提交，返回已有那条。
 * 判据要在租户行锁里查，否则双击的两条请求都读不到对方、各自插一条。
 * shortcut: 键是需求本身而不是客户端生成的 id；要区分「同一份需求刻意建两个项目」时再加 idempotency_key 列。
 */
export async function createProtoProject(
  tdb: TenantDb,
  actorId: string,
  input: { title: string; requirement: string; target: ProtoTarget; theme: ProtoTheme },
  maxInflight: number,
): Promise<ProtoProjectRecord> {
  const actor = await requireTenantUser(tdb, actorId);
  await lockTenant(tdb);
  const [same] = await tdb.db
    .select()
    .from(protoProjects)
    .where(
      and(
        eq(protoProjects.tenantId, tdb.tenantId),
        eq(protoProjects.title, input.title),
        eq(protoProjects.requirement, input.requirement),
        notInArray(protoProjects.stage, TERMINAL),
      ),
    )
    .limit(1);
  if (same) return same;
  await assertCapacity(tdb, maxInflight);
  const at = nowIso();
  const row: ProtoProjectRecord = {
    id: snowflakeId(),
    delFlag: "0",
    tenantId: tdb.tenantId,
    title: input.title,
    requirement: input.requirement,
    target: input.target,
    theme: input.theme,
    stage: "draft",
    errorReason: null,
    leaseOwner: null,
    heartbeat: null,
    attempts: 0,
    createBy: actor.id,
    createTime: at,
    updateBy: actor.id,
    updateTime: at,
  };
  await tdb.db.insert(protoProjects).values(row);
  return row;
}

/**
 * 人点「重新生成」：把终态项目推回 draft。attempts 归零是给人动作留的余地——
 * attempts 记的是自动重试，人主动重跑不该背着上一轮的次数。
 * 在途/待审的项目一律拒绝：那说明还有一份稿子在跑或在等人，再排一份只会撞在同一条版本上。
 */
export async function requestProtoGeneration(
  tdb: TenantDb,
  projectId: string,
  maxInflight: number,
  /** 点「重新生成」的人。 */
  actorId: string,
): Promise<void> {
  await assertCapacity(tdb, maxInflight);
  const [row] = await tdb.db
    .select({ stage: protoProjects.stage })
    .from(protoProjects)
    .where(and(eq(protoProjects.id, projectId), eq(protoProjects.tenantId, tdb.tenantId)))
    .for("update");
  if (!row) throw new ManagementError("not_found", "原型项目不存在或不在当前工作区");
  if (!TERMINAL.includes(row.stage)) {
    throw new ManagementError(
      "conflict",
      `项目当前停在 ${row.stage}，只有在已交付或失败后才能重新生成`,
    );
  }
  await tdb.db
    .update(protoProjects)
    .set({
      stage: "draft",
      errorReason: null,
      attempts: 0,
      leaseOwner: null,
      heartbeat: null,
      updateBy: actorId,
      updateTime: nowIso(),
    })
    .where(and(eq(protoProjects.id, projectId), eq(protoProjects.tenantId, tdb.tenantId)));
}

/** 领到的那条项目连同租户：调度方没有租户坐标，这个 id 就是接下来 withTenant 的入口。 */
export interface ClaimedProtoProject {
  readonly project: ProtoProjectRecord;
  readonly tenantId: string;
}

/** 跨租户调度：与 claimNextTask 同一把 FOR UPDATE SKIP LOCKED，同一套租约判据。 */
async function claimProto(
  admin: AdminDb,
  owner: string,
  leaseTtlSec: number,
  projectId?: string,
): Promise<ClaimedProtoProject | null> {
  const [row] = await admin.db
    .select()
    .from(protoProjects)
    .where(
      and(
        ...(projectId === undefined ? [] : [eq(protoProjects.id, projectId)]),
        inArray(protoProjects.stage, CLAIMABLE),
        or(isNull(protoProjects.leaseOwner), lt(protoProjects.heartbeat, leaseCutoff(leaseTtlSec))),
      ),
    )
    .orderBy(asc(protoProjects.createTime))
    .limit(1)
    .for("update", { of: [protoProjects], skipLocked: true });
  if (!row) return null;

  const at = nowIso();
  await admin.db
    .update(protoProjects)
    .set({ leaseOwner: owner, heartbeat: at, updateBy: null, updateTime: at })
    .where(eq(protoProjects.id, row.id));
  return {
    project: { ...row, leaseOwner: owner, heartbeat: at, updateTime: at },
    tenantId: row.tenantId,
  };
}

export function claimNextProtoProject(
  admin: AdminDb,
  owner: string,
  leaseTtlSec: number,
): Promise<ClaimedProtoProject | null> {
  return claimProto(admin, owner, leaseTtlSec);
}

/** 队列投来 projectId 时按 id 领：判据与「最早一条」完全相同，避免队列把别的工作抢走。 */
export function claimProtoProjectById(
  admin: AdminDb,
  projectId: string,
  owner: string,
  leaseTtlSec: number,
): Promise<ClaimedProtoProject | null> {
  return claimProto(admin, owner, leaseTtlSec, projectId);
}

/**
 * 长任务（模型 + 引擎编译可能几分钟）期间续租。只有还握着这条租约的进程能续：
 * 租约已被回收时这里静默返回 false，不会把活抢回来。
 */
export async function touchProtoLease(
  tdb: TenantDb,
  projectId: string,
  owner: string,
): Promise<boolean> {
  const touched = await tdb.db
    .update(protoProjects)
    .set({ heartbeat: nowIso(), updateBy: null, updateTime: nowIso() })
    .where(
      and(
        eq(protoProjects.id, projectId),
        eq(protoProjects.tenantId, tdb.tenantId),
        eq(protoProjects.leaseOwner, owner),
      ),
    )
    .returning({ id: protoProjects.id });
  return touched.length > 0;
}

/**
 * worker 的一步推进：条件更新带 from 与租约持有者，两个 worker 抢同一条活时后到的那个走不进下一阶段。
 * clearLease 用在「停在 review 等人」：任务好好等人的时候不能再占着租约，否则心跳过期会被当成进程死亡。
 */
export async function advanceProtoProject(
  tdb: TenantDb,
  input: {
    projectId: string;
    from: ProtoStage;
    to: ProtoStage;
    owner: string;
    clearLease?: boolean;
  },
): Promise<void> {
  const touched = await tdb.db
    .update(protoProjects)
    .set({
      stage: input.to,
      updateBy: null,
      updateTime: nowIso(),
      ...(input.clearLease === true ? { leaseOwner: null, heartbeat: null } : {}),
    })
    .where(
      and(
        eq(protoProjects.id, input.projectId),
        eq(protoProjects.tenantId, tdb.tenantId),
        eq(protoProjects.stage, input.from),
        eq(protoProjects.leaseOwner, input.owner),
      ),
    )
    .returning({ id: protoProjects.id });
  if (touched.length === 0) {
    throw new Error(
      `原型项目 ${input.projectId} 不在 ${input.from}（或租约已不属于 ${input.owner}），拒绝转移到 ${input.to}`,
    );
  }
}

async function failProto(
  db: Db,
  tenantId: string,
  projectId: string,
  reason: string,
  at: string,
): Promise<boolean> {
  const touched = await db
    .update(protoProjects)
    .set({
      stage: "failed",
      errorReason: reason,
      leaseOwner: null,
      heartbeat: null,
      updateBy: null,
      updateTime: at,
    })
    .where(
      and(
        eq(protoProjects.id, projectId),
        eq(protoProjects.tenantId, tenantId),
        notInArray(protoProjects.stage, TERMINAL),
      ),
    )
    .returning({ id: protoProjects.id });
  return touched.length > 0;
}

/** 失败落库：原因只有一条，界面展开全文；已在终态就不动（交付过的项目不能被后来的异常改写）。 */
export async function markProtoFailed(
  tdb: TenantDb,
  projectId: string,
  reason: string,
  owner: string,
): Promise<void> {
  const [current] = await tdb.db
    .select()
    .from(protoProjects)
    .where(and(eq(protoProjects.id, projectId), eq(protoProjects.tenantId, tdb.tenantId)))
    .for("update");
  if (!current || TERMINAL.includes(current.stage)) {
    throw new Error(`原型项目 ${projectId} 已在终态，无法标记失败`);
  }
  if (current.leaseOwner !== owner)
    throw new Error(`原型项目 ${projectId} 租约已易手，拒绝标记失败`);
  await failProto(tdb.db, tdb.tenantId, projectId, reason, nowIso());
}

/** 版本号由这里算：同一项目版本唯一（u_proto_revision_project_version），调用方不许猜。 */
export async function createProtoRevision(
  tdb: TenantDb,
  projectId: string,
): Promise<{ id: string; version: number }> {
  const [row] = await tdb.db
    .select({ max: sql<number>`coalesce(max(${protoRevisions.version}), 0)::int` })
    .from(protoRevisions)
    .where(and(eq(protoRevisions.projectId, projectId), alive(protoRevisions.delFlag)));
  const version = (row?.max ?? 0) + 1;
  const id = snowflakeId();
  await tdb.db.insert(protoRevisions).values({ id, projectId, version, createTime: nowIso() });
  return { id, version };
}

/** 只传了的字段进 SET：状态推进不许把已经落进去的产物指纹抹回 NULL。 */
export interface ProtoRevisionPatch {
  rp1Source?: string | null;
  engineVersion?: string | null;
  audit?: unknown;
  model?: string | null;
  tokensIn?: number | null;
  tokensOut?: number | null;
  failureKind?: ProtoFailureKind | null;
  failureReason?: string | null;
  rpSha256?: string | null;
  rpBytes?: number | null;
  previewSha256?: string | null;
  deliveredAt?: string | null;
}

export async function updateProtoRevision(
  tdb: TenantDb,
  revisionId: string,
  patch: ProtoRevisionPatch,
  /** 补这些字段的人；worker 写的是系统产物，传 null。 */
  actorId: string | null,
): Promise<void> {
  const touched = await tdb.db
    .update(protoRevisions)
    .set({ ...patch, updateBy: actorId, updateTime: nowIso() })
    .where(eq(protoRevisions.id, revisionId))
    .returning({ id: protoRevisions.id });
  if (touched.length === 0) throw new Error(`原型版本 ${revisionId} 不存在或不在当前工作区`);
}

/** 下载接口要原文（RP/1 源码、审计报告），所以这里返回整行而不是投影。 */
export async function getProtoRevision(
  tdb: TenantDb,
  revisionId: string,
): Promise<ProtoRevisionRecord | null> {
  const [row] = await tdb.db
    .select()
    .from(protoRevisions)
    .where(eq(protoRevisions.id, revisionId))
    .limit(1);
  return row ?? null;
}

export async function listProtoRevisions(
  tdb: TenantDb,
  projectId: string,
): Promise<ProtoRevision[]> {
  return tdb.db
    .select(revisionColumns)
    .from(protoRevisions)
    .leftJoin(
      users,
      and(eq(users.id, protoRevisions.confirmedBy), eq(users.tenantId, tdb.tenantId)),
    )
    .where(eq(protoRevisions.projectId, projectId))
    .orderBy(desc(protoRevisions.version));
}

/** 当前那一版：确认与详情都读它——「人审的是哪一版」由数据回答，不由界面带来的字段回答。 */
export async function latestProtoRevision(
  tdb: TenantDb,
  projectId: string,
): Promise<ProtoRevision | null> {
  const rows = await tdb.db
    .select(revisionColumns)
    .from(protoRevisions)
    .leftJoin(
      users,
      and(eq(users.id, protoRevisions.confirmedBy), eq(users.tenantId, tdb.tenantId)),
    )
    .where(eq(protoRevisions.projectId, projectId))
    .orderBy(desc(protoRevisions.version))
    .limit(1);
  return rows[0] ?? null;
}

export interface ConfirmProtoInput {
  projectId: string;
  revisionId: string;
  /** 人当场看到的那张预览图的 sha256。 */
  previewSha256: string;
  /** 原生闸的人证：不勾也能交付，但这一版会永久带着「未在 Axure 验证」。 */
  axureVerified: boolean;
}

/**
 * 人的闸（review → approved）。指纹对不上就是「人审 A、系统发 B」被当场拦下的时刻，直接 409。
 * 确认人、确认时间、预览图指纹与 Axure 勾选（谁、何时）都落在这一版上，下一版重新来过。
 */
export async function confirmProtoRevision(
  tdb: TenantDb,
  actorId: string,
  input: ConfirmProtoInput,
): Promise<void> {
  const actor = await requireTenantUser(tdb, actorId);
  const [project] = await tdb.db
    .select({ stage: protoProjects.stage })
    .from(protoProjects)
    .where(and(eq(protoProjects.id, input.projectId), eq(protoProjects.tenantId, tdb.tenantId)))
    .for("update");
  if (!project) throw new ManagementError("not_found", "原型项目不存在或不在当前工作区");
  if (project.stage !== "review") {
    throw new ManagementError(
      "conflict",
      `项目当前停在 ${project.stage}，只有待审（review）的原型才能确认`,
    );
  }
  const latest = await latestProtoRevision(tdb, input.projectId);
  if (!latest || latest.id !== input.revisionId) {
    throw new ManagementError("conflict", "这条版本不是当前待确认的版本，请刷新后再确认");
  }
  if (!latest.previewSha256 || latest.previewSha256 !== input.previewSha256) {
    throw new ManagementError(
      "conflict",
      "预览图指纹对不上：人看到的不是当前这一版（可能刚重渲过），请刷新后再确认",
    );
  }

  const at = nowIso();
  await tdb.db
    .update(protoRevisions)
    .set({
      confirmedBy: actor.id,
      confirmedAt: at,
      axureVerified: input.axureVerified,
      axureVerifiedBy: input.axureVerified ? actor.id : null,
      axureVerifiedAt: input.axureVerified ? at : null,
      updateBy: actorId,
      updateTime: at,
    })
    .where(eq(protoRevisions.id, input.revisionId));
  const touched = await tdb.db
    .update(protoProjects)
    .set({ stage: "approved", updateBy: actorId, updateTime: at })
    .where(
      and(
        eq(protoProjects.id, input.projectId),
        eq(protoProjects.tenantId, tdb.tenantId),
        eq(protoProjects.stage, "review"),
      ),
    )
    .returning({ id: protoProjects.id });
  if (touched.length === 0) {
    throw new ManagementError("conflict", "项目状态刚被改动，请刷新后再确认");
  }
}

/** 交付落库：版本上记交付时间，项目进终态并放租约；已交付的版本不会被下一次生成改写。 */
export async function markProtoDelivered(
  tdb: TenantDb,
  input: { projectId: string; revisionId: string; owner: string },
): Promise<void> {
  const at = nowIso();
  const touched = await tdb.db
    .update(protoProjects)
    .set({
      stage: "delivered",
      updateBy: null,
      updateTime: at,
      leaseOwner: null,
      heartbeat: null,
    })
    .where(
      and(
        eq(protoProjects.id, input.projectId),
        eq(protoProjects.tenantId, tdb.tenantId),
        eq(protoProjects.stage, "approved"),
        eq(protoProjects.leaseOwner, input.owner),
      ),
    )
    .returning({ id: protoProjects.id });
  if (touched.length === 0) {
    throw new Error(`原型项目 ${input.projectId} 不在待交付状态（或租约已不属于 ${input.owner}）`);
  }
  await tdb.db
    .update(protoRevisions)
    .set({ deliveredAt: at })
    .where(eq(protoRevisions.id, input.revisionId));
}

/**
 * 租约过期回收：心跳断了的活放回池子（attempts+1），超过上限就停下等人。
 * 跨租户，所以走 AdminDb；与新闻线同一套语义。
 *
 * 回收必须把 stage 退回可领的位置：scripting/compiling 只在租约在手时才是活的，
 * 光清租约会让这条项目永远停在没人能领的中间态。approved 例外——它的下一步是交付落库，
 * 不是重新生成，退成 draft 会白烧一次模型与编译。
 */
export async function recoverExpiredProtoLeases(
  admin: AdminDb,
  options: { leaseTtlSec: number; maxAttempts: number },
): Promise<{ returned: string[]; failed: string[] }> {
  const rows = await admin.db
    .select()
    .from(protoProjects)
    .where(
      and(
        inArray(protoProjects.stage, INFLIGHT),
        isNotNull(protoProjects.leaseOwner),
        lt(protoProjects.heartbeat, leaseCutoff(options.leaseTtlSec)),
      ),
    )
    .for("update", { of: [protoProjects], skipLocked: true });

  const returned: string[] = [];
  const failed: string[] = [];
  for (const row of rows) {
    const attempts = row.attempts + 1;
    const at = nowIso();
    if (attempts >= options.maxAttempts) {
      const stopped = await failProto(
        admin.db,
        row.tenantId,
        row.id,
        `租约连续过期 ${String(attempts)} 次，停止自动重试，等人处理（最后停在 ${row.stage}）`,
        at,
      );
      if (stopped) failed.push(row.id);
      continue;
    }
    await admin.db
      .update(protoProjects)
      .set({
        stage: row.stage === "approved" ? "approved" : "draft",
        attempts,
        leaseOwner: null,
        heartbeat: null,
        updateTime: at,
      })
      .where(eq(protoProjects.id, row.id));
    returned.push(row.id);
  }
  return { returned, failed };
}
