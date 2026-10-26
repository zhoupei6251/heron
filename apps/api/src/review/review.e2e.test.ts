import type { INestApplication } from "@nestjs/common";
import { Test } from "@nestjs/testing";

import {
  type Db,
  claimByHuman,
  confirmationMatches,
  createCandidate,
  createTask,
  createTenant,
  createUser,
  getTask,
  insertRender,
  listGateResults,
  listReviewQueue,
  listTasks,
  migrate,
  nextRenderVersion,
  openDb,
  tasks,
  withTenant,
  listCandidates,
  setTenantSuspended,
  updateMember,
} from "@heron/db/pg";
import { hashPassword } from "@heron/domain";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { AuthService } from "../auth/auth.service";

import { AppModule } from "../app.module";
import { SESSION_COOKIE } from "../config/runtime";
import { PG_DB } from "../db/pg";
import { TaskQueueService, QUEUE } from "../redis/redis.module";
import { applyTransport } from "../transport";

/**
 * 人工确认在真 socket 上的往返：守卫 → 控制器 → 服务 → pg 仓储 → 回到响应。
 * 这些不是任何一个函数的性质，而是它们串起来之后的性质——单测各自绿了也可能接错线。
 *
 * 两条必须的红线（计划 T7-6）：
 *   1. viewer 点确认得到 403，且文案说得出缺哪个角色；
 *   2. 提交的指纹不是当前那一版时，任务进不了 approved，但闸记录必须留下为什么拒。
 *
 * 库用 pglite（与全案非 T11 测试同口径）：同一份迁移与 RLS 策略，但没有真并发。
 */

const SECRET = "review-e2e-secret-not-a-real-key-32";
const PASSWORD = "correct-horse-battery";
const REVIEWER = "reviewer@example.com";
const VIEWER = "viewer@example.com";
const SHEET_FP = "fp-contact-sheet-v1";
const STALE_FP = "fp-contact-sheet-stale";

const originalSecret = process.env.HERON_JWT_SECRET;

async function json(res: Response): Promise<Record<string, unknown>> {
  return (await res.json()) as Record<string, unknown>;
}

describe("人工确认（真 socket）", () => {
  let db: Db;
  let app: INestApplication;
  let base: string;
  let tenantSeq = 0;

  function get(path: string, token?: string): Promise<Response> {
    return fetch(`${base}/api${path}`, {
      headers: token ? { cookie: `${SESSION_COOKIE}=${token}` } : {},
    });
  }

  function post(path: string, token: string, body: unknown): Promise<Response> {
    return fetch(`${base}/api${path}`, {
      method: "POST",
      headers: { "content-type": "application/json", cookie: `${SESSION_COOKIE}=${token}` },
      body: JSON.stringify(body),
    });
  }

  /** 登录成功返回 cookie 里的 token；失败直接抛，免得后续断言拿着空串去查一个假问题。 */
  async function login(slug: string, email: string): Promise<string> {
    const res = await fetch(`${base}/api/auth/login`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ tenant: slug, email, password: PASSWORD }),
    });
    if (!res.ok) throw new Error(`登录失败：${res.status} ${await res.text()}`);
    const raw = res.headers.getSetCookie()[0] ?? "";
    const pair = raw.split(";")[0] ?? "";
    if (!pair.startsWith(`${SESSION_COOKIE}=`)) throw new Error("登录成功但没拿到会话 cookie");
    return pair.slice(SESSION_COOKIE.length + 1);
  }

  /** 每个用例一个全新租户：夹具互不干扰，也让下面那条不带 WHERE 的夹具 UPDATE 只命中一条任务。 */
  async function newTenant(): Promise<{ tenantId: string; slug: string; reviewerId: string }> {
    tenantSeq += 1;
    const slug = `review-${tenantSeq}`;
    const tenantId = await createTenant(db, { name: `审片租户 ${tenantSeq}`, slug });
    const reviewerId = await withTenant(db, tenantId, async (t) => {
      const reviewer = await createUser(t, {
        email: REVIEWER,
        displayName: "审片人",
        passwordHash: hashPassword(PASSWORD),
        role: "reviewer",
      });
      await createUser(t, {
        email: VIEWER,
        displayName: "旁观者",
        passwordHash: hashPassword(PASSWORD),
      });
      return reviewer;
    });
    return { tenantId, slug, reviewerId };
  }

  /**
   * 一条 review 态、带着一版联络表的任务。
   * 从 claimed 推到 review 需要租约，而租约只由跨租户调度方（AdminDb，api 侧拿不到）发放——
   * 推进路径本身由 packages/db 的 tasks.test.ts 覆盖，这条 e2e 要验的是确认的接线，
   * 所以夹具直接把状态摆到 review。租户是新建的，那条 UPDATE 只会命中这一条任务；
   * 数量对不上就说明这个前提没了，宁可红在夹具上。
   */
  async function seedReviewTask(
    tenantId: string,
    reviewerId: string,
    title: string,
  ): Promise<string> {
    return withTenant(db, tenantId, async (t) => {
      const candidateId = await createCandidate(t, { title, summary: "", heat: 1 });
      const task = await createTask(t, { capability: "news", candidateId });
      await claimByHuman(t, task.id, reviewerId);
      const only = await listTasks(t);
      if (only.length !== 1) {
        throw new Error(`夹具前提不成立：新租户里应有 1 条任务，实际 ${only.length}`);
      }
      await t.db.update(tasks).set({ stage: "review" });
      await insertRender(t, {
        taskId: task.id,
        version: await nextRenderVersion(t, task.id),
        pack: "demo",
        fingerprint: SHEET_FP,
      });
      return task.id;
    });
  }

  beforeAll(async () => {
    process.env.HERON_JWT_SECRET = SECRET;
    delete process.env.HERON_COOKIE_SECURE;

    db = openDb("memory");
    await migrate(db);

    const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
      .overrideProvider(PG_DB)
      .useValue(db)
      // 队列在测试里恒为 null：e2e 只管「有没有投、投的是哪个 id」，不该真去连某个 Redis
      // （开发机上 REDIS_URL 很可能是配着的，测试投一条就是一条外溢副作用）。
      .overrideProvider(QUEUE)
      .useValue(null)
      .compile();
    app = moduleRef.createNestApplication({ logger: false });
    applyTransport(app);
    await app.listen(0, "127.0.0.1");
    base = await app.getUrl();
  });

  afterAll(async () => {
    if (originalSecret === undefined) delete process.env.HERON_JWT_SECRET;
    else process.env.HERON_JWT_SECRET = originalSecret;
    // PgConnection 的销毁钩子关 pglite：e2e 里 app.close() 就是「容器关闭即释放连接」那次实测
    await app.close();
  });

  it.each([
    ["create", "suspend"],
    ["create", "disable"],
    ["create", "demote"],
    ["claim", "suspend"],
    ["claim", "disable"],
    ["claim", "demote"],
    ["confirm", "suspend"],
    ["confirm", "disable"],
    ["confirm", "demote"],
  ])("rechecks %s after a guard snapshot races with %s", async (action, mutation) => {
    const { tenantId, slug, reviewerId } = await newTenant();
    const taskId =
      action === "confirm"
        ? await seedReviewTask(tenantId, reviewerId, "Authorization race")
        : (
            await withTenant(db, tenantId, (t) =>
              createTask(t, { capability: "news", candidateId: null }),
            )
          ).id;
    const ownerId = await withTenant(db, tenantId, (t) =>
      createUser(t, {
        email: "owner@example.com",
        displayName: "Owner",
        role: "owner",
        passwordHash: "unusable:test",
      }),
    );
    const token = await login(slug, REVIEWER);
    const snapshot = () =>
      withTenant(db, tenantId, async (t) => ({
        tasks: await listTasks(t),
        candidates: await listCandidates(t),
        gates: await listGateResults(t, taskId),
      }));
    const before = await snapshot();
    const auth = app.get(AuthService);
    const userFor = auth.userFor.bind(auth);
    const guard = vi.spyOn(auth, "userFor").mockImplementationOnce(async (payload) => {
      const stale = await userFor(payload);
      expect(stale?.id).toBe(reviewerId);
      if (mutation === "suspend") await setTenantSuspended(db, tenantId, new Date().toISOString());
      else
        await withTenant(db, tenantId, (t) =>
          updateMember(
            t,
            ownerId,
            reviewerId,
            mutation === "disable" ? { disabled: true } : { role: "viewer" },
          ),
        );
      return stale;
    });
    try {
      const path =
        action === "create"
          ? "/tasks"
          : action === "claim"
            ? `/tasks/${taskId}/claim`
            : `/reviews/${taskId}/confirm`;
      const res = await post(
        path,
        token,
        action === "create"
          ? { capability: "news", title: "Must not create" }
          : { contactSheetFingerprint: SHEET_FP },
      );
      expect(res.status).toBe(403);
      expect(await res.text()).not.toMatch(/SELECT|INSERT|UPDATE|password_hash/);
      expect(await snapshot()).toEqual(before);
    } finally {
      guard.mockRestore();
    }
  });

  it("does not disclose SQL from claim failures and permits current authorized writers", async () => {
    const { slug } = await newTenant();
    const token = await login(slug, REVIEWER);
    const invalid = await post("/tasks/not-a-uuid/claim", token, {});
    expect(invalid.status).toBe(400);
    expect(await invalid.text()).not.toMatch(/select|params:|uuid|password_hash/i);
    const created = await post("/tasks", token, { capability: "news", title: "Current writer" });
    expect(created.status).toBe(201);
    const task = await json(created);
    const claimed = await post(`/tasks/${String(task.id)}/claim`, token, {});
    expect(claimed.status).toBe(201);
    expect(await json(claimed)).toMatchObject({ stage: "claimed" });
    expect(
      (await post("/tasks", await login(slug, VIEWER), { capability: "news", title: "Read only" }))
        .status,
    ).toBe(403);
  });

  it("viewer 点确认：403 说得出缺哪个角色，任务一动不动", async () => {
    const { tenantId, slug, reviewerId } = await newTenant();
    const taskId = await seedReviewTask(tenantId, reviewerId, "某市开通新航线");
    const viewer = await login(slug, VIEWER);

    const res = await post(`/reviews/${taskId}/confirm`, viewer, {
      contactSheetFingerprint: SHEET_FP,
    });
    expect(res.status).toBe(403);
    const message = String((await json(res)).message);
    expect(message).toContain("需要 reviewer 及以上");
    expect(message).toContain("viewer");

    // 闸在 handler 之前：任务仍在 review，且一行 human 闸都不该被写下
    const after = await withTenant(db, tenantId, async (t) => ({
      stage: (await getTask(t, taskId))?.stage,
      gates: await listGateResults(t, taskId),
    }));
    expect(after.stage).toBe("review");
    expect(after.gates.filter((g) => g.gate === "human")).toEqual([]);
  });

  it("旧指纹：409，进不了 approved，但闸记录留下为什么拒", async () => {
    const { tenantId, slug, reviewerId } = await newTenant();
    const taskId = await seedReviewTask(tenantId, reviewerId, "跨海大桥合龙");
    const reviewer = await login(slug, REVIEWER);

    const pending = await get("/reviews/pending", reviewer);
    expect(pending.status).toBe(200);
    expect((await json(pending)).reviews).toMatchObject([
      { taskId, title: "跨海大桥合龙", renderVersion: 1, contactSheetFingerprint: SHEET_FP },
    ]);

    const res = await post(`/reviews/${taskId}/confirm`, reviewer, {
      contactSheetFingerprint: STALE_FP,
    });
    expect(res.status).toBe(409);
    expect(String((await json(res)).message)).toContain("不是当前这一版");

    const after = await withTenant(db, tenantId, async (t) => ({
      stage: (await getTask(t, taskId))?.stage,
      gates: await listGateResults(t, taskId),
      queue: await listReviewQueue(t),
    }));
    expect(after.stage).toBe("failed");
    const human = after.gates.find((g) => g.gate === "human");
    expect(human?.passed).toBe(false);
    expect(human?.rejections[0]?.ruleId).toBe("human.fingerprint_mismatch");
    // 失败的任务从待审队列里消失：reviewer 不会对一条已经 failed 的任务再点一次
    expect(after.queue).toEqual([]);
  });

  it("指纹对得上才放行：approved，确认记录绑着那一版", async () => {
    const { tenantId, slug, reviewerId } = await newTenant();
    const taskId = await seedReviewTask(tenantId, reviewerId, "山区小学新校舍投用");
    const reviewer = await login(slug, REVIEWER);

    const res = await post(`/reviews/${taskId}/confirm`, reviewer, {
      contactSheetFingerprint: SHEET_FP,
    });
    expect(res.status).toBe(201);
    expect(await json(res)).toMatchObject({ id: taskId, stage: "approved" });

    const saved = await withTenant(db, tenantId, (t) =>
      confirmationMatches(t, {
        taskId,
        renderVersion: 1,
        contactSheetFingerprint: SHEET_FP,
      }),
    );
    expect(saved).toBe(true);

    const pending = await get("/reviews/pending", reviewer);
    expect((await json(pending)).reviews).toEqual([]);
  });

  /**
   * 派发只挂在人的两个动作上。这条断言盯的是接线本身：删掉服务里那行 push 就得红。
   * 队列在测试里是 null，push 走的是「未配置就什么都不做」那条早退分支——
   * 要验的是「有没有被调用、带的是哪个 id」，不是投递成没成功。
   */
  it("认领与确认把 taskId 推给队列，创建与被拒的确认不推", async () => {
    const push = vi.spyOn(app.get(TaskQueueService), "push");
    const pushed = () => push.mock.calls.map(([id]) => id);

    const first = await newTenant();
    const reviewer = await login(first.slug, REVIEWER);
    const created = await post("/tasks", reviewer, { capability: "news", title: "建一条候选" });
    const taskId = String((await json(created)).id);
    expect(pushed()).toEqual([]);

    expect((await post(`/tasks/${taskId}/claim`, reviewer, {})).status).toBe(201);
    expect(pushed()).toEqual([taskId]);

    // 每条 review 态任务各占一个租户：seedReviewTask 的夹具要求租户里只有一条任务，
    // 而指纹对不上的那次确认会把任务判成 failed，同一租户里没法再确认第二次。
    const stale = await newTenant();
    const staleId = await seedReviewTask(stale.tenantId, stale.reviewerId, "被拒的确认不该派活");
    expect(
      (
        await post(`/reviews/${staleId}/confirm`, await login(stale.slug, REVIEWER), {
          contactSheetFingerprint: STALE_FP,
        })
      ).status,
    ).toBe(409);
    expect(pushed()).toEqual([taskId]);

    const ok = await newTenant();
    const okId = await seedReviewTask(ok.tenantId, ok.reviewerId, "确认放行才派活");
    expect(
      (
        await post(`/reviews/${okId}/confirm`, await login(ok.slug, REVIEWER), {
          contactSheetFingerprint: SHEET_FP,
        })
      ).status,
    ).toBe(201);
    expect(pushed()).toEqual([taskId, okId]);

    push.mockRestore();
  });
});
