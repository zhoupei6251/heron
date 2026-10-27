import { randomUUID } from "node:crypto";

import type { GateVerdict, Role, Stage, Task } from "@heron/contracts";
import { hashPassword } from "@heron/domain";
import { eq, isNull, sql } from "drizzle-orm";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { sha256 } from "../hash";
import { nowIso } from "../time";
import { OWNER_ROLE, withAdmin } from "./admin";
import {
  advanceTask,
  aigcPosture,
  APP_ROLE,
  candidates,
  claimByHuman,
  claimNextTask,
  claimTaskById,
  confirmByHuman,
  confirmationMatches,
  createCandidate,
  createUser,
  createTask,
  findPublishByKey,
  getTask,
  getScript,
  hasPublishedTaskForCandidate,
  insertConfirmation,
  insertPosture,
  insertPublish,
  insertRender,
  listGateResults,
  markFailed,
  migrate,
  nextRenderVersion,
  releaseLease,
  openDb,
  publishes,
  recordUsage,
  setCandidateStatus,
  startProductionFromCandidate,
  scripts,
  sumUsage,
  taskEvents,
  tasks,
  tenants,
  updatePublish,
  upsertScript,
  usageRecords,
  withTenant,
  type Db,
  type TenantDb,
} from "./index";

/**
 * 旧 SQLite 仓储（../db.test.ts）的断言在异步层重立一遍：T4 换的是通道与驱动，不是规矩。
 * 对不上的两条写清去向，不留「看起来测过了」的账：
 *   · 「迁移建齐表」由 ./schema.test.ts 的三张清单比对承担——v3 的表集合与 v2 不同，照抄是假对照；
 *   · `news_fact_checks 至少两源`、`news_shots 复合外键` 随 news 外挂表推到计划 4：
 *     那两张表不在 v3 核心 schema 里，packages/db 这一层没有写它们的路径。
 *
 * 拓扑只有这一种写法，两条都是实测换来的：
 *   1. 建租户走 withAdmin（heron_app 连 tenants 行都插不进去），业务走 withTenant；
 *   2. 回调里只用回调传进来的那个句柄——pglite 只有一条连接且事务正占着它，
 *      这时再用外层 db 不是报错而是死锁，表现为测试超时。
 * 闸拒绝留下的痕（gate_results 与 stage=failed）写在调用方的事务里，所以 catch 必须在回调内：
 * 让错误穿出 withTenant 等于回滚，是测试自己把审计抹掉。
 */

const ISO_Z = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
const AT = "2026-10-22T00:00:00.000Z";
const TENANT_A = "00000000-0000-4000-8000-0000000000aa";
const TENANT_B = "00000000-0000-4000-8000-0000000000bb";
/** worker 领活时的进程身份，不是人（见 schema/tasks.ts 的 lease_owner 注释）。 */
const OWNER = "worker-test";
/**
 * 种下去的人带真哈希（domain 的 scrypt），T6 的登录路径才验得过它；
 * 合成 actor 走另一条形状——`!` 前缀，能进审计列但永远登不进来。
 * 哈希只算一次放在模块作用域：scryptSync 一次约 50–100ms，每个用例现算就是白烧半分钟。
 */
const PASSWORD_HASH = hashPassword("test-password-1234");
/** 状态机的直路：从 claimed 一路走到 published，每道闸都放行。 */
const TO_PUBLISHED: Stage[] = [
  "scripting",
  "factcheck",
  "rendering",
  "review",
  "approved",
  "publishing",
  "published",
];

interface Seed {
  readonly tenantId: string;
  readonly userId: string;
  readonly taskId: string;
  readonly candidateId: string;
  readonly owner: string;
}

/** pglite 把 execute 的原样结果包在 rows 上（见 ./schema.test.ts 的同形取法）。 */
function roleOf(result: unknown): string {
  return (result as { rows: { current_user: string }[] }).rows[0]?.current_user ?? "";
}

describe("pg 仓储层（异步 + 双通道）", () => {
  let db: Db;

  beforeEach(async () => {
    // 每个用例一个干净的库：claimNextTask 跨租户取「最早的一条」，
    // 共用一个库就会领到上一个用例剩下的任务，那断言的就不是本用例种下去的东西。
    db = openDb("memory");
    await migrate(db);
    await withAdmin(db, (a) =>
      a.db.insert(tenants).values([
        { id: TENANT_A, name: "A 台", slug: "a", suspendedAt: null, createdAt: AT },
        { id: TENANT_B, name: "B 台", slug: "b", suspendedAt: null, createdAt: AT },
      ]),
    );
  });

  afterEach(async () => {
    await db.close();
  });

  /** drizzle 把驱动错误包成 `Failed query: …`，SQLSTATE 原文在 cause 上，两段拼起来才认得出约束名。 */
  async function rejection(run: () => Promise<unknown>): Promise<string> {
    try {
      await run();
    } catch (e) {
      const err = e as { message: string; cause?: { message?: string } };
      return `${err.message} ${err.cause?.message ?? ""}`;
    }
    throw new Error("期望被数据库拒掉，结果写进去了");
  }

  /** 一条已认领、且被 worker 领到租约的任务：状态转移的前置状态。 */
  async function claimedTask(tenantId: string = TENANT_A): Promise<Seed> {
    const seeded = await withTenant(db, tenantId, async (t) => {
      const userId = await createUser(t, {
        email: "zhoupei@example.com",
        displayName: "周佩",
        passwordHash: PASSWORD_HASH,
        role: "reviewer",
      });
      const candidateId = await createCandidate(t, {
        title: "某市开通新航线",
        summary: "",
        heat: 1,
      });
      const task = await createTask(t, { capability: "news", candidateId });
      await claimByHuman(t, task.id, userId);
      return { userId, candidateId, taskId: task.id };
    });
    // 领租约是调度方的动作，没有租户上下文，所以走特权通道
    const leased = await withAdmin(db, (a) => claimNextTask(a, OWNER, 60));
    if (leased?.task.id !== seeded.taskId) {
      throw new Error(`种子任务没被领到（领到 ${leased?.task.id ?? "无"}），后面的断言无从谈起`);
    }
    if (leased.tenantId !== tenantId) {
      throw new Error(`领到的任务租户对不上：期望 ${tenantId}，实际 ${leased.tenantId}`);
    }
    return { ...seeded, tenantId, owner: OWNER };
  }

  /** 走一步，闸放行。 */
  function step(ref: Seed, to: Stage): Promise<Task> {
    return withTenant(db, ref.tenantId, (t) =>
      advanceTask(t, {
        taskId: ref.taskId,
        owner: ref.owner,
        to,
        evaluateGate: ({ gate }) => ({ gate, passed: true, rejections: [], note: "测试放行" }),
      }),
    );
  }

  async function runToPublished(ref: Seed): Promise<void> {
    for (const to of TO_PUBLISHED) await step(ref, to);
  }

  /** 让闸拒一步，返回界面要展开全文的那句文案；null 表示「压根没给评估器」。 */
  async function refuse(ref: Seed, to: Stage, verdict: GateVerdict | null): Promise<string> {
    return withTenant(db, ref.tenantId, async (t) => {
      try {
        await advanceTask(t, {
          taskId: ref.taskId,
          owner: ref.owner,
          to,
          evaluateGate: verdict ? () => verdict : undefined,
        });
      } catch (e) {
        return (e as Error).message;
      }
      throw new Error(`期望 ${to} 被闸拒掉，结果转移成功了`);
    });
  }

  /** 改姿态的人：set_by 是 uuid 外键，所以姿态测试也得先有真人。角色给 admin（RBAC 的最小档由 T7 接）。 */
  function human(tenantId: string, role: Role = "admin"): Promise<string> {
    return withTenant(db, tenantId, (t) =>
      createUser(t, {
        email: `posture-${randomUUID()}@example.com`,
        displayName: "姿态操作人",
        passwordHash: PASSWORD_HASH,
        role,
      }),
    );
  }

  function setPosture(
    t: TenantDb,
    renderMode: string,
    revertedAt: string | null,
    setBy: string,
  ): Promise<string> {
    return insertPosture(t, { renderMode, declaration: "required", note: "", setBy, revertedAt });
  }

  const readTask = (ref: Seed) => withTenant(db, ref.tenantId, (t) => getTask(t, ref.taskId));
  const readCandidate = (ref: Seed) =>
    withTenant(db, ref.tenantId, (t) =>
      t.db.select().from(candidates).where(eq(candidates.id, ref.candidateId)),
    );

  it("时间列全是 UTC ISO-8601 带 Z，不带 tenant_id 的子表在租户上下文里读得到", async () => {
    const ref = await claimedTask();
    const task = await readTask(ref);
    expect(task?.createdAt).toMatch(ISO_Z);
    expect(task?.claimedAt).toMatch(ISO_Z);

    // task_events 靠 applyRls 给它建的那条 EXISTS 策略继承租户：读得到才说明策略真接上了
    const events = await withTenant(db, TENANT_A, (t) =>
      t.db.select().from(taskEvents).where(eq(taskEvents.taskId, ref.taskId)),
    );
    expect(events.map((e) => e.toStage)).toEqual(["claimed"]);
    for (const row of events) expect(row.createdAt).toMatch(ISO_Z);
  });

  it("认领落下确认人与时间，候选转 selected", async () => {
    const ref = await claimedTask();
    const task = await readTask(ref);
    expect(task?.stage).toBe("claimed");
    expect(task?.claimedBy).toBe(ref.userId);
    expect(task?.claimedAt).toMatch(ISO_Z);
    expect(await readCandidate(ref)).toMatchObject([{ status: "selected" }]);
  });

  it("走完 published 后候选状态跟着变，去重闸据此拒绝重做同一条热点", async () => {
    const ref = await claimedTask();
    await runToPublished(ref);
    expect(await readTask(ref)).toMatchObject({ stage: "published" });
    expect(
      await withTenant(db, TENANT_A, (t) => hasPublishedTaskForCandidate(t, ref.candidateId)),
    ).toBe(true);
    expect(await readCandidate(ref)).toMatchObject([{ status: "published" }]);
  });

  it("租约不是自己的就不许推进：领活的进程与写状态的必须同源", async () => {
    const ref = await claimedTask();
    expect(
      await rejection(() =>
        withTenant(db, TENANT_A, (t) =>
          advanceTask(t, { taskId: ref.taskId, owner: "worker-别的进程", to: "scripting" }),
        ),
      ),
    ).toMatch(/租约不属于/);
    // 拒绝发生在任何写入之前，任务还停在 claimed
    expect(await readTask(ref)).toMatchObject({ stage: "claimed" });
  });

  it("review 不可领：放掉租约等人，确认之后才重新可领", async () => {
    const ref = await claimedTask();
    for (const to of ["scripting", "factcheck", "rendering", "review"] as Stage[])
      await step(ref, to);

    // worker 到 review 就主动放租约：留着等过期会被 recoverExpiredLeases 当成进程死了去计数，
    // 一条好好等人的任务三次心跳之后就成了 failed
    await withTenant(db, TENANT_A, (t) => releaseLease(t, ref.taskId, ref.owner));
    expect(await readTask(ref)).toMatchObject({ stage: "review", leaseOwner: null });
    // 没有租约的 review 任务依然领不到：人的闸只能由确认动作产出，worker 不许替人过
    expect(await withAdmin(db, (a) => claimNextTask(a, OWNER, 60))).toBeNull();

    const fingerprint = sha256("contact-sheet-review");
    await withTenant(db, TENANT_A, (t) =>
      insertRender(t, { taskId: ref.taskId, version: 1, pack: "demo", fingerprint }),
    );
    await withTenant(db, TENANT_A, (t) =>
      confirmByHuman(t, {
        taskId: ref.taskId,
        userId: ref.userId,
        contactSheetFingerprint: fingerprint,
      }),
    );

    // 人确认后 stage=approved，这时才重新进池子，且领到的活自带租户坐标
    const reclaimed = await withAdmin(db, (a) => claimNextTask(a, OWNER, 60));
    expect(reclaimed?.tenantId).toBe(TENANT_A);
    expect(reclaimed?.task).toMatchObject({ id: ref.taskId, stage: "approved" });
  });

  it("按 id 领活：领到的是点名的这一条，不是池子里最早的那条", async () => {
    const ref = await claimedTask();
    await withTenant(db, TENANT_A, (t) => releaseLease(t, ref.taskId, ref.owner));
    const laterId = await withTenant(db, TENANT_A, async (t) => {
      const userId = await createUser(t, {
        email: `late-${randomUUID()}@example.com`,
        displayName: "晚认领的人",
        passwordHash: PASSWORD_HASH,
        role: "reviewer",
      });
      const candidateId = await createCandidate(t, { title: "晚一条", summary: "", heat: 1 });
      const task = await createTask(t, { capability: "news", candidateId });
      await claimByHuman(t, task.id, userId);
      return task.id;
    });

    // 池子里更早的可领活是 ref：点名领 laterId 就必须拿到 laterId，否则队列的 job 等于白推
    const claimed = await withAdmin(db, (a) => claimTaskById(a, laterId, OWNER, 60));
    expect(claimed?.task).toMatchObject({ id: laterId, leaseOwner: OWNER });
    expect(claimed?.tenantId).toBe(TENANT_A);
    // 同一秒再点一次：有效租约在别人名下，领不到，也就不会渲染两遍
    expect(await withAdmin(db, (a) => claimTaskById(a, laterId, OWNER, 60))).toBeNull();
    // 别的 id 不被连带锁住：ref 没租约、stage 可领，仍然在池子里
    expect(await readTask(ref)).toMatchObject({ stage: "claimed", leaseOwner: null });
    // 队列里躺着一条库里已经没有的 job（任务被删），领不到就算完，不许抛
    expect(await withAdmin(db, (a) => claimTaskById(a, randomUUID(), OWNER, 60))).toBeNull();
  });

  it("按 id 也越不过人的闸：还没人认领的领不到", async () => {
    const unclaimed = await withTenant(db, TENANT_A, async (t) => {
      const cid = await createCandidate(t, { title: "还没人认领", summary: "", heat: 1 });
      return (await createTask(t, { capability: "news", candidateId: cid })).id;
    });
    // N5「认领才生产」：candidate 不是活，队列推得再快也不许 worker 替人认领
    expect(await withAdmin(db, (a) => claimTaskById(a, unclaimed, OWNER, 60))).toBeNull();
    expect(await withTenant(db, TENANT_A, (t) => getTask(t, unclaimed))).toMatchObject({
      stage: "candidate",
      leaseOwner: null,
    });
  });

  it("闸拒绝时结构化理由进 gate_results，拼好的文案进 error_reason", async () => {
    const ref = await claimedTask();
    await step(ref, "scripting");
    await step(ref, "factcheck");

    const message = await refuse(ref, "rendering", {
      gate: "contract",
      passed: false,
      rejections: [
        { ruleId: "contract.empty_voice", snippet: "第 3 镜", why: "正文为空，配音会停机" },
      ],
    });
    expect(message).toContain("contract.empty_voice");

    const failed = await readTask(ref);
    expect(failed?.stage).toBe("failed");
    expect(failed?.errorReason).toContain("正文为空");

    const rows = await withTenant(db, TENANT_A, (t) => listGateResults(t, ref.taskId));
    const contract = rows.find((r) => r.gate === "contract");
    expect(contract?.passed).toBe(false);
    // rejections 是 jsonb 列，读回来的是结构化原文而不是拼好的句子——界面展开全文与 oracle 对账都靠它
    expect(contract?.rejections).toEqual([
      { ruleId: "contract.empty_voice", snippet: "第 3 镜", why: "正文为空，配音会停机" },
    ]);
  });

  it("未提供的闸评估器不许静默放行", async () => {
    const ref = await claimedTask();
    await step(ref, "scripting");
    await step(ref, "factcheck");

    expect(await refuse(ref, "rendering", null)).toContain("不许静默放行");
    expect(await readTask(ref)).toMatchObject({ stage: "failed" });
    const rows = await withTenant(db, TENANT_A, (t) => listGateResults(t, ref.taskId));
    expect(rows.find((r) => r.gate === "contract")?.rejections[0]?.ruleId).toBe(
      "gate.no_evaluator",
    );
  });

  it("联络表指纹对不上就不算确认", async () => {
    const ref = await claimedTask();
    const fingerprint = sha256("contact-sheet-v1");
    await withTenant(db, TENANT_A, async (t) => {
      await insertRender(t, { taskId: ref.taskId, version: 1, pack: "demo", fingerprint });
      await insertConfirmation(t, {
        taskId: ref.taskId,
        renderVersion: 1,
        contactSheetFingerprint: fingerprint,
        confirmedBy: ref.userId,
      });
    });

    const matches = (renderVersion: number, sheet = fingerprint) =>
      withTenant(db, TENANT_A, (t) =>
        confirmationMatches(t, {
          taskId: ref.taskId,
          renderVersion,
          contactSheetFingerprint: sheet,
        }),
      );
    expect(await matches(1)).toBe(true);
    // 换版本、换指纹都必须重新确认：这条是发布闸的判据，少一样就会出现「人审 A、系统发 B」
    expect(await matches(2)).toBe(false);
    expect(await matches(1, sha256("contact-sheet-v2"))).toBe(false);
  });

  it("确认人必须是本租户的人：外键不受 RLS 管，归属得自己查", async () => {
    const ref = await claimedTask();
    // 同一个邮箱在 B 租户是另一个人（u_users_tenant_email 是租户内唯一），拿它的 id 来冒充 A 的确认人
    const foreignUserId = await withTenant(db, TENANT_B, (t) =>
      createUser(t, {
        email: "zhoupei@example.com",
        displayName: "隔壁的人",
        passwordHash: PASSWORD_HASH,
        role: "reviewer",
      }),
    );
    const fingerprint = sha256("contact-sheet-v1");
    await withTenant(db, TENANT_A, (t) =>
      insertRender(t, { taskId: ref.taskId, version: 1, pack: "demo", fingerprint }),
    );
    expect(
      await rejection(() =>
        withTenant(db, TENANT_A, (t) =>
          insertConfirmation(t, {
            taskId: ref.taskId,
            renderVersion: 1,
            contactSheetFingerprint: fingerprint,
            confirmedBy: foreignUserId,
          }),
        ),
      ),
    ).toMatch(/不属于本租户/);
  });

  it("同一任务同一版本只能有一行渲染，版本号由库里算", async () => {
    const ref = await claimedTask();
    await withTenant(db, TENANT_A, (t) =>
      insertRender(t, { taskId: ref.taskId, version: 1, pack: "demo", fingerprint: sha256("a") }),
    );
    expect(
      await rejection(() =>
        withTenant(db, TENANT_A, (t) =>
          insertRender(t, {
            taskId: ref.taskId,
            version: 1,
            pack: "demo",
            fingerprint: sha256("b"),
          }),
        ),
      ),
    ).toMatch(/u_renders_task_version/);
    expect(await withTenant(db, TENANT_A, (t) => nextRenderVersion(t, ref.taskId))).toBe(2);
  });

  it("稿子一任务一份：重写覆盖，别的租户读不到", async () => {
    const ref = await claimedTask();
    await withTenant(db, TENANT_A, async (t) => {
      await upsertScript(t, {
        taskId: ref.taskId,
        model: "旧模型",
        content: { title: "旧稿", scenes: [] },
      });
      await upsertScript(t, {
        taskId: ref.taskId,
        model: "新模型",
        content: { title: "新稿", scenes: [{ body: "正文" }] },
      });
    });

    expect(await withTenant(db, TENANT_A, (t) => getScript(t, ref.taskId))).toEqual({
      title: "新稿",
      scenes: [{ body: "正文" }],
    });
    // 子表的隔离靠 RLS 的 EXISTS 策略：按 taskId 查越界，得到的是 null 而不是别人的稿子
    expect(await withTenant(db, TENANT_B, (t) => getScript(t, ref.taskId))).toBeNull();
    expect(await withTenant(db, TENANT_A, (t) => t.db.select().from(scripts))).toHaveLength(1);
  });

  it("发布幂等键复用，凭证两列必须成对", async () => {
    const ref = await claimedTask();
    const renderId = await withTenant(db, TENANT_A, (t) =>
      insertRender(t, {
        taskId: ref.taskId,
        version: 1,
        pack: "demo",
        fingerprint: sha256("a"),
        timings: [{ stage: "截图", seconds: 3 }],
      }),
    );
    const key = `${ref.taskId}:v1`;
    await withTenant(db, TENANT_A, (t) =>
      insertPublish(t, {
        renderId,
        idempotencyKey: key,
        declaration: "内容由AI生成",
        credentialPath: "/root/.heron/state/douyin/state.json",
        credentialSha256: sha256("state"),
      }),
    );

    // 重试前先拿幂等键查「是不是其实已经发出去了」：回执丢失不等于没发出去
    const publishId = await withTenant(db, TENANT_A, (t) => findPublishByKey(t, key));
    expect(publishId).not.toBeNull();
    expect(
      await rejection(() =>
        withTenant(db, TENANT_A, (t) =>
          insertPublish(t, { renderId, idempotencyKey: key, declaration: "内容由AI生成" }),
        ),
      ),
    ).toMatch(/u_publishes_tenant_idempotency/);
    expect(
      await rejection(() =>
        withTenant(db, TENANT_A, (t) =>
          insertPublish(t, {
            renderId,
            idempotencyKey: `${ref.taskId}:v2`,
            declaration: "内容由AI生成",
            credentialPath: "/root/.heron/state/douyin/state.json",
          }),
        ),
      ),
    ).toMatch(/ck_publishes_credential_pair/);

    // 状态推进不许把已经落进去的 post_url 抹回 NULL：没传的字段不进 SET
    await withTenant(db, TENANT_A, (t) =>
      updatePublish(t, publishId as string, { status: "sent" }),
    );
    await withTenant(db, TENANT_A, (t) =>
      updatePublish(t, publishId as string, {
        status: "confirmed",
        postUrl: "https://www.douyin.com/video/1",
      }),
    );
    const rows = await withTenant(db, TENANT_A, (t) =>
      t.db
        .select()
        .from(publishes)
        .where(eq(publishes.id, publishId as string)),
    );
    expect(rows[0]?.status).toBe("confirmed");
    expect(rows[0]?.postUrl).toContain("douyin");
    expect(rows[0]?.verifiedAt).toBeNull();
    expect(rows[0]?.updatedAt).toMatch(ISO_Z);
  });

  it("最多一条生效中的姿态", async () => {
    const setBy = await human(TENANT_A);
    await withTenant(db, TENANT_A, (t) => setPosture(t, "full", null, setBy));
    expect(
      await rejection(() =>
        withTenant(db, TENANT_A, (t) => setPosture(t, "no-badge", null, setBy)),
      ),
    ).toMatch(/u_aigc_posture_single_current/);
  });

  it("置为已撤销后可以让位给下一条", async () => {
    const setBy = await human(TENANT_A);
    await withTenant(db, TENANT_A, (t) => setPosture(t, "full", null, setBy));
    await withTenant(db, TENANT_A, async (t) => {
      await t.db
        .update(aigcPosture)
        .set({ revertedAt: nowIso() })
        .where(eq(aigcPosture.tenantId, t.tenantId));
      await setPosture(t, "no-badge", null, setBy);
    });
    const open = await withTenant(db, TENANT_A, (t) =>
      t.db.select({ id: aigcPosture.id }).from(aigcPosture).where(isNull(aigcPosture.revertedAt)),
    );
    expect(open).toHaveLength(1);
  });

  it("改姿态的人也要验归属：外键不受 RLS 管", async () => {
    // B 租户的人 id 靠外键是插得进 A 的姿态行的——「谁绕过了角标」写成别人身上，审计就废了
    const foreign = await human(TENANT_B);
    expect(
      await rejection(() => withTenant(db, TENANT_A, (t) => setPosture(t, "draft", null, foreign))),
    ).toMatch(/不属于本租户/);
  });

  it("用量记在各自租户里，账单汇总走特权通道看全部", async () => {
    const refA = await claimedTask(TENANT_A);
    const refB = await claimedTask(TENANT_B);
    await withTenant(db, TENANT_A, (t) =>
      recordUsage(t, {
        kind: "render_seconds",
        quantity: "61.4",
        unit: "seconds",
        taskId: refA.taskId,
      }),
    );
    await withTenant(db, TENANT_B, (t) =>
      recordUsage(t, {
        kind: "render_seconds",
        quantity: "20",
        unit: "seconds",
        taskId: refB.taskId,
      }),
    );

    // 出账方不属于任何一个租户，所以只能从特权通道读；租户通道仍然只看得到自己
    const totals = await withAdmin(db, (a) => sumUsage(a));
    expect(totals.map((r) => [r.tenantId, r.quantity, r.rows])).toEqual(
      expect.arrayContaining([
        [TENANT_A, "61.4", 1],
        [TENANT_B, "20", 1],
      ]),
    );
    expect(totals).toHaveLength(2);
    expect(await withTenant(db, TENANT_A, (t) => t.db.select().from(usageRecords))).toHaveLength(1);
  });

  it("终态不被后来的异常改写：published 的任务不能被 markFailed 拖回失败", async () => {
    const ref = await claimedTask();
    await runToPublished(ref);
    await withTenant(db, TENANT_A, (t) => markFailed(t, ref.taskId, "重复的异常"));
    expect(await readTask(ref)).toMatchObject({ stage: "published", errorReason: null });
    // 台账也不许多出一条没发生过的转移：审计读的就是这一列
    const events = await withTenant(db, TENANT_A, (t) =>
      t.db.select().from(taskEvents).where(eq(taskEvents.taskId, ref.taskId)),
    );
    expect(events.filter((e) => e.toStage === "failed")).toHaveLength(0);
    // 认领一条 + 走到 published 一条，markFailed 没添上第九条
    expect(events).toHaveLength(TO_PUBLISHED.length + 1);
  });

  /** 候选 → 在途任务：认领人来自会话、候选转 selected、台账从 candidate 起笔。 */
  it("开始生产：候选变成一条已认领的任务，而不是先建任务再认领", async () => {
    const seeded = await withTenant(db, TENANT_A, async (t) => {
      const userId = await createUser(t, {
        email: "start@example.com",
        displayName: "周佩",
        passwordHash: PASSWORD_HASH,
        role: "reviewer",
      });
      const candidateId = await createCandidate(t, {
        title: "某市开通新航线",
        summary: "",
        heat: 1,
      });
      const task = await startProductionFromCandidate(t, candidateId, userId, "news");
      return { userId, candidateId, task };
    });
    expect(seeded.task).toMatchObject({
      stage: "claimed",
      claimedBy: seeded.userId,
      candidateId: seeded.candidateId,
    });
    const [candidate] = await withTenant(db, TENANT_A, (t) =>
      t.db.select().from(candidates).where(eq(candidates.id, seeded.candidateId)),
    );
    expect(candidate?.status).toBe("selected");
    const events = await withTenant(db, TENANT_A, (t) =>
      t.db.select().from(taskEvents).where(eq(taskEvents.taskId, seeded.task.id)),
    );
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ fromStage: "candidate", toStage: "claimed" });
  });

  it("同一条候选点两次，第二次被拒，在途任务只有一条", async () => {
    const { userId, candidateId } = await withTenant(db, TENANT_A, async (t) => {
      const userId = await createUser(t, {
        email: "twice@example.com",
        displayName: "周佩",
        passwordHash: PASSWORD_HASH,
        role: "reviewer",
      });
      const candidateId = await createCandidate(t, {
        title: "某市开通新航线",
        summary: "",
        heat: 1,
      });
      await startProductionFromCandidate(t, candidateId, userId, "news");
      return { userId, candidateId };
    });
    await expect(
      withTenant(db, TENANT_A, (t) => startProductionFromCandidate(t, candidateId, userId, "news")),
    ).rejects.toMatchObject({ code: "conflict" });
    const rows = await withTenant(db, TENANT_A, (t) =>
      t.db.select().from(tasks).where(eq(tasks.candidateId, candidateId)),
    );
    expect(rows).toHaveLength(1);
  });

  it("已发布的候选不能再开始生产", async () => {
    const { userId, candidateId } = await withTenant(db, TENANT_A, async (t) => {
      const userId = await createUser(t, {
        email: "done@example.com",
        displayName: "周佩",
        passwordHash: PASSWORD_HASH,
        role: "reviewer",
      });
      const candidateId = await createCandidate(t, {
        title: "某市开通新航线",
        summary: "",
        heat: 1,
      });
      await setCandidateStatus(t, candidateId, "published", AT);
      return { userId, candidateId };
    });
    await expect(
      withTenant(db, TENANT_A, (t) => startProductionFromCandidate(t, candidateId, userId, "news")),
    ).rejects.toMatchObject({ code: "conflict" });
  });

  /**
   * 双通道的降权确实发生了，而且是事务局部的（SET LOCAL 而不是 SET）。
   * 这两条不是锦上添花：只设 GUC 不降权时策略等于没写（超管无视 RLS），
   * 而 session 级降权会让还回池子的连接把下一个请求也变成特权请求——反方向泄漏更致命。
   */
  it("两条通道各自站定角色，退出事务就还原", async () => {
    const tenantRole = await withTenant(db, TENANT_A, (t) =>
      t.db.execute(sql`SELECT current_user`),
    );
    expect(roleOf(tenantRole)).toBe(APP_ROLE);
    const adminRole = await withAdmin(db, (a) => a.db.execute(sql`SELECT current_user`));
    expect(roleOf(adminRole)).toBe(OWNER_ROLE);
    expect(roleOf(await db.execute(sql`SELECT current_user`))).not.toBe(OWNER_ROLE);
  });
});
