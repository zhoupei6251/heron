import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { nowIso } from "@heron/db";
import {
  createTask,
  createTenant,
  migrate,
  openDb,
  renders,
  setRetentionDays,
  withTenant,
  type Db,
} from "@heron/db/pg";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { cleanupArtifacts } from "./cleanup";

describe("worker 保留清理", () => {
  let db: Db;
  let root: string;
  let tenantId: string;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    tenantId = randomUUID();
    await createTenant(db, { id: tenantId, name: "清理台", slug: "worker-cleanup" });
    root = mkdtempSync(join(tmpdir(), "heron-cleanup-"));
  });

  afterEach(async () => {
    await db.close();
    rmSync(root, { recursive: true, force: true });
  });

  const daysAgo = (days: number): string => nowIso(new Date(Date.now() - days * 86_400_000));

  /** 一个带目录与一版渲染的任务。.keep 是渲染失败留给人的现场。 */
  async function seedTask(owner: string, createdAt: string, keep = false): Promise<string> {
    return withTenant(db, owner, async (t) => {
      const task = await createTask(t, { capability: "news", candidateId: null });
      await t.db.insert(renders).values({
        id: randomUUID(),
        taskId: task.id,
        version: 1,
        pack: "demo",
        fingerprint: "fp-test",
        createdAt,
      });
      mkdirSync(join(root, task.id), { recursive: true });
      writeFileSync(join(root, task.id, "fake.mp4"), "x");
      if (keep) writeFileSync(join(root, task.id, ".keep"), "");
      return task.id;
    });
  }

  it("过期的删掉，.keep 与没到期的留着", async () => {
    await withTenant(db, tenantId, (t) => setRetentionDays(t, 1));
    const expired = await seedTask(tenantId, daysAgo(3));
    const protectedDir = await seedTask(tenantId, daysAgo(3), true);
    const fresh = await seedTask(tenantId, daysAgo(0));

    expect(await cleanupArtifacts(db, { root })).toEqual({ removed: 1, kept: 1 });
    expect(existsSync(join(root, expired))).toBe(false);
    expect(existsSync(join(root, protectedDir, ".keep"))).toBe(true);
    expect(existsSync(join(root, fresh, "fake.mp4"))).toBe(true);
  });

  it("同一任务重渲过：最后一版还在保留期内，目录不动", async () => {
    await withTenant(db, tenantId, (t) => setRetentionDays(t, 1));
    const taskId = await seedTask(tenantId, daysAgo(5));
    await withTenant(db, tenantId, (t) =>
      t.db.insert(renders).values({
        id: randomUUID(),
        taskId,
        version: 2,
        pack: "demo",
        fingerprint: "fp-v2",
        createdAt: daysAgo(0),
      }),
    );

    expect(await cleanupArtifacts(db, { root })).toEqual({ removed: 0, kept: 0 });
    expect(existsSync(join(root, taskId, "fake.mp4"))).toBe(true);
  });

  it("保留天数是每租户各自一档", async () => {
    const relaxed = randomUUID();
    await createTenant(db, { id: relaxed, name: "长保留台", slug: "worker-cleanup-long" });
    await withTenant(db, tenantId, (t) => setRetentionDays(t, 1));
    const strictTask = await seedTask(tenantId, daysAgo(3));
    const relaxedTask = await seedTask(relaxed, daysAgo(3));

    await cleanupArtifacts(db, { root });
    expect(existsSync(join(root, strictTask))).toBe(false);
    expect(existsSync(join(root, relaxedTask, "fake.mp4"))).toBe(true);
  });
});
