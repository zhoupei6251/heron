import { randomUUID } from "node:crypto";

import type { Stage, Task } from "@heron/contracts";
import { GateRefusalError, hashPassword } from "@heron/domain";
import { asc, eq } from "drizzle-orm";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { sha256 } from "../hash";
// 特权通道故意不在 ./index 的导出面上（见 pg/index.ts），包内测试按深路径 import
import { withAdmin } from "./admin";
import {
  type Db,
  advanceTask,
  claimByHuman,
  claimNextTask,
  confirmationMatches,
  type ConfirmInput,
  confirmByHuman,
  createCandidate,
  createTask,
  createTenant,
  createUser,
  disableUser,
  getTask,
  insertRender,
  latestRender,
  listGateResults,
  listReviewQueue,
  migrate,
  nextRenderVersion,
  openDb,
  recoverExpiredLeases,
  taskEvents,
  withTenant,
} from "./index";

/**
 * 人工确认（review → approved）是五道闸里唯一由**人**触发的那道。
 * 它与 worker 走的转移有两处本质区别，所以单独一个仓储函数而不是复用 advanceTask：
 *   1. 它不持租约——人的动作没有「两个进程同时做同一条」这个风险，硬套租约检查就会出现
 *      「worker 心跳一过期，人在界面上点确认被拒」这种没人能解释的失败；
 *   2. 闸的 verdict 由这次确认本身产出，不是调用方递进来一个 `passed: true`。
 * 第 2 条是规格书 §5「人审 A、系统发 B」的守门位置：确认记录必须绑着当时那一版联络表的指纹。
 */

const TENANT_A = "00000000-0000-4000-8000-0000000000aa";
const TENANT_B = "00000000-0000-4000-8000-0000000000bb";
const OWNER = "worker-test";
const PASSWORD_HASH = hashPassword("test-password-1234");
/** 认领之后 worker 一路推到 review 的中间步。 */
const TO_REVIEW: Stage[] = ["scripting", "factcheck", "rendering", "review"];
const SHEET_V1 = sha256("task=v1\npack=demo\n");
const SHEET_V2 = sha256("task=v2\npack=demo\n");

interface Seeded {
  tenantId: string;
  userId: string;
  taskId: string;
}

/** 一个 reviewer 和一条他已经认领的任务：所有用例的共同起点。 */
async function newTask(db: Db, tenantId = TENANT_A, title = "某市开通新航线"): Promise<Seeded> {
  const seeded = await withTenant(db, tenantId, async (t) => {
    // 邮箱在租户内唯一：同一个用例里种两条任务时必须换邮箱，撞键不是这里想测的东西
    const userId = await createUser(t, {
      email: `zhoupei-${randomUUID()}@example.com`,
      displayName: "周佩",
      passwordHash: PASSWORD_HASH,
      role: "reviewer",
    });
    // 候选按 norm_key 租户内唯一，标题同理要能种出第二条
    const candidateId = await createCandidate(t, { title, summary: "", heat: 1 });
    const task = await createTask(t, { capability: "news", candidateId });
    await claimByHuman(t, task.id, userId);
    return { userId, taskId: task.id };
  });
  return { ...seeded, tenantId };
}

/** worker 领走租约，把任务推到 review。 */
async function advanceToReview(db: Db, seeded: Seeded): Promise<Seeded> {
  await withAdmin(db, (a) => claimNextTask(a, OWNER, 600));
  for (const to of TO_REVIEW) {
    await withTenant(db, seeded.tenantId, (t) =>
      advanceTask(t, {
        taskId: seeded.taskId,
        owner: OWNER,
        to,
        evaluateGate: ({ gate }) => ({ gate, passed: true, rejections: [], note: "测试放行" }),
      }),
    );
  }
  return seeded;
}

/** 渲一版联络表：版本号由仓储算，指纹由调用方给。 */
async function render(db: Db, seeded: Seeded, fingerprint: string): Promise<void> {
  await withTenant(db, seeded.tenantId, async (t) => {
    await insertRender(t, {
      taskId: seeded.taskId,
      version: await nextRenderVersion(t, seeded.taskId),
      pack: "demo",
      fingerprint,
    });
  });
}

/** 一条 review 态、带着 v1 联络表的任务。 */
async function toReview(db: Db, tenantId = TENANT_A): Promise<Seeded> {
  const seeded = await advanceToReview(db, await newTask(db, tenantId));
  await render(db, seeded, SHEET_V1);
  return seeded;
}

/** 台账按 id 升序就是写入顺序（identity 主键），最后一行才是「最近一次发生了什么」。 */
async function lastEventReason(db: Db, seeded: Seeded): Promise<string | null> {
  const rows = await withTenant(db, seeded.tenantId, (t) =>
    t.db
      .select({ reason: taskEvents.reason })
      .from(taskEvents)
      .where(eq(taskEvents.taskId, seeded.taskId))
      .orderBy(asc(taskEvents.id)),
  );
  return rows.at(-1)?.reason ?? null;
}

/** 界面点确认时提交的参数：不给就是「确认当前这一版联络表、由当前会话的人发起」。 */
type ConfirmOverride = { userId?: string; contactSheetFingerprint?: string };

function confirmArgs(seeded: Seeded, input: ConfirmOverride): ConfirmInput {
  return {
    taskId: seeded.taskId,
    userId: input.userId ?? seeded.userId,
    contactSheetFingerprint: input.contactSheetFingerprint ?? SHEET_V1,
  };
}

function confirm(db: Db, seeded: Seeded, input: ConfirmOverride = {}): Promise<Task> {
  return withTenant(db, seeded.tenantId, (t) => confirmByHuman(t, confirmArgs(seeded, input)));
}

/**
 * 点确认、预期被闸拒。catch 必须写在 withTenant 回调里面：让 GateRefusalError 穿出事务，
 * rollback 会把刚写下的 gate_results 与 stage=failed 一起抹掉——那点痕是审计要求，不是日志。
 */
async function confirmRefused(db: Db, seeded: Seeded, input: ConfirmOverride = {}): Promise<Error> {
  return withTenant(db, seeded.tenantId, async (t) => {
    try {
      await confirmByHuman(t, confirmArgs(seeded, input));
    } catch (e) {
      return e as Error;
    }
    throw new Error("期望这次确认被闸拒掉，结果它进了 approved");
  });
}

describe("人工确认（人的闸）", () => {
  let db: Db;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    await createTenant(db, { id: TENANT_A, name: "A 台", slug: "a" });
    await createTenant(db, { id: TENANT_B, name: "B 台", slug: "b" });
  });

  afterEach(async () => {
    await db.close();
  });

  it("确认写下三样：谁、什么时候、当时那一版联络表的指纹", async () => {
    const seeded = await toReview(db);
    const task = await confirm(db, seeded);
    expect(task.stage).toBe("approved");

    await withTenant(db, TENANT_A, async (t) => {
      expect(
        await confirmationMatches(t, {
          taskId: seeded.taskId,
          renderVersion: 1,
          contactSheetFingerprint: SHEET_V1,
        }),
      ).toBe(true);
      const gates = await listGateResults(t, seeded.taskId);
      const human = gates.find((g) => g.gate === "human");
      expect(human?.passed).toBe(true);
      // note 里必须能看出确认的是哪一版：只有「放行了」而没有版本的闸记录没法对账
      expect(human?.note).toContain("v1");
    });
    // 「谁确认的」在审计上是「哪个岗位上的人确认的」，所以角色也进台账
    const reason = await lastEventReason(db, seeded);
    expect(reason).toContain("确认人 周佩");
    expect(reason).toContain("reviewer");
  });

  it("指纹不是当前那一版：不进 approved，闸记录留下为什么拒", async () => {
    const seeded = await toReview(db);
    expect(await confirmRefused(db, seeded, { contactSheetFingerprint: SHEET_V2 })).toBeInstanceOf(
      GateRefusalError,
    );
    await withTenant(db, TENANT_A, async (t) => {
      expect((await getTask(t, seeded.taskId))?.stage).toBe("failed");
      const gates = await listGateResults(t, seeded.taskId);
      const human = gates.find((g) => g.gate === "human");
      expect(human?.passed).toBe(false);
      expect(human?.rejections[0]?.ruleId).toBe("human.fingerprint_mismatch");
    });
  });

  it("重渲之后旧确认不作数：新指纹必须重新确认", async () => {
    const seeded = await toReview(db);
    await confirm(db, seeded);
    await render(db, seeded, SHEET_V2);
    await withTenant(db, TENANT_A, (t) =>
      expect(latestRender(t, seeded.taskId)).resolves.toMatchObject({
        version: 2,
        fingerprint: SHEET_V2,
      }),
    );
    // 已经 approved 的任务再确认一次，是状态机的事，不是闸的事
    await expect(confirm(db, seeded, { contactSheetFingerprint: SHEET_V2 })).rejects.toThrow(
      /非法状态转移/,
    );
  });

  it("没有渲染版本就没人确认过东西：闸拒绝并写清缺什么", async () => {
    const seeded = await advanceToReview(db, await newTask(db));
    expect((await confirmRefused(db, seeded)).message).toMatch(/没有可确认的渲染版本/);
    await withTenant(db, TENANT_A, async (t) => {
      const human = (await listGateResults(t, seeded.taskId)).find((g) => g.gate === "human");
      expect(human?.rejections[0]?.ruleId).toBe("human.render_missing");
    });
  });

  it("别人的 user id 冒充确认人：FK 不受 RLS 管，归属必须自己查", async () => {
    const seeded = await toReview(db);
    const foreign = await withTenant(db, TENANT_B, (t) =>
      createUser(t, {
        email: "other@example.com",
        displayName: "别台的人",
        passwordHash: PASSWORD_HASH,
        role: "owner",
      }),
    );
    await expect(confirm(db, seeded, { userId: foreign })).rejects.toThrow(/不属于本租户/);
    await withTenant(db, TENANT_A, async (t) => {
      expect((await getTask(t, seeded.taskId))?.stage).toBe("review");
    });
  });

  it("停用的人不能确认：token 还没过期也不作数", async () => {
    const seeded = await toReview(db);
    await withTenant(db, TENANT_A, (t) => disableUser(t, seeded.userId));
    await expect(confirm(db, seeded)).rejects.toThrow(/不属于本租户/);
  });

  it("确认不看租约：worker 心跳断了，人照样能点", async () => {
    const seeded = await toReview(db);
    const released = await withAdmin(db, (a) =>
      recoverExpiredLeases(a, { leaseTtlSec: -1, maxAttempts: 99 }),
    );
    expect(released.returned).toContain(seeded.taskId);
    // 同一条任务上 worker 的转移仍要租约，人的转移不要——两条断言放一起才说明区别是真的
    await expect(
      withTenant(db, TENANT_A, (t) =>
        advanceTask(t, { taskId: seeded.taskId, owner: OWNER, to: "approved" }),
      ),
    ).rejects.toThrow(/租约不属于/);
    expect((await confirm(db, seeded)).stage).toBe("approved");
  });

  it("刚认领就点确认：状态机先挡，闸都轮不到", async () => {
    const seeded = await newTask(db);
    await expect(confirm(db, seeded)).rejects.toThrow(/非法状态转移/);
  });
});

describe("待审队列（reviewer 的视野）", () => {
  let db: Db;

  beforeEach(async () => {
    db = openDb("memory");
    await migrate(db);
    await createTenant(db, { id: TENANT_A, name: "A 台", slug: "a" });
    await createTenant(db, { id: TENANT_B, name: "B 台", slug: "b" });
  });

  afterEach(async () => {
    await db.close();
  });

  it("只列 review 态的任务，带标题与当前渲染的指纹", async () => {
    const seeded = await toReview(db);
    await newTask(db, TENANT_A, "另一条不该进队列的候选"); // claimed 态
    const queue = await withTenant(db, TENANT_A, (t) => listReviewQueue(t));
    expect(queue).toHaveLength(1);
    expect(queue[0]).toMatchObject({
      taskId: seeded.taskId,
      title: "某市开通新航线",
      renderVersion: 1,
      contactSheetFingerprint: SHEET_V1,
    });
  });

  it("没有渲染的任务也在队列里，渲染字段是 null：界面要显示「无可确认版本」而不是少一行", async () => {
    const seeded = await advanceToReview(db, await newTask(db));
    const queue = await withTenant(db, TENANT_A, (t) => listReviewQueue(t));
    expect(queue).toHaveLength(1);
    expect(queue[0]?.taskId).toBe(seeded.taskId);
    expect(queue[0]?.renderVersion).toBeNull();
    expect(queue[0]?.contactSheetFingerprint).toBeNull();
  });

  it("重渲之后队列给的是新指纹：reviewer 看到的就是将要发布的那一版", async () => {
    const seeded = await toReview(db);
    await render(db, seeded, SHEET_V2);
    const queue = await withTenant(db, TENANT_A, (t) => listReviewQueue(t));
    expect(queue[0]).toMatchObject({ renderVersion: 2, contactSheetFingerprint: SHEET_V2 });
  });

  it("别的租户在审的任务不出现在我的队列里", async () => {
    await toReview(db);
    await toReview(db, TENANT_B);
    const queue = await withTenant(db, TENANT_A, (t) => listReviewQueue(t));
    expect(queue).toHaveLength(1);
    expect(queue[0]?.title).toBe("某市开通新航线");
  });
});
