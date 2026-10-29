import type { GateName, GateVerdict, PublishStatus, Rejection } from "@heron/contracts";
import { and, desc, eq, inArray, sql } from "drizzle-orm";

import { snowflakeId } from "../id";
import { nowIso } from "../time";
import { requireTenantUser } from "./identity";
import type { TenantDb } from "./rls";
import {
  aigcPosture,
  confirmations,
  gateResults,
  publishes,
  renders,
  scripts,
  alive,
} from "./schema/index";

/**
 * 一次闸判定落一行：rejections 保留结构化原文，界面展开全文与 oracle 对账都靠它。
 *
 * 这三张子表（task_events / gate_results / renders）不带 tenant_id，租户由 tasks 的 FK 继承，
 * 所以隔离靠的是 applyRls 给它们建的那条 EXISTS 策略——不是这里的 WHERE。
 * 少了那条策略，这里每一个按 taskId 的查询都是跨租户读别人的行。
 */
export async function insertGateResult(
  tdb: TenantDb,
  input: {
    taskId: string;
    gate: GateName;
    verdict: GateVerdict;
    renderVersion?: number | null;
    at?: string;
  },
): Promise<string> {
  const id = snowflakeId();
  await tdb.db.insert(gateResults).values({
    id,
    taskId: input.taskId,
    gate: input.gate,
    renderVersion: input.renderVersion ?? null,
    passed: input.verdict.passed,
    note: input.verdict.note ?? null,
    // 列带 $type<Rejection[]>，drizzle 负责序列化；这里再 JSON.parse 一遍是把自己写出去的东西当外部输入
    rejections: input.verdict.rejections,
    evaluatedAt: input.at ?? nowIso(),
  });
  return id;
}

export interface GateResultRow {
  gate: GateName;
  passed: boolean;
  note: string | null;
  rejections: Rejection[];
}

/** 一个任务的闸判定，最新一次在前——界面上「为什么被拒」读第一行，回归对账读全部。 */
export async function listGateResults(tdb: TenantDb, taskId: string): Promise<GateResultRow[]> {
  return tdb.db
    .select({
      gate: gateResults.gate,
      passed: gateResults.passed,
      note: gateResults.note,
      rejections: gateResults.rejections,
    })
    .from(gateResults)
    .where(eq(gateResults.taskId, taskId))
    .orderBy(desc(gateResults.evaluatedAt));
}

export interface NewRender {
  taskId: string;
  version: number;
  pack: string;
  fingerprint: string;
  mp4Path?: string | null;
  mp4Sha256?: string | null;
  mp4Bytes?: number | null;
  contactSheetPath?: string | null;
  width?: number | null;
  height?: number | null;
  fps?: number | null;
  durationMs?: number | null;
  aigcSidecarPath?: string | null;
  /** 对象存储里的键。没配存储（本地驱动）时留空，路径列就是唯一真相。 */
  mp4Key?: string | null;
  contactSheetKey?: string | null;
  aigcSidecarKey?: string | null;
  timings?: readonly { stage: string; seconds: number }[];
}

/** 同一任务同一版本只能有一行（u_news_render_task_version），所以版本号由这里算，不许调用方猜。 */
export async function nextRenderVersion(tdb: TenantDb, taskId: string): Promise<number> {
  const rows = await tdb.db
    .select({ max: sql<number>`coalesce(max(${renders.version}), 0)::int` })
    .from(renders)
    .where(eq(renders.taskId, taskId));
  return (rows[0]?.max ?? 0) + 1;
}

export async function insertRender(tdb: TenantDb, input: NewRender): Promise<string> {
  const id = snowflakeId();
  await tdb.db.insert(renders).values({
    id,
    taskId: input.taskId,
    version: input.version,
    pack: input.pack,
    mp4Path: input.mp4Path ?? null,
    mp4Sha256: input.mp4Sha256 ?? null,
    mp4Bytes: input.mp4Bytes ?? null,
    contactSheetPath: input.contactSheetPath ?? null,
    fingerprint: input.fingerprint,
    width: input.width ?? null,
    height: input.height ?? null,
    fps: input.fps ?? null,
    durationMs: input.durationMs ?? null,
    aigcSidecarPath: input.aigcSidecarPath ?? null,
    mp4Key: input.mp4Key ?? null,
    contactSheetKey: input.contactSheetKey ?? null,
    aigcSidecarKey: input.aigcSidecarKey ?? null,
    timings: [...(input.timings ?? [])],
    createTime: nowIso(),
  });
  return id;
}

/** 「当前那一版渲染」的投影：确认、发布、待审队列都读它，版本号最大的那行。 */
export interface CurrentRender {
  id: string;
  version: number;
  fingerprint: string;
  contactSheetPath: string | null;
  mp4Path: string | null;
}

/**
 * 当前那一版渲染（版本号最大的那行）。
 * 人工确认与发布都问它，因为「人审的是哪一版」必须由数据回答，不能由界面上带的字段回答——
 * 界面拿到的可能是上一秒的快照，而这一行一秒内可能已经被 worker 换掉了。
 */
export async function latestRender(tdb: TenantDb, taskId: string): Promise<CurrentRender | null> {
  const rows = await tdb.db
    .select({
      id: renders.id,
      version: renders.version,
      fingerprint: renders.fingerprint,
      contactSheetPath: renders.contactSheetPath,
      mp4Path: renders.mp4Path,
    })
    .from(renders)
    .where(eq(renders.taskId, taskId))
    .orderBy(desc(renders.version))
    .limit(1);
  const row = rows[0];
  return row ?? null;
}

/**
 * 人工确认必须同时落确认人、确认时间、当时联络表的版本指纹——少第三样就会出现「人审 A、系统发 B」。
 * confirmedBy 先验归属：外键检查不受 RLS 管，别的租户的 user id 光靠 FK 是插得进来的。
 */
export async function insertConfirmation(
  tdb: TenantDb,
  input: {
    taskId: string;
    renderVersion: number;
    contactSheetFingerprint: string;
    confirmedBy: string;
  },
): Promise<string> {
  await requireTenantUser(tdb, input.confirmedBy);
  const id = snowflakeId();
  await tdb.db.insert(confirmations).values({
    id,
    tenantId: tdb.tenantId,
    taskId: input.taskId,
    renderVersion: input.renderVersion,
    contactSheetFingerprint: input.contactSheetFingerprint,
    confirmedBy: input.confirmedBy,
    confirmedAt: nowIso(),
  });
  return id;
}

/** 指纹对上才算确认过；换一版联络表就必须重新确认，这条是发布闸的判据。 */
export async function confirmationMatches(
  tdb: TenantDb,
  input: { taskId: string; renderVersion: number; contactSheetFingerprint: string },
): Promise<boolean> {
  const rows = await tdb.db
    .select({ id: confirmations.id })
    .from(confirmations)
    .where(
      and(
        eq(confirmations.taskId, input.taskId),
        eq(confirmations.renderVersion, input.renderVersion),
        eq(confirmations.contactSheetFingerprint, input.contactSheetFingerprint),
        eq(confirmations.tenantId, tdb.tenantId),
      ),
    )
    .limit(1);
  return rows.length > 0;
}

/**
 * 保留清理的候选：**全部**渲染都早于 cutoff 的任务。
 * 判据是 max(created_at) 而不是「有任一版过期」：同一任务重渲后新旧版本共用一个目录，
 * 按旧版本判断会把刚出的新片一起删掉。渲染行本身不删——publishes.render_id 指着它，
 * 审计也要能回答「当时发的是哪一版、文件在哪（已清理）」。
 */
export async function listExpiredTaskDirs(tdb: TenantDb, cutoff: string): Promise<string[]> {
  const rows = await tdb.db
    .select({ taskId: renders.taskId })
    .from(renders)
    .groupBy(renders.taskId)
    .having(sql`max(${renders.createTime}) < ${cutoff}`);
  return rows.map((row) => row.taskId);
}

/**
 * 过期清理要删的远端对象：按任务取全部版本、全部类型的 key。
 *
 * 按 renders 行取而不是「按约定拼出 key」：渲染行不随清理删除（发布记录指着它），
 * 所以这份清单下一轮还能再拿到——远端删除失败是自愈的，不需要失败队列。
 */
export async function listRenderObjectKeys(
  tdb: TenantDb,
  taskIds: readonly string[],
): Promise<Map<string, string[]>> {
  if (taskIds.length === 0) return new Map();
  const rows = await tdb.db
    .select({
      taskId: renders.taskId,
      mp4: renders.mp4Key,
      sheet: renders.contactSheetKey,
      sidecar: renders.aigcSidecarKey,
    })
    .from(renders)
    .where(inArray(renders.taskId, [...taskIds]));

  const grouped = new Map<string, Set<string>>();
  for (const row of rows) {
    const keys = grouped.get(row.taskId) ?? new Set<string>();
    // 同一 key 可能在多行重复（重渲后同一版本号不会，但跨行去重便宜且必要：删两次会 612）
    for (const key of [row.mp4, row.sheet, row.sidecar]) if (key) keys.add(key);
    grouped.set(row.taskId, keys);
  }
  return new Map([...grouped].map(([taskId, keys]) => [taskId, [...keys]]));
}

export interface NewPublish {
  renderId: string;
  idempotencyKey: string;
  declaration: string;
  /** 登录态只存路径 + 指纹（规格书 §5）；假发布没有凭证，两列必须同时为空。 */
  credentialPath?: string | null;
  credentialSha256?: string | null;
  platform?: string;
}

/** 幂等键租户内唯一（u_news_publish_tenant_idempotency）：重试前先拿它查「是不是其实已经发出去了」。 */
export async function insertPublish(tdb: TenantDb, input: NewPublish): Promise<string> {
  const id = snowflakeId();
  const at = nowIso();
  await tdb.db.insert(publishes).values({
    id,
    tenantId: tdb.tenantId,
    renderId: input.renderId,
    platform: input.platform ?? "douyin",
    idempotencyKey: input.idempotencyKey,
    declaration: input.declaration,
    credentialPath: input.credentialPath ?? null,
    credentialSha256: input.credentialSha256 ?? null,
    status: "pending",
    createTime: at,
    updateTime: at,
  });
  return id;
}

/**
 * 只有传了的字段才进 SET：`undefined` 会被 drizzle 跳过，等于旧版的 COALESCE 语义——
 * 状态推进不许把已经落进去的 post_url / verified_at 抹回 NULL。
 */
export async function updatePublish(
  tdb: TenantDb,
  id: string,
  input: {
    status: PublishStatus;
    receipt?: string | null;
    postUrl?: string | null;
    /** 对外核实「其实已经发出去了」的时间：回执丢失时先填它，再决定要不要重发。 */
    verifiedAt?: string | null;
  },
): Promise<void> {
  await tdb.db
    .update(publishes)
    .set({
      status: input.status,
      receipt: input.receipt,
      postUrl: input.postUrl,
      verifiedAt: input.verifiedAt,
      // 发布流水线是系统跑的：人的那一手记在 confirmations（确认人 + 指纹），这里只记状态
      updateBy: null,
      updateTime: nowIso(),
    })
    .where(and(eq(publishes.id, id), eq(publishes.tenantId, tdb.tenantId)));
}

/** 幂等键 → 发布单 id；查不到返回 null。 */
export async function findPublishByKey(
  tdb: TenantDb,
  idempotencyKey: string,
): Promise<string | null> {
  const rows = await tdb.db
    .select({ id: publishes.id })
    .from(publishes)
    .where(and(eq(publishes.idempotencyKey, idempotencyKey), eq(publishes.tenantId, tdb.tenantId)))
    .limit(1);
  return rows[0]?.id ?? null;
}

export interface NewPosture {
  renderMode: string;
  declaration: string;
  note?: string;
  /** 改姿态的人（uuid 外键）。与确认人同一条规矩：外键不受 RLS 管，归属在这里查。 */
  setBy: string;
  /** 每租户最多一条现行（u_news_posture_single_current），所以旧姿态由调用方先撤销再立新的。 */
  revertedAt?: string | null;
}

/**
 * 落一条 AIGC 姿态。这一层存在的理由不是 insert 本身，而是 setBy 的归属校验：
 * 没有它，别的租户的 user id 靠外键就能写进「谁绕过了角标」这一列，审计链接到别人身上。
 */
export async function insertPosture(tdb: TenantDb, input: NewPosture): Promise<string> {
  await requireTenantUser(tdb, input.setBy);
  const id = snowflakeId();
  await tdb.db.insert(aigcPosture).values({
    id,
    tenantId: tdb.tenantId,
    renderMode: input.renderMode,
    declaration: input.declaration,
    note: input.note ?? "",
    setBy: input.setBy,
    sinceAt: nowIso(),
    revertedAt: input.revertedAt ?? null,
  });
  return id;
}

/** 一任务一份稿子：重写直接覆盖，形状判定留给契约闸（这里不替它做白名单）。 */
export async function upsertScript(
  tdb: TenantDb,
  input: { taskId: string; model: string; content: unknown },
): Promise<void> {
  await tdb.db
    .insert(scripts)
    .values({
      id: snowflakeId(),
      taskId: input.taskId,
      model: input.model,
      content: input.content,
      createTime: nowIso(),
    })
    .onConflictDoUpdate({
      target: scripts.taskId,
      // 唯一索引是 partial 的（只对活着的稿唯一），ON CONFLICT 的推断必须带上同一个谓词，
      // 否则 Postgres 找不到可推断的索引，插入直接报错。
      targetWhere: sql`${scripts.delFlag} = '0'`,
      set: { model: input.model, content: input.content, createTime: nowIso() },
    });
}

/** 契约闸读它。返回原样 JSON：形状对不对由 domain 的纯函数判，不在仓储里提前归一。 */
export async function getScript(tdb: TenantDb, taskId: string): Promise<unknown> {
  const rows = await tdb.db
    .select({ content: scripts.content })
    .from(scripts)
    .where(and(eq(scripts.taskId, taskId), alive(scripts.delFlag)))
    .limit(1);
  return rows[0]?.content ?? null;
}
