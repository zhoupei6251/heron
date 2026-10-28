import { randomUUID } from "node:crypto";

import { LEASE_TTL_SEC } from "@heron/db";
import {
  getProtoProject,
  createTenant,
  migrate,
  openDb,
  protoProjects,
  touchProtoLease,
  withTenant,
  type Db,
  type ProtoProjectRecord,
} from "@heron/db/pg";
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// 包根会加载 home.ts；拦住文件加载并关闭队列配置，不读本机 .env、不连任何服务。
vi.hoisted(() => {
  vi.spyOn(process, "loadEnvFile").mockImplementation(() => undefined);
  vi.stubEnv("REDIS_URL", "");
});

import { MAX_ATTEMPTS, OWNER } from "./config";
import { createMutex } from "./queue";
import { claimNextProto, recoverProtoLeases } from "./scheduler";

describe("原型调度的租约恢复", () => {
  let db: Db;
  let tenantId: string;
  let runExclusive: ReturnType<typeof createMutex>;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    tenantId = randomUUID();
    await createTenant(db, { id: tenantId, name: "租约测试台", slug: "lease-test" });
    runExclusive = createMutex();
    // 只改 Date，让 Date.now 的截止线与续租写入时间一起推进，不碰数据库的异步定时器。
    vi.useFakeTimers({ toFake: ["Date"] });
  });

  afterEach(async () => {
    vi.useRealTimers();
    await db.close();
  });

  afterAll(() => {
    vi.restoreAllMocks();
    vi.unstubAllEnvs();
  });

  async function seed(stage: ProtoProjectRecord["stage"], attempts = 0): Promise<string> {
    const id = randomUUID();
    const at = new Date().toISOString();
    await withTenant(db, tenantId, (t) =>
      t.db.insert(protoProjects).values({
        id,
        tenantId,
        title: "租约恢复",
        requirement: "一个测试页面",
        target: "web",
        theme: "cobalt-pro",
        stage,
        attempts,
        leaseOwner: "previous-worker",
        heartbeat: at,
        createdAt: at,
        updatedAt: at,
      }),
    );
    return id;
  }

  function expire(): void {
    vi.setSystemTime(Date.now() + LEASE_TTL_SEC * 1000 + 1);
  }

  function read(id: string) {
    return withTenant(db, tenantId, (t) => getProtoProject(t, id));
  }

  it.each(["scripting", "compiling"] as const)(
    "%s：重启时心跳仍活着，过期后普通调度退回 draft 并重新领到",
    async (stage) => {
      const id = await seed(stage);
      vi.setSystemTime(Date.now() + (LEASE_TTL_SEC * 1000) / 2);
      expect(await withTenant(db, tenantId, (t) => touchProtoLease(t, id, "previous-worker"))).toBe(
        true,
      );
      const live = await read(id);

      expect(await runExclusive(() => recoverProtoLeases(db))).toEqual({
        returned: [],
        failed: [],
      });
      expect(await runExclusive(() => claimNextProto(db))).toBeNull();
      expect(await read(id)).toEqual(live);

      expire();
      const claimed = await runExclusive(() => claimNextProto(db));
      expect(claimed).toMatchObject({
        tenantId,
        project: { id, stage: "draft", attempts: 1, leaseOwner: OWNER, errorReason: null },
      });
      expect(await read(id)).toEqual(claimed?.project);
      expect(await withTenant(db, tenantId, (t) => touchProtoLease(t, id, "previous-worker"))).toBe(
        false,
      );
      expect(await runExclusive(() => claimNextProto(db))).toBeNull();
    },
  );

  it("普通调度到重试上限判死并释放租约，不再领走", async () => {
    const id = await seed("compiling", MAX_ATTEMPTS - 1);
    expect(await runExclusive(() => recoverProtoLeases(db))).toEqual({ returned: [], failed: [] });
    expire();

    expect(await runExclusive(() => claimNextProto(db))).toBeNull();
    const failed = await read(id);
    expect(failed).toMatchObject({ stage: "failed", leaseOwner: null, heartbeat: null });
    expect(failed?.errorReason).toMatch(/租约连续过期 3 次.*最后停在 compiling/);
    expect(await runExclusive(() => claimNextProto(db))).toBeNull();
  });

  it("普通调度回收 approved 时计入重试，但仍领成交付而非重新生成", async () => {
    const id = await seed("approved");
    expect(await runExclusive(() => recoverProtoLeases(db))).toEqual({ returned: [], failed: [] });
    expire();

    expect(await runExclusive(() => claimNextProto(db))).toMatchObject({
      tenantId,
      project: { id, stage: "approved", attempts: 1, leaseOwner: OWNER },
    });
    expect(await read(id)).toMatchObject({ stage: "approved", attempts: 1 });
  });

  it.each(["review", "delivered", "failed"] as const)(
    "%s：即使残留过期租约，也绝不回收或重领",
    async (stage) => {
      const id = await seed(stage);
      const before = await read(id);
      expire();

      expect(await runExclusive(() => recoverProtoLeases(db))).toEqual({
        returned: [],
        failed: [],
      });
      expect(await runExclusive(() => claimNextProto(db))).toBeNull();
      expect(await read(id)).toEqual(before);
    },
  );

  it("回收 SELECT 在真实 admin 事务中执行 FOR UPDATE SKIP LOCKED", async () => {
    const id = await seed("scripting");
    expire();
    const statements: string[] = [];
    const transaction = db.transaction.bind(db);
    // 只记录事务内实际准备的 SQL，查询与事务仍由真实 PGlite 执行。
    const trace = vi.spyOn(db, "transaction").mockImplementation((fn, config) =>
      transaction(async (tx) => {
        const prepare = vi.spyOn(tx._.session, "prepareQuery");
        try {
          return await fn(tx);
        } finally {
          statements.push(...prepare.mock.calls.map(([query]) => query.sql));
          prepare.mockRestore();
        }
      }, config),
    );
    try {
      expect(await runExclusive(() => recoverProtoLeases(db))).toEqual({
        returned: [id],
        failed: [],
      });
      expect(statements[0]).toBe("SET LOCAL ROLE heron_owner");
      const selected = statements.filter((sql) =>
        /^select .* from "proto_projects" where /i.test(sql),
      );
      expect(selected).toHaveLength(1);
      expect(selected[0]).toMatch(/for update of "proto_projects" skip locked/i);
      expect(await read(id)).toMatchObject({
        stage: "draft",
        attempts: 1,
        leaseOwner: null,
        heartbeat: null,
      });
    } finally {
      trace.mockRestore();
    }
  });
});
