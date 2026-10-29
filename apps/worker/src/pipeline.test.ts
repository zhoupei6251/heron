import { randomUUID } from "node:crypto";

import {
  claimByHuman,
  confirmByHuman,
  createCandidate,
  createTask,
  createTenant,
  createUser,
  findPublishByKey,
  getArtifactForTask,
  getScript,
  getTask,
  insertPublish,
  insertRender,
  latestRender,
  listGateResults,
  migrate,
  openDb,
  setModelSettings,
  usageRecords,
  withTenant,
  type Db,
} from "@heron/db/pg";
import { DECLARATION_TEXT } from "@heron/domain";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { NEWS_CAPABILITY } from "@heron/capability-news";

import { P0_FAKE } from "./capabilities";
import { OWNER } from "./config";
import type { ModelConfig } from "./model";
import { walkTask, type WalkDeps, type WalkTrace } from "./pipeline";
import { createMutex } from "./queue";
import { claimNext } from "./scheduler";
import type { ScriptDeps } from "./scripting";
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

  async function seedClaimedTask(capability: string = P0_FAKE): Promise<string> {
    return withTenant(db, tenantId, async (t) => {
      const candidateId = await createCandidate(t, {
        title: "某市开通新航线",
        summary: "",
        heat: 1,
      });
      const task = await createTask(t, { capability, candidateId });
      await claimByHuman(t, task.id, reviewerId);
      return task.id;
    });
  }

  async function runOnce(deps: WalkDeps = {}): Promise<WalkTrace> {
    const claimed = await claimNext(db);
    if (!claimed) throw new Error("预期能领到任务，结果池子是空的");
    expect(claimed.tenantId).toBe(tenantId);
    return withTenant(db, tenantId, (t) => walkTask(t, claimed.task, OWNER, deps));
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

  it("配了对象存储就搬到桶里：key 与本地路径一起落库，按租户/任务/版本布局", async () => {
    const taskId = await seedClaimedTask();
    const puts: string[] = [];
    await runOnce({
      store: {
        driver: "qiniu",
        put: (key) => {
          puts.push(key);
          return Promise.resolve({ key, bytes: 1 });
        },
        url: () => null,
        remove: () => Promise.resolve(),
      },
    });

    const mp4 = await withTenant(db, tenantId, (t) => getArtifactForTask(t, taskId, "mp4"));
    const sheet = await withTenant(db, tenantId, (t) =>
      getArtifactForTask(t, taskId, "contact-sheet"),
    );
    expect(puts).toEqual([
      `tenants/${tenantId}/tasks/${taskId}/v1/mp4.mp4`,
      `tenants/${tenantId}/tasks/${taskId}/v1/contact-sheet.png`,
    ]);
    expect(mp4.key).toBe(puts[0]);
    expect(sheet.key).toBe(puts[1]);
    // 本地路径不许被 key 顶掉：诊断仍然要能在渲染机上看现场
    expect(mp4.path).toContain("fake.mp4");
  });

  it("上传失败就不落库：这一版不存在，不静默降级成「只有本地有」", async () => {
    const taskId = await seedClaimedTask();
    await expect(
      runOnce({
        store: {
          driver: "qiniu",
          put: () => Promise.reject(new Error("上传被拒：401")),
          url: () => null,
          remove: () => Promise.resolve(),
        },
      }),
    ).rejects.toThrow(/上传被拒/);
    expect(await withTenant(db, tenantId, (t) => latestRender(t, taskId))).toBeNull();
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
    // 账本各记一次：渲染记秒数（真花的时间），发布记条数（额度数的就是这个）
    const usage = await withTenant(db, tenantId, (t) =>
      t.db.select({ kind: usageRecords.kind, quantity: usageRecords.quantity }).from(usageRecords),
    );
    expect(usage).toEqual(
      expect.arrayContaining([
        { kind: "render_seconds", quantity: "1" },
        { kind: "publish", quantity: "1" },
      ]),
    );
  });

  it("本月发布满额：额度闸在幂等键之后拦下，任务 failed 且原因点名哪一格", async () => {
    const taskId = await seedClaimedTask();
    await runOnce();
    const render = await withTenant(db, tenantId, (t) => latestRender(t, taskId));
    if (!render) throw new Error("review 阶段没有渲染版本，确认无从谈起");
    await withTenant(db, tenantId, (t) =>
      confirmByHuman(t, {
        taskId,
        userId: reviewerId,
        contactSheetFingerprint: render.fingerprint,
      }),
    );
    // 免费档每月 30 条发布：填满 30 条本月记录（额度按 >= 判，正好满额就是满）
    await withTenant(db, tenantId, async (t) => {
      for (let i = 0; i < 30; i += 1) {
        const filler = await insertRender(t, {
          taskId,
          version: 2 + i,
          pack: "demo",
          fingerprint: `filler-${String(i)}`,
        });
        await insertPublish(t, {
          renderId: filler,
          idempotencyKey: `filler-${String(i)}`,
          declaration: DECLARATION_TEXT,
        });
      }
    });

    const result = await runTask(db, taskId);
    expect(result).toContain("本月发布已达该档上限（30/30");
    expect(result).toContain("平台台");
    // 额度拦下是业务结论：原因里不该出现「worker 异常」和堆栈
    expect(result).not.toContain("worker 异常");
    expect(await withTenant(db, tenantId, (t) => getTask(t, taskId))).toMatchObject({
      stage: "failed",
    });
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

  const STUB_MODEL = { baseUrl: "http://127.0.0.1:1/v1", apiKey: "", model: "stub-model" };

  /** 替掉真模型，其余解析、落库、记账照走真实路径。 */
  function stubChat(script: unknown): ScriptDeps {
    return {
      modelConfig: STUB_MODEL,
      chat: () =>
        Promise.resolve({
          content: JSON.stringify(script),
          tokensIn: 12,
          tokensOut: 34,
          model: STUB_MODEL.model,
        }),
    };
  }

  async function runOnceCaught(deps: ScriptDeps): Promise<string> {
    const claimed = await claimNext(db);
    if (!claimed) throw new Error("预期能领到任务，结果池子是空的");
    // catch 在回调里：闸拒时 gate_results 与 stage=failed 已写在同一个事务里，
    // 让异常穿出 withTenant 就是回滚掉审计痕（与 tick.ts 同一条规矩）
    return withTenant(db, tenantId, async (t) => {
      try {
        await walkTask(t, claimed.task, OWNER, deps);
      } catch (cause) {
        return cause instanceof Error ? cause.message : String(cause);
      }
      throw new Error("期望被闸拒绝，结果走完了");
    });
  }

  it("news 写稿：模型产出落库并记 token，随后照常走到 review", async () => {
    const taskId = await seedClaimedTask(NEWS_CAPABILITY);
    const script = {
      title: "某市开通新航线",
      scenes: [
        { body: "首航航班今天上午起飞。", onscreen: "首航｜今天起飞" },
        { body: "航线每周三班。" },
      ],
    };
    const trace = await runOnce(stubChat(script));

    expect(trace.finalStage).toBe("review");
    expect(trace.steps).toContain("scripting(stub-model)");
    expect(await withTenant(db, tenantId, (t) => getScript(t, taskId))).toEqual(script);

    const usage = await withTenant(db, tenantId, (t) =>
      t.db.select({ kind: usageRecords.kind, quantity: usageRecords.quantity }).from(usageRecords),
    );
    expect(usage).toEqual(
      expect.arrayContaining([
        { kind: "llm_tokens_in", quantity: "12" },
        { kind: "llm_tokens_out", quantity: "34" },
      ]),
    );
  });

  it("模型少一镜：契约闸在 factcheck→rendering 拒绝，原因点名缺什么", async () => {
    const taskId = await seedClaimedTask(NEWS_CAPABILITY);
    const message = await runOnceCaught(
      stubChat({ title: "只有一镜", scenes: [{ body: "正文" }] }),
    );

    expect(message).toContain("contract.script_shape");
    expect(message).toContain("scenes");
    expect(await withTenant(db, tenantId, (t) => getTask(t, taskId))).toMatchObject({
      stage: "failed",
    });
    const gates = await withTenant(db, tenantId, (t) => listGateResults(t, taskId));
    expect(gates.find((gate) => gate.gate === "contract")).toMatchObject({ passed: false });
  });

  it("页面配的模型就是这次调用用的模型：密钥留空时回落到环境变量", async () => {
    await seedClaimedTask(NEWS_CAPABILITY);
    await withTenant(db, tenantId, (t) =>
      setModelSettings(t, {
        baseUrl: "https://api.qnaigc.com/v1",
        model: "moonshotai/kimi-k3-free",
      }),
    );
    const seen: ModelConfig[] = [];
    const deps: ScriptDeps = {
      // 不注入 modelConfig：让配置真的走「读租户设置 → 回落环境变量」这条路
      chat: (config) => {
        seen.push(config);
        return Promise.resolve({
          content: JSON.stringify({
            title: "某市开通新航线",
            scenes: [{ body: "首航今天起飞。" }, { body: "每周三班。" }],
          }),
          tokensIn: null,
          tokensOut: null,
          model: config.model,
        });
      },
    };
    const previous = process.env.HERON_MODEL_API_KEY;
    process.env.HERON_MODEL_API_KEY = "env-key";
    try {
      const trace = await runOnce(deps);
      expect(trace.finalStage).toBe("review");
      expect(trace.steps).toContain("scripting(moonshotai/kimi-k3-free)");
      expect(seen[0]).toEqual({
        baseUrl: "https://api.qnaigc.com/v1",
        apiKey: "env-key",
        model: "moonshotai/kimi-k3-free",
      });
    } finally {
      if (previous === undefined) delete process.env.HERON_MODEL_API_KEY;
      else process.env.HERON_MODEL_API_KEY = previous;
    }
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
