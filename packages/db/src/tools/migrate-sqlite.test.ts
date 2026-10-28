import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";

import { verifyPassword } from "@heron/domain";
import { eq, like } from "drizzle-orm";
import { afterAll, describe, expect, it } from "vitest";

import {
  candidates,
  confirmations,
  gateResults,
  migrate,
  openDb,
  publishes,
  renders,
  settings,
  taskEvents,
  tasks,
  tenants,
  users,
  withTenant,
  type Db,
} from "../pg/index";
import { importLegacySqlite, retireLegacyDb } from "./migrate-sqlite";

const AT = "2026-10-22T09:12:52.388Z";
const TENANT_ID = "00000000-0000-4000-8000-0000000000cc";
const TENANT_SLUG = "legacy";
const CANDIDATE = "8787fee3-72b4-4615-b47b-c3945e8aa03e";
const TASK_1 = "985a995d-2661-451c-92c4-630502f2ae2d";
const TASK_2 = "0c632074-aa8d-44c8-b1df-1cd5779221cd";
const RENDER = "7360d2cf-f7f6-4272-aa82-4e443ded30d1";
const FINGERPRINT = "8116d43057e90c077045a7cb1822be542be9136226c213c07047bb55036a304a";

const SCHEMA = `
  CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL);
  CREATE TABLE feed_sources (
    id TEXT PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL,
    frequency_sec INTEGER NOT NULL, last_fetch_at TEXT, created_at TEXT NOT NULL
  );
  CREATE TABLE candidates (
    id TEXT PRIMARY KEY, title TEXT NOT NULL, summary TEXT NOT NULL, norm_key TEXT NOT NULL,
    source_id TEXT, heat REAL NOT NULL, rank_peak INTEGER, hits INTEGER NOT NULL,
    status TEXT NOT NULL, first_seen_at TEXT NOT NULL, last_seen_at TEXT NOT NULL,
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
  );
  CREATE TABLE tasks (
    id TEXT PRIMARY KEY, capability TEXT NOT NULL, candidate_id TEXT, stage TEXT NOT NULL,
    lease_owner TEXT, heartbeat TEXT, attempts INTEGER NOT NULL, claimed_by TEXT,
    claimed_at TEXT, error_reason TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
  );
  CREATE TABLE task_events (
    id INTEGER PRIMARY KEY, task_id TEXT NOT NULL, from_stage TEXT NOT NULL,
    to_stage TEXT NOT NULL, reason TEXT, created_at TEXT NOT NULL
  );
  CREATE TABLE gate_results (
    id TEXT PRIMARY KEY, task_id TEXT NOT NULL, gate TEXT NOT NULL, render_version INTEGER,
    passed INTEGER NOT NULL, note TEXT, rejections TEXT NOT NULL, evaluated_at TEXT NOT NULL
  );
  CREATE TABLE renders (
    id TEXT PRIMARY KEY, task_id TEXT NOT NULL, version INTEGER NOT NULL, pack TEXT NOT NULL,
    mp4_path TEXT, mp4_sha256 TEXT, mp4_bytes INTEGER, contact_sheet_path TEXT,
    fingerprint TEXT NOT NULL, width INTEGER, height INTEGER, fps REAL, duration_ms INTEGER,
    aigc_sidecar_path TEXT, timings TEXT NOT NULL, created_at TEXT NOT NULL
  );
  CREATE TABLE confirmations (
    id TEXT PRIMARY KEY, task_id TEXT NOT NULL, render_version INTEGER NOT NULL,
    contact_sheet_fingerprint TEXT NOT NULL, confirmer TEXT NOT NULL, confirmed_at TEXT NOT NULL
  );
  CREATE TABLE publishes (
    id TEXT PRIMARY KEY, render_id TEXT NOT NULL, platform TEXT NOT NULL,
    idempotency_key TEXT NOT NULL, declaration TEXT NOT NULL, credential_path TEXT,
    credential_sha256 TEXT, status TEXT NOT NULL, post_url TEXT, receipt TEXT,
    verified_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
  );
  CREATE TABLE aigc_posture (
    id TEXT PRIMARY KEY, render_mode TEXT NOT NULL, declaration TEXT NOT NULL, note TEXT NOT NULL,
    set_by TEXT NOT NULL, since_at TEXT NOT NULL, reverted_at TEXT
  );
`;

function createLegacyDb(path: string): void {
  const sqlite = new DatabaseSync(path);
  try {
    sqlite.exec(SCHEMA);
    sqlite.exec(`
      INSERT INTO settings VALUES ('health.last_probe_at', '${AT}', '${AT}');
      INSERT INTO candidates VALUES (
        '${CANDIDATE}', '旧库候选', '', '旧库候选', NULL, 0, NULL, 1,
        'published', '${AT}', '${AT}', '${AT}', '${AT}'
      );
      INSERT INTO tasks VALUES (
        '${TASK_1}', 'p0-fake', '${CANDIDATE}', 'published', 'runner-admin', '${AT}', 0,
        'p0-script', '${AT}', NULL, '${AT}', '${AT}'
      );
      INSERT INTO tasks VALUES (
        '${TASK_2}', 'p0-fake', NULL, 'failed', NULL, NULL, 1,
        NULL, NULL, '闸拒了', '${AT}', '${AT}'
      );
      INSERT INTO task_events VALUES (1, '${TASK_1}', 'candidate', 'claimed', '认领人 p0-script', '${AT}');
      INSERT INTO task_events VALUES (2, '${TASK_1}', 'claimed', 'scripting', NULL, '${AT}');
      INSERT INTO task_events VALUES (3, '${TASK_1}', 'scripting', 'factcheck', NULL, '${AT}');
      INSERT INTO renders VALUES (
        '${RENDER}', '${TASK_1}', 1, 'demo', NULL, NULL, NULL, NULL,
        '${FINGERPRINT}', NULL, NULL, NULL, 1000, NULL,
        '[{"stage":"render","seconds":1.5}]', '${AT}'
      );
      INSERT INTO gate_results VALUES (
        '9c3213a6-b514-451e-82e5-cb7e7260ae99', '${TASK_1}', 'contract', 1, 1,
        '占位放行', '[]', '${AT}'
      );
      INSERT INTO gate_results VALUES (
        '847e6ba1-8374-43e9-a14f-30028ce39da3', '${TASK_2}', 'contract', NULL, 0,
        NULL,
        '[{"ruleId":"contract.declaration","snippet":"无声明","why":"缺合规声明"}]',
        '${AT}'
      );
      INSERT INTO confirmations VALUES (
        'ba158257-b2d4-4760-8bc4-ee548dd2dcb9', '${TASK_1}', 1,
        '${FINGERPRINT}', 'p0-placeholder', '${AT}'
      );
      INSERT INTO publishes VALUES (
        'd3b42849-8ecb-4b2e-b415-46a74ca53405', '${RENDER}', 'douyin',
        '${TASK_1}:v1', '内容由AI生成', NULL, NULL, 'confirmed', NULL,
        'p0-stub-receipt', NULL, '${AT}', '${AT}'
      );
      INSERT INTO aigc_posture VALUES (
        'b3c688cc-a272-b886-7c23-4d9309c79e25', 'full', 'required', '',
        'p0-script', '${AT}', NULL
      );
    `);
  } finally {
    sqlite.close();
  }
}

const dirs: string[] = [];
const dbs: Db[] = [];

function tempDir(): string {
  const dir = mkdtempSync(join(tmpdir(), "heron-migrate-"));
  dirs.push(dir);
  return dir;
}

async function makeTarget(): Promise<{ db: Db; tenantId: string }> {
  const db = openDb("memory");
  dbs.push(db);
  await migrate(db);
  await db.insert(tenants).values({
    id: TENANT_ID,
    name: "旧库",
    slug: TENANT_SLUG,
    suspendedAt: null,
    createdAt: AT,
  });
  return { db, tenantId: TENANT_ID };
}

afterAll(async () => {
  for (const db of dbs) await db.close();
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
});

describe("SQLite → Postgres 导入", () => {
  it("逐表搬完：ID、计数、JSON 字段与租户归属都守恒", async () => {
    const { db } = await makeTarget();
    const sourcePath = join(tempDir(), "heron.sqlite");
    createLegacyDb(sourcePath);

    const report = await importLegacySqlite({ db, sourcePath, tenantSlug: TENANT_SLUG });

    expect(report.tables).toEqual({
      settings: { source: 1, target: 1 },
      feed_sources: { source: 0, target: 0 },
      candidates: { source: 1, target: 1 },
      tasks: { source: 2, target: 2 },
      task_events: { source: 3, target: 3 },
      gate_results: { source: 2, target: 2 },
      renders: { source: 1, target: 1 },
      confirmations: { source: 1, target: 1 },
      publishes: { source: 1, target: 1 },
      aigc_posture: { source: 1, target: 1 },
    });

    const seen = await withTenant(db, TENANT_ID, async (t) => {
      const [candidate] = await t.db.select().from(candidates);
      const allTasks = await t.db.select().from(tasks);
      const events = await t.db.select().from(taskEvents).orderBy(taskEvents.id);
      const [render] = await t.db.select().from(renders);
      const gates = await t.db.select().from(gateResults);
      const [confirmation] = await t.db.select().from(confirmations);
      const [publish] = await t.db.select().from(publishes);
      const [setting] = await t.db.select().from(settings);
      return { candidate, allTasks, events, render, gates, confirmation, publish, setting };
    });

    expect(seen.candidate).toMatchObject({
      id: CANDIDATE,
      tenantId: TENANT_ID,
      title: "旧库候选",
      normKey: "旧库候选",
      status: "published",
    });
    expect(seen.allTasks.map((task) => [task.id, task.stage]).sort()).toEqual(
      [
        [TASK_1, "published"],
        [TASK_2, "failed"],
      ].sort(),
    );
    expect(seen.events.map((event) => event.reason)).toEqual(["认领人 p0-script", null, null]);
    expect(seen.render).toMatchObject({
      id: RENDER,
      fingerprint: FINGERPRINT,
      timings: [{ stage: "render", seconds: 1.5 }],
    });
    expect(seen.gates.find((gate) => gate.passed === false)?.rejections).toEqual([
      { ruleId: "contract.declaration", snippet: "无声明", why: "缺合规声明" },
    ]);
    expect(seen.confirmation?.id).toBe("ba158257-b2d4-4760-8bc4-ee548dd2dcb9");
    expect(seen.publish).toMatchObject({ renderId: RENDER, status: "confirmed" });
    expect(seen.setting).toMatchObject({ tenantId: TENANT_ID, key: "health.last_probe_at" });
  });

  it("自由文本的人落成合成 actor：能写进审计列，但永远登不进来", async () => {
    const { db } = await makeTarget();
    const sourcePath = join(tempDir(), "heron.sqlite");
    createLegacyDb(sourcePath);

    await importLegacySqlite({ db, sourcePath, tenantSlug: TENANT_SLUG });

    const seen = await withTenant(db, TENANT_ID, async (t) => {
      const actors = await t.db.select().from(users).where(like(users.email, "legacy+%"));
      const [task] = await t.db.select().from(tasks).where(eq(tasks.id, TASK_1));
      const [confirmation] = await t.db.select().from(confirmations);
      return { actors, task, confirmation };
    });

    expect(seen.actors).toHaveLength(2);
    const byName = new Map(seen.actors.map((actor) => [actor.displayName, actor]));
    const script = byName.get("旧库合成：p0-script");
    const placeholder = byName.get("旧库合成：p0-placeholder");
    expect(script).toBeDefined();
    expect(placeholder).toBeDefined();
    for (const actor of seen.actors) {
      expect(actor.tenantId).toBe(TENANT_ID);
      expect(actor.role).toBe("viewer");
      expect(actor.passwordHash.startsWith("!")).toBe(true);
      expect(verifyPassword("p0-placeholder", actor.passwordHash)).toBe(false);
    }
    expect(seen.task?.claimedBy).toBe(script?.id);
    expect(seen.confirmation?.confirmedBy).toBe(placeholder?.id);
  });

  it("目标租户已有业务行就拒绝，不半途写入", async () => {
    const { db } = await makeTarget();
    await withTenant(db, TENANT_ID, (t) =>
      t.db.insert(candidates).values({
        id: CANDIDATE,
        tenantId: TENANT_ID,
        title: "已有的行",
        normKey: "已有的行",
        firstSeenAt: AT,
        lastSeenAt: AT,
        createdAt: AT,
        updatedAt: AT,
      }),
    );
    const sourcePath = join(tempDir(), "heron.sqlite");
    createLegacyDb(sourcePath);

    await expect(importLegacySqlite({ db, sourcePath, tenantSlug: TENANT_SLUG })).rejects.toThrow(
      /已有数据/,
    );
    const count = await withTenant(db, TENANT_ID, (t) => t.db.select().from(tasks));
    expect(count).toHaveLength(0);
  });

  it("工作空间不存在时拒绝，不偷偷建一个", async () => {
    const { db } = await makeTarget();
    const sourcePath = join(tempDir(), "heron.sqlite");
    createLegacyDb(sourcePath);

    await expect(importLegacySqlite({ db, sourcePath, tenantSlug: "nope" })).rejects.toThrow(
      /没有这个工作空间/,
    );
  });
});

describe("旧库文件退场", () => {
  it("改名保留：目标已存在就拒绝，绝不覆盖", async () => {
    const dir = tempDir();
    const source = join(dir, "heron.sqlite");
    writeFileSync(source, "legacy-bytes");

    const retired = await retireLegacyDb(source);

    expect(retired).toBe(`${source}.pre-multi-tenant`);
    expect(existsSync(source)).toBe(false);
    expect(readFileSync(retired, "utf8")).toBe("legacy-bytes");

    writeFileSync(source, "second-import");
    await expect(retireLegacyDb(source)).rejects.toThrow(/已经存在/);
    expect(readFileSync(source, "utf8")).toBe("second-import");
  });
});
