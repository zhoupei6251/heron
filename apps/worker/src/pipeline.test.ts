import { randomUUID } from "node:crypto";

import {
  claimByHuman,
  confirmByHuman,
  createCandidate,
  createTask,
  createTenant,
  createUser,
  findPublishByKey,
  getTask,
  latestRender,
  listGateResults,
  migrate,
  openDb,
  withTenant,
  type Db,
} from "@heron/db/pg";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { P0_FAKE } from "./capabilities";
import { OWNER } from "./config";
import { walkTask, type WalkTrace } from "./pipeline";
import { createMutex } from "./queue";
import { claimNext } from "./scheduler";
import { runTask } from "./tick";

/**
 * worker 的流水线只验三件事：人的闸前必须停、人确认后必须接着跑完、指纹对不上必须留痕。
 * 领活走 scheduler（真实的特权通道），推进走 walkTask（真实的 pipeline），不绕开任何一层。
 * pglite 单连接：回调里只用传来的句柄，别再用外层 db（事务占着连接，表现为超时）。
 */
const PASSWORD_HASH = "!synthetic-test";

describe("worker 流水线", () => {
  let db: Db;
  let tenantId: string;
  let reviewerId: string;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    tenantId = randomUUID();
    await createTenant(db, { id: tenantId, name: "测试台", slug: "worker-test" });
    reviewerId = await withTenant(db, tenantId, (t) =>
      createUser(t, {
        email: "reviewer@example.com",
        displayName: "审核人",
        passwordHash: PASSWORD_HASH,
        role: "reviewer",
      }),
    );
  });

  afterEach(async () => {
    await db.close();
  });

  async function seedClaimedTask(): Promise<string> {
    return withTenant(db, tenantId, async (t) => {
      const candidateId = await createCandidate(t, {
        title: "某市开通新航线",
        summary: "",
        heat: 1,
      });
      const task = await createTask(t, { capability: P0_FAKE, candidateId });
      await claimByHuman(t, task.id, reviewerId);
      return task.id;
    });
  }

  async function runOnce(): Promise<WalkTrace> {
    const claimed = await claimNext(db);
    if (!claimed) throw new Error("预期能领到任务，结果池子是空的");
    expect(claimed.tenantId).toBe(tenantId);
    return withTenant(db, tenantId, (t) => walkTask(t, claimed.task, OWNER));
  }

  it("走到 review 就停：租约释放、渲染落库、不替人确认", async () => {
    const taskId = await seedClaimedTask();
    const trace = await runOnce();

    expect(trace.finalStage).toBe("review");
    const state = await withTenant(db, tenantId, (t) => getTask(t, taskId));
    expect(state).toMatchObject({ stage: "review", leaseOwner: null, errorReason: null });

    const render = await withTenant(db, tenantId, (t) => latestRender(t, taskId));
    expect(render?.version).toBe(1);

    // review 不在可领集合里：等人确认期间谁都不许把它领走
    expect(await claimNext(db)).toBeNull();
  });

  it("人确认之后接着跑完，发布单按幂等键落库", async () => {
    const taskId = await seedClaimedTask();
    await runOnce();

    const render = await withTenant(db, tenantId, (t) => latestRender(t, taskId));
    if (!render) throw new Error("review 阶段没有渲染版本，确认无从谈起");
    const approved = await withTenant(db, tenantId, (t) =>
      confirmByHuman(t, {
        taskId,
        userId: reviewerId,
        contactSheetFingerprint: render.fingerprint,
      }),
    );
    expect(approved.stage).toBe("approved");

    const trace = await runOnce();
    expect(trace.finalStage).toBe("published");
    expect(
      await withTenant(db, tenantId, (t) => findPublishByKey(t, `${taskId}:v1`)),
    ).not.toBeNull();
  });

  it("指纹对不上：确认被拒，任务 failed 且 human 闸痕留下", async () => {
    const taskId = await seedClaimedTask();
    await runOnce();

    const message = await withTenant(db, tenantId, async (t) => {
      try {
        // catch 必须在回调里：闸拒时 gate_results 与 stage=failed 已写在同一个事务里，
        // 让异常穿出 withTenant 就是回滚掉审计痕（与 ReviewService 同一条规矩）
        await confirmByHuman(t, {
          taskId,
          userId: reviewerId,
          contactSheetFingerprint: "fp-stale",
        });
      } catch (cause) {
        return cause instanceof Error ? cause.message : String(cause);
      }
      throw new Error("期望确认被拒，结果成功了");
    });

    expect(message).toContain("human.fingerprint_mismatch");
    expect(await withTenant(db, tenantId, (t) => getTask(t, taskId))).toMatchObject({
      stage: "failed",
    });
    const rows = await withTenant(db, tenantId, (t) => listGateResults(t, taskId));
    expect(rows.find((r) => r.gate === "human")?.passed).toBe(false);
  });

  it("队列按 id 投递：重复投递与空转都不多渲染一遍", async () => {
    const taskId = await seedClaimedTask();

    expect(await runTask(db, taskId)).toContain("review");
    const state = await withTenant(db, tenantId, (t) => getTask(t, taskId));
    expect(state).toMatchObject({ stage: "review", leaseOwner: null });
    expect(await withTenant(db, tenantId, (t) => latestRender(t, taskId))).toMatchObject({
      version: 1,
    });

    // 同一条 task 再投一次（队列至少一次语义）：已到人的闸前，领不到就是什么都不做
    expect(await runTask(db, taskId)).toBeNull();
    expect(await withTenant(db, tenantId, (t) => latestRender(t, taskId))).toMatchObject({
      version: 1,
    });

    // 队列里躺着一条库里没有的活（清库、换环境）：空转返回 null，不炸消费者
    expect(await runTask(db, randomUUID())).toBeNull();
  });
});

describe("同进程互斥", () => {
  const later = (ms: number): Promise<void> =>
    new Promise((resolve) => {
      setTimeout(resolve, ms);
    });

  it("队列 job 与兜底轮询排队跑，不叠出并发渲染", async () => {
    const runExclusive = createMutex();
    const events: string[] = [];
    const slow = runExclusive(async () => {
      events.push("slow:start");
      await later(20);
      events.push("slow:end");
    });
    const fast = runExclusive(async () => {
      await later(1);
      events.push("fast:start");
      events.push("fast:end");
    });

    await Promise.all([slow, fast]);
    expect(events).toEqual(["slow:start", "slow:end", "fast:start", "fast:end"]);
  });

  it("前一条抛错也不把后来的挡在门外", async () => {
    const runExclusive = createMutex();
    const first = runExclusive(async () => {
      await later(1);
      throw new Error("渲染失败");
    }).catch(() => "caught");
    const second = await runExclusive(async () => {
      await later(1);
      return "done";
    });

    expect(await first).toBe("caught");
    expect(second).toBe("done");
  });
});
