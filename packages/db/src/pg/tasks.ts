import { randomUUID } from "node:crypto";

import type { GateVerdict, Stage, Task, TaskEvent } from "@heron/contracts";
import {
  GateRefusalError,
  assertTransition,
  formatErrorReason,
  gateForTransition,
  type GateEvaluator,
} from "@heron/domain";
import { and, asc, desc, eq, gt, isNotNull, isNull, lt, notInArray, or, sql } from "drizzle-orm";

import { nowIso } from "../time";
import type { AdminDb } from "./admin";
import { setCandidateStatus } from "./candidates";
import { requireTenantUser } from "./identity";
import type { TenantDb } from "./rls";
import { insertConfirmation, insertGateResult, latestRender } from "./artifacts";
import type { Db } from "./driver";
import { taskEvents, candidates, tasks } from "./schema/index";

/**
 * 任务与状态机的仓储。两条规矩贯穿全文件：
 * 1. 第一个参数是通道句柄而不是连接：业务读写要 TenantDb，跨租户调度要 AdminDb；
 * 2. 这里只发语句、不自开事务——原子性归调用方的 withTenant/withAdmin。
 *    所以闸拒绝时抛 GateRefusalError 之前，gate_results 与 stage=failed 已经写在同一个事务里；
 *    调用方若不在回调里 catch，回滚会把这点痕一起抹掉（审计要求，不是日志）。
 */

/** 终态：进去就不许再动，recoverExpiredLeases 与 markFailed 都靠这两个值收口。 */
const TERMINAL: Stage[] = ["published", "failed"];

/**
 * worker 领活时跳过：还没人认领的（N5「认领才生产」）、等人点确认的（review）和已经进终态的。
 * review 必须在这里：人的闸只能由确认动作产出，让 worker 领到它只会走到「worker 替人放行」或者
 * 「worker 把等人的任务判失败」两个错误答案。人确认后 stage=approved，那时才重新可领。
 */
const CLAIMABLE_EXCLUDED: Stage[] = ["candidate", "review", "published", "failed"];

/**
 * 心跳超时线。
 * `heartbeat` 是 text 列，存 UTC ISO-8601 带 Z（口径见 time.ts），这种字符串的字典序就是时间序，
 * 所以这里在 TS 侧算出 cutoff 再按文本比——换成 DB 的 now() 反而要把 text 转 timestamp，
 * 转错了不报错，只是差一个时区。
 */
function leaseCutoff(leaseTtlSec: number): string {
  return nowIso(new Date(Date.now() - leaseTtlSec * 1000));
}

function toTask(row: typeof tasks.$inferSelect): Task {
  return {
    id: row.id,
    capability: row.capability,
    candidateId: row.candidateId,
    stage: row.stage,
    leaseOwner: row.leaseOwner,
    heartbeat: row.heartbeat,
    attempts: row.attempts,
    claimedBy: row.claimedBy,
    claimedAt: row.claimedAt,
    errorReason: row.errorReason,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
  };
}

async function selectTask(db: Db, tenantId: string, id: string): Promise<Task | null> {
  const rows = await db
    .select()
    .from(tasks)
    .where(and(eq(tasks.id, id), eq(tasks.tenantId, tenantId)))
    .limit(1);
  const row = rows[0];
  return row ? toTask(row) : null;
}

export async function getTask(tdb: TenantDb, id: string): Promise<Task | null> {
  return selectTask(tdb.db, tdb.tenantId, id);
}

/** 台账顺序：最新建的在前，界面与回归对账都按这个顺序读。 */
export async function listTasks(tdb: TenantDb): Promise<Task[]> {
  const rows = await tdb.db
    .select()
    .from(tasks)
    .where(eq(tasks.tenantId, tdb.tenantId))
    .orderBy(desc(tasks.createdAt));
  return rows.map(toTask);
}

export interface ReviewQueueItem {
  taskId: string;
  /** 候选标题；任务不带候选时为 null（candidate_id 允许 null，队列不许因此少一行）。 */
  title: string | null;
  /** 没有渲染时是 null：reviewer 的界面据此显示「无可确认版本」，而不是把这条藏起来。 */
  renderVersion: number | null;
  contactSheetFingerprint: string | null;
  contactSheetPath: string | null;
  mp4Path: string | null;
}

/**
 * 待审队列：stage=review 的任务，附上「将要被确认的是哪一版」。
 * 指纹必须跟着队列发出去——reviewer 确认时原样提交它，服务端再与当前版本比对，
 * 一来一回对不上就是「人审 A、系统发 B」被当场拦下的时刻。
 *
 * 逐条 latestRender 而不是一条 JOIN：drizzle 0.45 写不出干净的 LATERAL，
 * 而「每任务最新一版」如果用窗口函数在 SQL 里再写一份，口径就有两处。队列是等人点的一小串，
 * N+1 的成本换口径单点，值。
 */
export async function listReviewQueue(tdb: TenantDb): Promise<ReviewQueueItem[]> {
  const rows = await tdb.db
    .select({ taskId: tasks.id, title: candidates.title })
    .from(tasks)
    .leftJoin(candidates, eq(candidates.id, tasks.candidateId))
    .where(and(eq(tasks.tenantId, tdb.tenantId), eq(tasks.stage, "review")))
    .orderBy(desc(tasks.updatedAt));

  const queue: ReviewQueueItem[] = [];
  for (const row of rows) {
    const render = await latestRender(tdb, row.taskId);
    queue.push({
      taskId: row.taskId,
      title: row.title ?? null,
      renderVersion: render?.version ?? null,
      contactSheetFingerprint: render?.fingerprint ?? null,
      contactSheetPath: render?.contactSheetPath ?? null,
      mp4Path: render?.mp4Path ?? null,
    });
  }
  return queue;
}

/** 台账的一条带上它自己的 id：id 是游标推进的依据，也是「断线补哪一段」的坐标。 */
export interface TaskEventEntry {
  id: number;
  event: TaskEvent;
}

/**
 * 台账读取（游标式）：SSE 轮询与界面展开「发生过什么」都从这里读。
 * afterId 是 task_events 的 identity 主键——id 升序就是写入顺序，游标才补得出断线那一段。
 * 租户隔离不靠这里的 WHERE：task_events 不带 tenant_id，靠 applyRls 的 EXISTS 策略。
 */
export async function listTaskEventsSince(
  tdb: TenantDb,
  afterId: number,
): Promise<TaskEventEntry[]> {
  const rows = await tdb.db
    .select({
      id: taskEvents.id,
      taskId: taskEvents.taskId,
      from: taskEvents.fromStage,
      to: taskEvents.toStage,
      reason: taskEvents.reason,
      createdAt: taskEvents.createdAt,
    })
    .from(taskEvents)
    .where(gt(taskEvents.id, afterId))
    .orderBy(asc(taskEvents.id));
  return rows.map((row) => ({
    id: row.id,
    event: {
      type: "task.updated",
      taskId: row.taskId,
      from: row.from,
      to: row.to,
      reason: row.reason,
      at: row.createdAt,
    },
  }));
}

/** 当前最大台账 id：新订阅从这里起游标，不把整个历史重播一遍。 */
export async function maxTaskEventId(tdb: TenantDb): Promise<number> {
  const rows = await tdb.db
    .select({ max: sql<number>`coalesce(max(${taskEvents.id}), 0)::int` })
    .from(taskEvents);
  return rows[0]?.max ?? 0;
}

export async function createTask(
  tdb: TenantDb,
  input: { capability: string; candidateId: string | null },
): Promise<Task> {
  const id = randomUUID();
  const at = nowIso();
  await tdb.db.insert(tasks).values({
    id,
    tenantId: tdb.tenantId,
    capability: input.capability,
    candidateId: input.candidateId,
    stage: "candidate",
    createdAt: at,
    updatedAt: at,
  });
  const task = await selectTask(tdb.db, tdb.tenantId, id);
  if (!task) throw new Error(`任务写入后读不回：${id}`);
  return task;
}

/** 这条候选是不是已经发出去过——去重闸的判据（发过的热点不再做第二遍）。 */
export async function hasPublishedTaskForCandidate(
  tdb: TenantDb,
  candidateId: string,
): Promise<boolean> {
  const rows = await tdb.db
    .select({ id: tasks.id })
    .from(tasks)
    .where(
      and(
        eq(tasks.candidateId, candidateId),
        eq(tasks.tenantId, tdb.tenantId),
        eq(tasks.stage, "published"),
      ),
    )
    .limit(1);
  return rows.length > 0;
}

/**
 * 人认领：stage → claimed，并把确认人、确认时间落在审计列上（N5 的「认领才生产」）。
 * userId 必须先过 requireTenantUser：FK 不受 RLS 管，别的租户的 user id 光靠外键插得进来。
 */
export async function claimByHuman(tdb: TenantDb, taskId: string, userId: string): Promise<Task> {
  const task = await selectTask(tdb.db, tdb.tenantId, taskId);
  if (!task) throw new Error(`任务不存在：${taskId}`);
  const actor = await requireTenantUser(tdb, userId);
  assertTransition(task.stage, "claimed");

  const at = nowIso();
  await tdb.db
    .update(tasks)
    .set({ stage: "claimed", claimedBy: userId, claimedAt: at, updatedAt: at })
    .where(and(eq(tasks.id, taskId), eq(tasks.tenantId, tdb.tenantId)));
  if (task.candidateId) {
    await setCandidateStatus(tdb, task.candidateId, "selected", at);
  }
  await tdb.db.insert(taskEvents).values({
    taskId,
    fromStage: task.stage,
    toStage: "claimed",
    reason: `认领人 ${actor.displayName}（${actor.role}）`,
    createdAt: at,
  });

  const next = await selectTask(tdb.db, tdb.tenantId, taskId);
  if (!next) throw new Error(`认领后读不回任务：${taskId}`);
  return next;
}

/**
 * 失败落库：写 error_reason 与一条 to_stage=failed 的事件。
 * 已经在终态就不动——published 的任务不能被后来的异常改写成 failed。
 * 「不动」也包含台账：UPDATE 用 NOT IN 终态兜住之后必须确认它真的改到了行，
 * 否则会出现 tasks.stage=published 而最后一行台账说去了 failed——审计读台账，读到的却是假的。
 */
async function writeFailed(
  db: Db,
  tenantId: string,
  task: Task,
  reason: string,
  at: string,
): Promise<void> {
  const touched = await db
    .update(tasks)
    .set({ stage: "failed", errorReason: reason, updatedAt: at })
    .where(
      and(eq(tasks.id, task.id), eq(tasks.tenantId, tenantId), notInArray(tasks.stage, TERMINAL)),
    )
    .returning({ id: tasks.id });
  if (touched.length === 0) return;
  await db.insert(taskEvents).values({
    taskId: task.id,
    fromStage: task.stage,
    toStage: "failed",
    reason,
    createdAt: at,
  });
}

/** 带原因的失败：error_reason 是「哪条闸 + 输入片段 + 为什么拒」，界面靠它展开全文。 */
export async function markFailed(
  tdb: TenantDb,
  taskId: string,
  reason: string,
  at: string = nowIso(),
): Promise<void> {
  const task = await selectTask(tdb.db, tdb.tenantId, taskId);
  if (!task) throw new Error(`任务不存在，无法标记失败：${taskId}`);
  await writeFailed(tdb.db, tdb.tenantId, task, reason, at);
}

export interface AdvanceInput {
  taskId: string;
  /** 租约持有者（worker 进程身份），不是人。 */
  owner: string;
  to: Stage;
  evaluateGate?: GateEvaluator;
}

/**
 * 状态推进一步。挂了闸的转移必须有评估器：没给就当成拒绝（ruleId=gate.no_evaluator），
 * 因为「闸没接线」在审计上等价于「闸被绕过」，不能算放行。
 */
export async function advanceTask(tdb: TenantDb, input: AdvanceInput): Promise<Task> {
  const task = await selectTask(tdb.db, tdb.tenantId, input.taskId);
  if (!task) throw new Error(`任务不存在：${input.taskId}`);
  if (task.leaseOwner !== input.owner) {
    throw new Error(`租约不属于 ${input.owner}（当前 ${task.leaseOwner ?? "无"}），拒绝转移`);
  }
  assertTransition(task.stage, input.to);

  const gate = gateForTransition(task.stage, input.to);
  const verdict: GateVerdict | null = gate
    ? input.evaluateGate
      ? input.evaluateGate({ gate, task })
      : {
          gate,
          passed: false,
          rejections: [
            {
              ruleId: "gate.no_evaluator",
              snippet: `${task.stage} -> ${input.to}`,
              why: "该转移挂了闸但未提供评估器，不许静默放行",
            },
          ],
        }
    : null;
  const refusal =
    verdict && !verdict.passed ? { gate: verdict.gate, reason: formatErrorReason(verdict) } : null;
  const at = nowIso();

  if (verdict) {
    await insertGateResult(tdb, { taskId: task.id, gate: verdict.gate, verdict, at });
  }
  if (refusal) {
    await writeFailed(tdb.db, tdb.tenantId, task, refusal.reason, at);
    throw new GateRefusalError(refusal.gate, refusal.reason);
  }

  await tdb.db
    .update(tasks)
    .set({ stage: input.to, updatedAt: at })
    .where(and(eq(tasks.id, task.id), eq(tasks.tenantId, tdb.tenantId)));
  if (input.to === "published" && task.candidateId) {
    await setCandidateStatus(tdb, task.candidateId, "published", at);
  }
  await tdb.db.insert(taskEvents).values({
    taskId: task.id,
    fromStage: task.stage,
    toStage: input.to,
    reason: gate ? `闸 ${gate} 放行${verdict?.note ? `（${verdict.note}）` : ""}` : null,
    createdAt: at,
  });

  const next = await selectTask(tdb.db, tdb.tenantId, task.id);
  if (!next) throw new Error(`转移后读不回任务：${task.id}`);
  return next;
}

/**
 * 人的闸（review → approved）的判定。verdict 由这次确认动作本身产出：
 * 界面提交的是「人当场看到的那张联络表的指纹」，与库里的当前版本对得上才算他确认了这一版。
 * 三条 ruleId 与规格书 §5 对齐，且每条都带输入片段——只显示「失败」答不上「为什么」。
 */
function humanVerdict(
  taskId: string,
  render: { version: number; fingerprint: string } | null,
  submitted: string,
): GateVerdict {
  if (!render) {
    return {
      gate: "human",
      passed: false,
      rejections: [
        { ruleId: "human.render_missing", snippet: taskId, why: "没有可确认的渲染版本" },
      ],
    };
  }
  if (render.fingerprint !== submitted) {
    return {
      gate: "human",
      passed: false,
      rejections: [
        {
          ruleId: "human.fingerprint_mismatch",
          snippet: `提交 ${submitted.slice(0, 12)} / 当前 v${render.version} ${render.fingerprint.slice(0, 12)}`,
          why: "人看到的联络表不是当前这一版：重渲了但没重新确认，或者确认的是旧快照",
        },
      ],
    };
  }
  return {
    gate: "human",
    passed: true,
    rejections: [],
    note: `v${render.version} ${render.fingerprint.slice(0, 12)}`,
  };
}

export interface ConfirmInput {
  taskId: string;
  /** 确认人：服务端会话里的人，不是请求体里谁写什么算谁。 */
  userId: string;
  /** 人当场看到的那张联络表的指纹。 */
  contactSheetFingerprint: string;
}

/**
 * 人工确认。与 advanceTask 分开的两条理由，都在这个函数身上：
 * 1. 不看租约——worker 心跳一断，任务就没人能在界面上点确认，这种失败没人能解释；
 *    租约防的是「两个渲染进程抢同一条活」，人的点击没有这个风险。
 * 2. verdict 由确认动作产出（见 humanVerdict），而不是调用方递一个 passed: true 进来。
 *
 * 写下去的是四样：gate_results（为什么放行）、confirmations（谁、何时、哪一版指纹）、
 * tasks.stage、task_events（台账）。少任何一样，「人审 A、系统发 B」就无从对账。
 */
export async function confirmByHuman(tdb: TenantDb, input: ConfirmInput): Promise<Task> {
  const actor = await requireTenantUser(tdb, input.userId);
  const task = await selectTask(tdb.db, tdb.tenantId, input.taskId);
  if (!task) throw new Error(`任务不存在：${input.taskId}`);
  assertTransition(task.stage, "approved");

  const at = nowIso();
  const render = await latestRender(tdb, task.id);
  const verdict = humanVerdict(task.id, render, input.contactSheetFingerprint);
  await insertGateResult(tdb, {
    taskId: task.id,
    gate: verdict.gate,
    verdict,
    renderVersion: render?.version ?? null,
    at,
  });
  // !render 这半边是给类型收窄用的：verdict 为放行时 render 必然存在，两条条件走的是同一个拒绝分支
  if (!verdict.passed || !render) {
    const reason = formatErrorReason(verdict);
    await writeFailed(tdb.db, tdb.tenantId, task, reason, at);
    throw new GateRefusalError(verdict.gate, reason);
  }

  await insertConfirmation(tdb, {
    taskId: task.id,
    renderVersion: render.version,
    contactSheetFingerprint: render.fingerprint,
    confirmedBy: actor.id,
  });
  await tdb.db
    .update(tasks)
    .set({ stage: "approved", updatedAt: at })
    .where(and(eq(tasks.id, task.id), eq(tasks.tenantId, tdb.tenantId)));
  await tdb.db.insert(taskEvents).values({
    taskId: task.id,
    fromStage: task.stage,
    toStage: "approved",
    reason: `确认人 ${actor.displayName}（${actor.role}）`,
    createdAt: at,
  });

  const next = await selectTask(tdb.db, tdb.tenantId, task.id);
  if (!next) throw new Error(`确认后读不回任务：${task.id}`);
  return next;
}

/** 领到的那条活连同它的租户：调度方没有租户坐标，这个 id 就是接下来 withTenant 的入口。 */
export interface ClaimedTask {
  readonly task: Task;
  readonly tenantId: string;
}

/**
 * worker 领下一条活。**跨租户**：调度方不知道下一个活属于哪个客户，领到的任务自带 tenant_id。
 * 所以它走 AdminDb（heron_owner / BYPASSRLS），业务函数一律不收 AdminDb——类型系统因此挡住
 * 「业务代码偷偷拿特权连接」。
 *
 * `FOR UPDATE SKIP LOCKED`：两个 worker 同时来领，一个锁到行，另一个直接跳过被别人锁着的行，
 * 不会撞成同一条任务两次渲染。锁活到 withAdmin 的事务提交。
 *
 * 租约：lease_owner + heartbeat，心跳过期就可以被别人接管（N9：DB 租约模型能扩到 N 个 worker）。
 */
export async function claimNextTask(
  admin: AdminDb,
  owner: string,
  leaseTtlSec: number,
): Promise<ClaimedTask | null> {
  const rows = await admin.db
    .select()
    .from(tasks)
    .where(
      and(
        notInArray(tasks.stage, CLAIMABLE_EXCLUDED),
        or(isNull(tasks.leaseOwner), lt(tasks.heartbeat, leaseCutoff(leaseTtlSec))),
      ),
    )
    .orderBy(asc(tasks.createdAt))
    .limit(1)
    .for("update", { of: [tasks], skipLocked: true });
  const row = rows[0];
  if (!row) return null;

  const at = nowIso();
  await admin.db
    .update(tasks)
    .set({ leaseOwner: owner, heartbeat: at, updatedAt: at })
    .where(eq(tasks.id, row.id));
  return {
    task: toTask({ ...row, leaseOwner: owner, heartbeat: at, updatedAt: at }),
    tenantId: row.tenantId,
  };
}

/**
 * 放掉租约：worker 走到 review 就停，人点确认前这条活不该被任何进程视为「有人正在做」。
 * 必须真的释放而不是留着等过期——过期会被 recoverExpiredLeases 当成失败计数，
 * 一条好好等人的任务三次心跳之后就变成 failed，那是把「等人」误判成「进程死了」。
 *
 * 只放自己的租约：owner 对不上说明活已经被别人接管，那时清空租约等于把别人的活偷走。
 */
export async function releaseLease(tdb: TenantDb, taskId: string, owner: string): Promise<void> {
  const touched = await tdb.db
    .update(tasks)
    .set({ leaseOwner: null, heartbeat: null, updatedAt: nowIso() })
    .where(and(eq(tasks.id, taskId), eq(tasks.tenantId, tdb.tenantId), eq(tasks.leaseOwner, owner)))
    .returning({ id: tasks.id });
  if (touched.length === 0) {
    throw new Error(`释放租约失败：任务 ${taskId} 的租约不属于 ${owner}`);
  }
}

/**
 * 租约过期回收：心跳断了的活要么放回池子（attempts+1），要么超过 maxAttempts 就停手等人。
 * 也是跨租户调度方的活，走 AdminDb。
 */
export async function recoverExpiredLeases(
  admin: AdminDb,
  options: { leaseTtlSec: number; maxAttempts: number },
): Promise<{ returned: string[]; failed: string[] }> {
  const rows = await admin.db
    .select()
    .from(tasks)
    .where(
      and(
        notInArray(tasks.stage, TERMINAL),
        isNotNull(tasks.leaseOwner),
        lt(tasks.heartbeat, leaseCutoff(options.leaseTtlSec)),
      ),
    );

  const returned: string[] = [];
  const failed: string[] = [];
  for (const row of rows) {
    const attempts = row.attempts + 1;
    const at = nowIso();
    await admin.db
      .update(tasks)
      .set({ attempts, leaseOwner: null, heartbeat: null, updatedAt: at })
      .where(eq(tasks.id, row.id));
    if (attempts >= options.maxAttempts) {
      await writeFailed(
        admin.db,
        row.tenantId,
        toTask(row),
        `租约连续过期 ${attempts} 次，停止自动重试，等人处理（最后停在 ${row.stage}）`,
        at,
      );
      failed.push(row.id);
    } else {
      returned.push(row.id);
    }
  }
  return { returned, failed };
}
