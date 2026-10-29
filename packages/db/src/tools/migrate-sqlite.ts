import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { rename } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";

import type { CandidateStatus, GateName, PublishStatus, Rejection, Stage } from "@heron/contracts";
import { UNUSABLE_PREFIX } from "@heron/domain";
import { count } from "drizzle-orm";

import { legacyUuidToId, snowflakeId } from "../id";
import type { Db } from "../pg/driver";
import { createUser } from "../pg/identity";
import { withTenant, type TenantDb } from "../pg/rls";
import {
  aigcPosture,
  candidates,
  confirmations,
  feedSources,
  gateResults,
  publishes,
  renders,
  settings,
  taskEvents,
  tasks,
} from "../pg/schema/index";
import { findTenantBySlug } from "../pg/tenants";

/**
 * 把多租户之前的 SQLite 开发库搬进 Postgres。
 *
 * 三条硬口径：
 * - 目标租户必须已存在且业务表为空：导入不是合并，「旧行 + 新行」混在一张表里就再也对不出账。
 * - 身份列不再收自由文本：`claimed_by` / `confirmer` / `set_by` 落成不可登录的合成 actor，
 *   审计写得出「是谁干的」，但这行永远登不进来。
 * - 计数对不上就整体回滚：先在同一个事务里插完再数，任何一张表不一致都不提交。
 *
 * 旧库文件的退场（改名保留）不在这里做：DB 事务提交与文件改名不是一件事，
 * 由 CLI 在导入成功后调用 retireLegacyDb。
 */

export const IMPORTED_TABLES = [
  "settings",
  "feed_sources",
  "candidates",
  "tasks",
  "task_events",
  "gate_results",
  "renders",
  "confirmations",
  "publishes",
  "aigc_posture",
] as const;

export type ImportedTable = (typeof IMPORTED_TABLES)[number];

export interface ImportReport {
  tenant: { id: string; slug: string };
  tables: Record<ImportedTable, { source: number; target: number }>;
  syntheticActors: { name: string; id: string; email: string }[];
}

export interface ImportOptions {
  db: Db;
  sourcePath: string;
  tenantSlug: string;
}

interface LegacySetting {
  key: string;
  value: string;
  updated_at: string;
}

interface LegacyFeedSource {
  id: string;
  name: string;
  url: string;
  frequency_sec: number;
  last_fetch_at: string | null;
  created_at: string;
}

interface LegacyCandidate {
  id: string;
  title: string;
  summary: string;
  norm_key: string;
  source_id: string | null;
  heat: number;
  rank_peak: number | null;
  hits: number;
  status: string;
  first_seen_at: string;
  last_seen_at: string;
  created_at: string;
  updated_at: string;
}

interface LegacyTask {
  id: string;
  capability: string;
  candidate_id: string | null;
  stage: string;
  lease_owner: string | null;
  heartbeat: string | null;
  attempts: number;
  claimed_by: string | null;
  claimed_at: string | null;
  error_reason: string | null;
  created_at: string;
  updated_at: string;
}

interface LegacyTaskEvent {
  id: number;
  task_id: string;
  from_stage: string;
  to_stage: string;
  reason: string | null;
  created_at: string;
}

interface LegacyGateResult {
  id: string;
  task_id: string;
  gate: string;
  render_version: number | null;
  passed: number;
  note: string | null;
  rejections: string;
  evaluated_at: string;
}

interface LegacyRender {
  id: string;
  task_id: string;
  version: number;
  pack: string;
  mp4_path: string | null;
  mp4_sha256: string | null;
  mp4_bytes: number | null;
  contact_sheet_path: string | null;
  fingerprint: string;
  width: number | null;
  height: number | null;
  fps: number | null;
  duration_ms: number | null;
  aigc_sidecar_path: string | null;
  timings: string;
  created_at: string;
}

interface LegacyConfirmation {
  id: string;
  task_id: string;
  render_version: number;
  contact_sheet_fingerprint: string;
  confirmer: string;
  confirmed_at: string;
}

interface LegacyPublish {
  id: string;
  render_id: string;
  platform: string;
  idempotency_key: string;
  declaration: string;
  credential_path: string | null;
  credential_sha256: string | null;
  status: string;
  post_url: string | null;
  receipt: string | null;
  verified_at: string | null;
  created_at: string;
  updated_at: string;
}

interface LegacyPosture {
  id: string;
  render_mode: string;
  declaration: string;
  note: string;
  set_by: string;
  since_at: string;
  reverted_at: string | null;
}

interface LegacyData {
  settings: LegacySetting[];
  feedSources: LegacyFeedSource[];
  candidates: LegacyCandidate[];
  tasks: LegacyTask[];
  taskEvents: LegacyTaskEvent[];
  gateResults: LegacyGateResult[];
  renders: LegacyRender[];
  confirmations: LegacyConfirmation[];
  publishes: LegacyPublish[];
  posture: LegacyPosture[];
}

function readLegacy(sourcePath: string): LegacyData {
  if (!existsSync(sourcePath)) throw new Error(`旧库不存在：${sourcePath}`);
  const sqlite = new DatabaseSync(sourcePath, { readOnly: true });
  try {
    const all = <T>(sql: string): T[] => sqlite.prepare(sql).all() as unknown as T[];
    return {
      settings: all<LegacySetting>("SELECT key, value, updated_at FROM settings"),
      feedSources: all<LegacyFeedSource>(
        "SELECT id, name, url, frequency_sec, last_fetch_at, created_at FROM feed_sources",
      ),
      candidates: all<LegacyCandidate>(
        "SELECT id, title, summary, norm_key, source_id, heat, rank_peak, hits, status, " +
          "first_seen_at, last_seen_at, created_at, updated_at FROM candidates",
      ),
      tasks: all<LegacyTask>(
        "SELECT id, capability, candidate_id, stage, lease_owner, heartbeat, attempts, " +
          "claimed_by, claimed_at, error_reason, created_at, updated_at FROM tasks",
      ),
      taskEvents: all<LegacyTaskEvent>(
        "SELECT id, task_id, from_stage, to_stage, reason, created_at FROM task_events ORDER BY id",
      ),
      gateResults: all<LegacyGateResult>(
        "SELECT id, task_id, gate, render_version, passed, note, rejections, evaluated_at " +
          "FROM gate_results",
      ),
      renders: all<LegacyRender>(
        "SELECT id, task_id, version, pack, mp4_path, mp4_sha256, mp4_bytes, " +
          "contact_sheet_path, fingerprint, width, height, fps, duration_ms, " +
          "aigc_sidecar_path, timings, created_at FROM renders",
      ),
      confirmations: all<LegacyConfirmation>(
        "SELECT id, task_id, render_version, contact_sheet_fingerprint, confirmer, " +
          "confirmed_at FROM confirmations",
      ),
      publishes: all<LegacyPublish>(
        "SELECT id, render_id, platform, idempotency_key, declaration, credential_path, " +
          "credential_sha256, status, post_url, receipt, verified_at, created_at, updated_at " +
          "FROM publishes",
      ),
      posture: all<LegacyPosture>(
        "SELECT id, render_mode, declaration, note, set_by, since_at, reverted_at FROM aigc_posture",
      ),
    };
  } finally {
    sqlite.close();
  }
}

function parseJson<T>(text: string, label: string): T {
  try {
    return JSON.parse(text) as T;
  } catch (cause) {
    throw new Error(`${label} 的 JSON 解析失败：${text.slice(0, 80)}`, { cause });
  }
}

function actorEmail(name: string): string {
  // 合成 actor 不需要可读邮箱：用名字摘要保证同名同址、不同名不同址，且永远是小写。
  const digest = createHash("sha256").update(name).digest("hex").slice(0, 12);
  return `legacy+${digest}@heron.invalid`;
}

function actorPasswordHash(name: string): string {
  // 与 p0 的合成 actor 同一条规矩：可写进审计列，verifyPassword 一律返回 false。
  return `${UNUSABLE_PREFIX}legacy-actor:${name}`;
}

/** 收集所有出现在「人」这一列里的自由文本；空串不算人，但必填列里的空串要报错。 */
function collectActorNames(data: LegacyData): string[] {
  const names = new Set<string>();
  const add = (value: string | null): void => {
    const name = value?.trim() ?? "";
    if (name) names.add(name);
  };
  for (const task of data.tasks) add(task.claimed_by);
  for (const confirmation of data.confirmations) {
    if (!confirmation.confirmer.trim()) {
      throw new Error(`确认记录 ${confirmation.id} 的 confirmer 是空串：人的闸不能没有确认人`);
    }
    add(confirmation.confirmer);
  }
  for (const posture of data.posture) {
    if (!posture.set_by.trim()) {
      throw new Error(`AIGC 姿态 ${posture.id} 的 set_by 是空串：改姿态的人不能是空白`);
    }
    add(posture.set_by);
  }
  return [...names];
}

async function targetCount(tdb: TenantDb, table: ImportedTable): Promise<number> {
  const one = async (query: PromiseLike<{ n: number }[]>): Promise<number> =>
    Number((await query)[0]?.n ?? 0);
  switch (table) {
    case "settings":
      return one(tdb.db.select({ n: count() }).from(settings));
    case "feed_sources":
      return one(tdb.db.select({ n: count() }).from(feedSources));
    case "candidates":
      return one(tdb.db.select({ n: count() }).from(candidates));
    case "tasks":
      return one(tdb.db.select({ n: count() }).from(tasks));
    case "task_events":
      return one(tdb.db.select({ n: count() }).from(taskEvents));
    case "gate_results":
      return one(tdb.db.select({ n: count() }).from(gateResults));
    case "renders":
      return one(tdb.db.select({ n: count() }).from(renders));
    case "confirmations":
      return one(tdb.db.select({ n: count() }).from(confirmations));
    case "publishes":
      return one(tdb.db.select({ n: count() }).from(publishes));
    case "aigc_posture":
      return one(tdb.db.select({ n: count() }).from(aigcPosture));
  }
}

function sourceCounts(data: LegacyData): Record<ImportedTable, number> {
  return {
    settings: data.settings.length,
    feed_sources: data.feedSources.length,
    candidates: data.candidates.length,
    tasks: data.tasks.length,
    task_events: data.taskEvents.length,
    gate_results: data.gateResults.length,
    renders: data.renders.length,
    confirmations: data.confirmations.length,
    publishes: data.publishes.length,
    aigc_posture: data.posture.length,
  };
}

async function assertTargetEmpty(tdb: TenantDb, slug: string): Promise<void> {
  const occupied: string[] = [];
  for (const table of IMPORTED_TABLES) {
    const n = await targetCount(tdb, table);
    if (n > 0) occupied.push(`${table} ${n} 行`);
  }
  if (occupied.length > 0) {
    throw new Error(
      `目标租户「${slug}」已有数据（${occupied.join("、")}）：导入要求空目标，` +
        "避免旧库行与现有行混在一起。换一个空租户，或备份后清空这些表再导入。",
    );
  }
}

async function insertAll(
  t: TenantDb,
  data: LegacyData,
  tenantId: string,
  actorIds: Map<string, string>,
): Promise<void> {
  const actorOf = (raw: string, label: string): string => {
    const name = raw.trim();
    const id = actorIds.get(name);
    if (!id) throw new Error(`${label} 引用了没建出来的合成 actor：${name || "(空)"}`);
    return id;
  };

  if (data.settings.length > 0) {
    await t.db.insert(settings).values(
      data.settings.map((row) => ({
        tenantId,
        key: row.key,
        value: row.value,
        updateTime: row.updated_at,
      })),
    );
  }

  if (data.feedSources.length > 0) {
    await t.db.insert(feedSources).values(
      data.feedSources.map((row) => ({
        id: legacyUuidToId(row.id),
        tenantId,
        name: row.name,
        url: row.url,
        frequencySec: row.frequency_sec,
        lastFetchAt: row.last_fetch_at,
        createTime: row.created_at,
      })),
    );
  }

  if (data.candidates.length > 0) {
    await t.db.insert(candidates).values(
      data.candidates.map((row) => ({
        id: legacyUuidToId(row.id),
        tenantId,
        title: row.title,
        summary: row.summary,
        normKey: row.norm_key,
        sourceId: row.source_id ? legacyUuidToId(row.source_id) : null,
        heat: row.heat,
        rankPeak: row.rank_peak,
        hits: row.hits,
        status: row.status as CandidateStatus,
        firstSeenAt: row.first_seen_at,
        lastSeenAt: row.last_seen_at,
        createTime: row.created_at,
        updateTime: row.updated_at,
      })),
    );
  }

  if (data.tasks.length > 0) {
    await t.db.insert(tasks).values(
      data.tasks.map((row) => ({
        id: legacyUuidToId(row.id),
        tenantId,
        capability: row.capability,
        candidateId: row.candidate_id ? legacyUuidToId(row.candidate_id) : null,
        stage: row.stage as Stage,
        leaseOwner: row.lease_owner,
        heartbeat: row.heartbeat,
        attempts: row.attempts,
        claimedBy: row.claimed_by?.trim()
          ? actorOf(row.claimed_by, `任务 ${row.id} 的 claimed_by`)
          : null,
        claimedAt: row.claimed_at,
        errorReason: row.error_reason,
        createTime: row.created_at,
        updateTime: row.updated_at,
      })),
    );
  }

  // task_events 的主键现在是雪花 id，旧 id 不保留；按旧 id 顺序逐条插，
  // 让新 id 的次序与旧库的事件次序一致（事件表没有外部引用，id 本身不是契约）。
  for (const row of data.taskEvents) {
    await t.db.insert(taskEvents).values({
      id: snowflakeId(),
      taskId: legacyUuidToId(row.task_id),
      fromStage: row.from_stage as Stage,
      toStage: row.to_stage as Stage,
      reason: row.reason,
      createTime: row.created_at,
    });
  }

  if (data.renders.length > 0) {
    await t.db.insert(renders).values(
      data.renders.map((row) => ({
        id: legacyUuidToId(row.id),
        taskId: legacyUuidToId(row.task_id),
        version: row.version,
        pack: row.pack,
        mp4Path: row.mp4_path,
        mp4Sha256: row.mp4_sha256,
        mp4Bytes: row.mp4_bytes,
        contactSheetPath: row.contact_sheet_path,
        fingerprint: row.fingerprint,
        width: row.width,
        height: row.height,
        fps: row.fps,
        durationMs: row.duration_ms,
        aigcSidecarPath: row.aigc_sidecar_path,
        timings: parseJson<{ stage: string; seconds: number }[]>(
          row.timings,
          `渲染 ${row.id} 的 timings`,
        ),
        createTime: row.created_at,
      })),
    );
  }

  if (data.gateResults.length > 0) {
    await t.db.insert(gateResults).values(
      data.gateResults.map((row) => ({
        id: legacyUuidToId(row.id),
        taskId: legacyUuidToId(row.task_id),
        gate: row.gate as GateName,
        renderVersion: row.render_version,
        passed: row.passed !== 0,
        note: row.note,
        rejections: parseJson<Rejection[]>(row.rejections, `闸结果 ${row.id} 的 rejections`),
        evaluatedAt: row.evaluated_at,
      })),
    );
  }

  if (data.confirmations.length > 0) {
    await t.db.insert(confirmations).values(
      data.confirmations.map((row) => ({
        id: legacyUuidToId(row.id),
        tenantId,
        taskId: legacyUuidToId(row.task_id),
        renderVersion: row.render_version,
        contactSheetFingerprint: row.contact_sheet_fingerprint,
        confirmedBy: actorOf(row.confirmer, `确认记录 ${row.id} 的 confirmer`),
        confirmedAt: row.confirmed_at,
      })),
    );
  }

  if (data.publishes.length > 0) {
    await t.db.insert(publishes).values(
      data.publishes.map((row) => ({
        id: legacyUuidToId(row.id),
        tenantId,
        // 发布一定有渲染（旧库里 render_id 是 NOT NULL），所以这里不做空值兜底
        renderId: legacyUuidToId(row.render_id),
        platform: row.platform,
        idempotencyKey: row.idempotency_key,
        declaration: row.declaration,
        credentialPath: row.credential_path,
        credentialSha256: row.credential_sha256,
        status: row.status as PublishStatus,
        postUrl: row.post_url,
        receipt: row.receipt,
        verifiedAt: row.verified_at,
        createTime: row.created_at,
        updateTime: row.updated_at,
      })),
    );
  }

  if (data.posture.length > 0) {
    await t.db.insert(aigcPosture).values(
      data.posture.map((row) => ({
        id: legacyUuidToId(row.id),
        tenantId,
        renderMode: row.render_mode,
        declaration: row.declaration,
        note: row.note,
        setBy: actorOf(row.set_by, `AIGC 姿态 ${row.id} 的 set_by`),
        sinceAt: row.since_at,
        revertedAt: row.reverted_at,
      })),
    );
  }
}

export async function importLegacySqlite(options: ImportOptions): Promise<ImportReport> {
  const tenant = await findTenantBySlug(options.db, options.tenantSlug);
  if (!tenant) {
    throw new Error(
      `没有这个工作空间：${options.tenantSlug}。先建租户与 owner（pnpm db:seed-owner），导入不替你建租户。`,
    );
  }

  const data = readLegacy(options.sourcePath);
  const expected = sourceCounts(data);
  const actorNames = collectActorNames(data);

  return withTenant(options.db, tenant.id, async (t) => {
    await assertTargetEmpty(t, tenant.slug);

    const syntheticActors: ImportReport["syntheticActors"] = [];
    const actorIds = new Map<string, string>();
    for (const name of actorNames) {
      const email = actorEmail(name);
      const id = await createUser(t, {
        email,
        displayName: `旧库合成：${name}`,
        passwordHash: actorPasswordHash(name),
        role: "viewer",
      });
      actorIds.set(name, id);
      syntheticActors.push({ name, id, email });
    }

    await insertAll(t, data, tenant.id, actorIds);

    const tables = {} as ImportReport["tables"];
    for (const table of IMPORTED_TABLES) {
      const target = await targetCount(t, table);
      if (target !== expected[table]) {
        throw new Error(
          `迁移计数对不上：${table} 旧库 ${expected[table]} 行、新库 ${target} 行。整笔导入回滚。`,
        );
      }
      tables[table] = { source: expected[table], target };
    }

    return {
      tenant: { id: tenant.id, slug: tenant.slug },
      tables,
      syntheticActors,
    };
  });
}

/**
 * 旧库文件退场：改名保留，不删。WAL/SHM 跟着主文件一起改，
 * 只搬主文件会把还没 checkpoint 的已提交数据留在原地。
 */
export async function retireLegacyDb(sourcePath: string): Promise<string> {
  if (!existsSync(sourcePath)) throw new Error(`旧库文件不存在：${sourcePath}`);
  const retired = `${sourcePath}.pre-multi-tenant`;
  if (existsSync(retired)) throw new Error(`退场目标已经存在，不覆盖：${retired}`);
  for (const suffix of ["-wal", "-shm"]) {
    if (existsSync(sourcePath + suffix) && existsSync(retired + suffix)) {
      throw new Error(`退场目标已经存在，不覆盖：${retired}${suffix}`);
    }
  }
  await rename(sourcePath, retired);
  for (const suffix of ["-wal", "-shm"]) {
    if (existsSync(sourcePath + suffix)) await rename(sourcePath + suffix, retired + suffix);
  }
  return retired;
}
