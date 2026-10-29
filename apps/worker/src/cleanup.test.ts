import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { nowIso } from "@heron/db";
import {
  createPlatformOperator,
  createTask,
  createTenant,
  migrate,
  openDb,
  renders,
  setRetentionDays,
  withTenant,
  writePlatformSettings,
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
  async function seedTask(
    owner: string,
    createdAt: string,
    keep = false,
    mp4Key: string | null = null,
  ): Promise<string> {
    return withTenant(db, owner, async (t) => {
      const task = await createTask(t, { capability: "news", candidateId: null });
      await t.db.insert(renders).values({
        id: randomUUID(),
        taskId: task.id,
        version: 1,
        pack: "demo",
        fingerprint: "fp-test",
        mp4Key,
        createdAt,
      });
      mkdirSync(join(root, task.id), { recursive: true });
      writeFileSync(join(root, task.id, "fake.mp4"), "x");
      if (keep) writeFileSync(join(root, task.id, ".keep"), "");
      return task.id;
    });
  }

  /** 记下删了哪些 key；failOn 里的 key 会抛，用来验「远端失败也照样删本地」。 */
  function fakeStore(failOn: string[] = []) {
    const removedKeys: string[] = [];
    return {
      removedKeys,
      store: {
        driver: "qiniu" as const,
        put: () => Promise.reject(new Error("清理不写对象")),
        url: () => null,
        remove: (key: string) => {
          if (failOn.includes(key)) return Promise.reject(new Error("远端 500"));
          removedKeys.push(key);
          return Promise.resolve();
        },
      },
    };
  }

  it("过期的删掉，.keep 与没到期的留着", async () => {
    await withTenant(db, tenantId, (t) => setRetentionDays(t, 1));
    const expired = await seedTask(tenantId, daysAgo(3));
    const protectedDir = await seedTask(tenantId, daysAgo(3), true);
    const fresh = await seedTask(tenantId, daysAgo(0));

    expect(await cleanupArtifacts(db, { root })).toEqual({
      removed: 1,
      kept: 1,
      objectsRemoved: 0,
      objectsFailed: 0,
    });
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

    expect(await cleanupArtifacts(db, { root })).toEqual({
      removed: 0,
      kept: 0,
      objectsRemoved: 0,
      objectsFailed: 0,
    });
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

  it("租户没设保留天数时用平台默认值，租户设了就以租户的为准", async () => {
    const operator = await createPlatformOperator(db, {
      username: "cleanup-op",
      displayName: "Ops",
      passwordHash: "unusable:test",
    });
    // 平台默认 1 天：没设过的台 3 天前的成片就该清掉
    await writePlatformSettings(db, operator.id, { default_retention_days: "1" });
    const fromPlatform = await seedTask(tenantId, daysAgo(3));
    await cleanupArtifacts(db, { root });
    expect(existsSync(join(root, fromPlatform))).toBe(false);

    // 自己把保留天数调长的那台不受平台默认值影响
    await withTenant(db, tenantId, (t) => setRetentionDays(t, 7));
    const fromTenant = await seedTask(tenantId, daysAgo(3));
    await cleanupArtifacts(db, { root });
    expect(existsSync(join(root, fromTenant, "fake.mp4"))).toBe(true);
  });

  it("过期任务的桶内对象按 renders 的 key 删，远端先删本地后删", async () => {
    await withTenant(db, tenantId, (t) => setRetentionDays(t, 1));
    const taskId = await seedTask(tenantId, daysAgo(3), false, `tenants/${tenantId}/v1/mp4.mp4`);
    const { removedKeys, store } = fakeStore();

    expect(await cleanupArtifacts(db, { root, store })).toMatchObject({
      removed: 1,
      objectsRemoved: 1,
      objectsFailed: 0,
    });
    expect(removedKeys).toEqual([`tenants/${tenantId}/v1/mp4.mp4`]);
    expect(existsSync(join(root, taskId))).toBe(false);
  });

  it("远端删失败只计数，本地照样删（下一轮同一 key 还会重试）", async () => {
    await withTenant(db, tenantId, (t) => setRetentionDays(t, 1));
    const key = `tenants/${tenantId}/v9/mp4.mp4`;
    const taskId = await seedTask(tenantId, daysAgo(3), false, key);
    const { store } = fakeStore([key]);

    expect(await cleanupArtifacts(db, { root, store })).toMatchObject({
      removed: 1,
      objectsRemoved: 0,
      objectsFailed: 1,
    });
    expect(existsSync(join(root, taskId))).toBe(false);
    // render 行还在：这就是下一轮能再拿到同一个 key 的原因
    expect(await cleanupArtifacts(db, { root, store })).toMatchObject({ objectsFailed: 1 });
  });

  it(".keep 的任务连桶也不动：现场要留就留全套", async () => {
    await withTenant(db, tenantId, (t) => setRetentionDays(t, 1));
    const key = `tenants/${tenantId}/v1/mp4.mp4`;
    await seedTask(tenantId, daysAgo(3), true, key);
    const { removedKeys, store } = fakeStore();

    expect(await cleanupArtifacts(db, { root, store })).toMatchObject({
      removed: 0,
      kept: 1,
      objectsRemoved: 0,
    });
    expect(removedKeys).toEqual([]);
  });
});
