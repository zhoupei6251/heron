import { eq } from "drizzle-orm";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import * as repo from "./index";
import type { Db, TenantDb } from "./index";

const HASH = "sensitive-password-hash-never-audit";
const NOW = new Date("2026-10-28T09:00:00.000Z");
/** 上个月最后一天的最后一毫秒：跨月边界上最容易差一天的地方。 */
const LAST_MONTH = "2026-09-30T23:59:59.999Z";

/** 把 Error 链上的 message 串起来：驱动错误常常裹了两三层才露出约束名。 */
function chainMessages(cause: unknown): string {
  const parts: string[] = [];
  let current: unknown = cause;
  for (let depth = 0; depth < 5 && current; depth += 1) {
    parts.push(current instanceof Error ? current.message : JSON.stringify(current));
    current = current instanceof Error ? (current as { cause?: unknown }).cause : undefined;
  }
  return parts.join(" | ");
}

describe("套餐额度 (real PGlite)", () => {
  let db: Db;
  let tenantId: string;
  let owner: string;
  let taskId: string;
  const inT = <T>(fn: (t: TenantDb) => Promise<T>) => repo.withTenant(db, tenantId, fn);

  beforeEach(async () => {
    db = repo.openDb("memory");
    await repo.migrate(db);
    tenantId = await repo.createTenant(db, { name: "额度台", slug: "quota" });
    owner = await inT((t) =>
      repo.createUser(t, {
        email: "owner@quota.test",
        displayName: "Owner",
        passwordHash: HASH,
        role: "owner",
      }),
    );
    taskId = (await inT((t) => repo.createTask(t, { capability: "news", candidateId: null }))).id;
  });
  afterEach(async () => {
    await db.close();
  });

  it("自然月从 UTC 月初算起，不受本地时区影响", () => {
    expect(repo.monthStartIso(NOW)).toBe("2026-10-01T00:00:00.000Z");
    // 月初那一刻属于本月，不是上月
    expect(repo.monthStartIso(new Date("2026-01-01T00:00:00.000Z"))).toBe(
      "2026-01-01T00:00:00.000Z",
    );
    // 12 月不能滚成次年 1 月
    expect(repo.monthStartIso(new Date("2026-12-31T23:59:59.999Z"))).toBe(
      "2026-12-01T00:00:00.000Z",
    );
  });

  it("用量只数本月：跨月的渲染与发布不占这个月的额度", async () => {
    const thisRender = await inT((t) =>
      repo.insertRender(t, { taskId, version: 1, pack: "demo", fingerprint: "fp-now" }),
    );
    const oldRender = await inT((t) =>
      repo.insertRender(t, { taskId, version: 2, pack: "demo", fingerprint: "fp-old" }),
    );
    await inT((t) =>
      t.db
        .update(repo.renders)
        .set({ createdAt: LAST_MONTH })
        .where(eq(repo.renders.id, oldRender)),
    );
    await inT((t) =>
      repo.insertPublish(t, {
        renderId: thisRender,
        idempotencyKey: "now",
        declaration: "内容由AI生成",
      }),
    );
    const oldPublish = await inT((t) =>
      repo.insertPublish(t, {
        renderId: oldRender,
        idempotencyKey: "old",
        declaration: "内容由AI生成",
      }),
    );
    await inT((t) =>
      t.db
        .update(repo.publishes)
        .set({ createdAt: LAST_MONTH })
        .where(eq(repo.publishes.id, oldPublish)),
    );

    // 席位是当前值：不按月清零，上个月进来的人这个月仍占一个座
    expect(await inT((t) => repo.monthlyUsage(t, NOW))).toEqual({
      seats: 1,
      renders: 1,
      publishes: 1,
    });
    // 换一个月看：这个月的两行都不再计入
    expect(await inT((t) => repo.monthlyUsage(t, new Date("2026-11-02T00:00:00.000Z")))).toEqual({
      seats: 1,
      renders: 0,
      publishes: 0,
    });
  });

  it("新租户默认免费档，额度表与控制台读到的是同一份上限", async () => {
    expect(await inT((t) => repo.tenantPlan(t))).toBe("free");
    expect(await inT((t) => repo.quotaStatus(t, NOW))).toEqual({
      plan: "free",
      limits: { seats: 3, rendersPerMonth: 100, publishesPerMonth: 30 },
      usage: { seats: 1, renders: 0, publishes: 0 },
    });
  });

  it("免费档 3 席：第 4 个成员被拒，升档后同一个调用就放行", async () => {
    const add = (email: string) =>
      inT((t) =>
        repo.createManagedMember(t, owner, {
          email,
          displayName: email,
          passwordHash: HASH,
          role: "viewer",
        }),
      );
    await add("second@quota.test");
    await add("third@quota.test");
    // 3 席用满：再来一个就是 4 个
    await expect(add("fourth@quota.test")).rejects.toMatchObject({ code: "conflict" });
    await expect(add("fourth@quota.test")).rejects.toThrow(/成员数已达该档上限（3\/3.*平台台/);

    const operator = await repo.createPlatformOperator(db, {
      username: "quota-ops",
      displayName: "Ops",
      passwordHash: HASH,
    });
    expect(await repo.changeTenantPlan(db, operator.id, tenantId, "standard")).toMatchObject({
      plan: "standard",
    });
    // 闸读的是库里那一刻的档位，不是进程里缓存的旧值
    await expect(add("fourth@quota.test")).resolves.toMatchObject({ email: "fourth@quota.test" });
  });

  it("额度表与档位约束同源：不认识的档位写不进库", async () => {
    // drizzle 把驱动错误裹在 cause 里，约束名在那一层；直接看顶层只会看到 "Failed query"
    const failure = await db
      .update(repo.tenants)
      .set({ plan: "platinum" as never })
      .where(eq(repo.tenants.id, tenantId))
      .then(
        () => null,
        (cause: unknown) => cause,
      );
    expect(failure).not.toBeNull();
    expect(chainMessages(failure)).toContain("ck_tenants_plan_known");
  });
});
