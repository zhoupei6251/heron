import { randomUUID } from "node:crypto";

import { normKey } from "@heron/domain";
import { eq, sql } from "drizzle-orm";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import {
  APP_ROLE,
  candidates,
  gateResults,
  migrate,
  openDb,
  renders,
  taskEvents,
  tasks,
  tenants,
  withTenant,
  type Db,
} from "./index";

const AT = "2026-10-22T00:00:00.000Z";
const TENANT_A = "00000000-0000-4000-8000-0000000000aa";
const TENANT_B = "00000000-0000-4000-8000-0000000000bb";

function candidate(tenantId: string, title: string) {
  return {
    id: randomUUID(),
    tenantId,
    title,
    // 用生产实现，不在测试里复制去重键规则：JS 的 /\W/g 不带 u 标志会把汉字整段吃掉
    normKey: normKey(title),
    firstSeenAt: AT,
    lastSeenAt: AT,
    createdAt: AT,
    updatedAt: AT,
  };
}

/**
 * drizzle 把驱动错误包成 `Failed query: …`，SQL 原文（`row-level security` 那句话）在 cause 上，
 * 所以断言必须把两段拼起来看——直接 rejects.toThrow 只会匹配到 SQL 文本而误判通过/失败。
 */
async function rejectText(run: () => Promise<unknown>): Promise<string> {
  try {
    await run();
  } catch (e) {
    const err = e as { message: string; cause?: { message?: string } };
    return `${err.message} ${err.cause?.message ?? ""}`;
  }
  throw new Error("期望被数据库拒掉，结果写进去了");
}

/**
 * 这是整个多租户的地基。pglite 以超管身份连接，而**超管无视 RLS（FORCE 也不管他）**。
 * withTenant 自己会在事务里 SET LOCAL ROLE 降权，但这个文件还要测「没走 withTenant 的裸连接」
 * （那条 fail-closed 断言就是拿裸句柄发的），所以 beforeAll 再做一次 session 级降权——
 * 不降权跑的就是「没开 RLS 的库」，绿灯是假的。
 */
describe("行级安全", () => {
  let db: Db;

  beforeAll(async () => {
    db = openDb("memory");
    await migrate(db);
    // 建租户是特权动作，必须在降权之前做完
    await db.insert(tenants).values([
      { id: TENANT_A, name: "A 台", slug: "a", suspendedAt: null, createdAt: AT },
      { id: TENANT_B, name: "B 台", slug: "b", suspendedAt: null, createdAt: AT },
    ]);
    await db.execute(sql`SET ROLE ${sql.raw(APP_ROLE)}`);
  });

  afterAll(async () => {
    await db.close();
  });

  it("A 写一条，B 一条也读不到；写 WHERE 指名要别人的行也不行", async () => {
    await withTenant(db, TENANT_A, (t) =>
      t.db.insert(candidates).values(candidate(TENANT_A, "台风巴威登陆")),
    );

    expect(await withTenant(db, TENANT_B, (t) => t.db.select().from(candidates))).toHaveLength(0);
    expect(
      await withTenant(db, TENANT_B, (t) =>
        t.db.select().from(candidates).where(eq(candidates.title, "台风巴威登陆")),
      ),
    ).toHaveLength(0);
    expect(await withTenant(db, TENANT_A, (t) => t.db.select().from(candidates))).toHaveLength(1);
  });

  it("没设 app.tenant_id 时读不到、也写不进（fail-closed）", async () => {
    // 不带 set_config 的裸事务：GUC 为空 → policy 里的比较结果是 NULL → 判假
    expect(await db.transaction((tx) => tx.select().from(candidates))).toHaveLength(0);
    expect(
      await rejectText(() => db.insert(candidates).values(candidate(TENANT_A, "没上下文也想写"))),
    ).toMatch(/row-level security/);
  });

  it("把 tenant_id 写成别人的租户，INSERT 被 WITH CHECK 拒", async () => {
    expect(
      await rejectText(() =>
        withTenant(db, TENANT_A, (t) =>
          t.db.insert(candidates).values(candidate(TENANT_B, "冒名")),
        ),
      ),
    ).toMatch(/row-level security/);

    // 上一条被拒后 A 的租户里仍只有那一条热点
    expect(await withTenant(db, TENANT_A, (t) => t.db.select().from(candidates))).toHaveLength(1);
  });

  it("同一 norm_key 在两个租户各一条是合法的", async () => {
    await withTenant(db, TENANT_A, (t) =>
      t.db.insert(candidates).values(candidate(TENANT_A, "重复热点")),
    );
    await withTenant(db, TENANT_B, (t) =>
      t.db.insert(candidates).values(candidate(TENANT_B, "重复热点")),
    );
    expect(
      await withTenant(db, TENANT_A, (t) =>
        t.db.select().from(candidates).where(eq(candidates.normKey, "重复热点")),
      ),
    ).toHaveLength(1);
    expect(
      await withTenant(db, TENANT_B, (t) =>
        t.db.select().from(candidates).where(eq(candidates.normKey, "重复热点")),
      ),
    ).toHaveLength(1);
  });

  it("tenants 表本身也隔离：B 眼里只存在 B 自己", async () => {
    const visible = await withTenant(db, TENANT_B, (t) =>
      t.db.select({ slug: tenants.slug }).from(tenants),
    );
    expect(visible).toEqual([{ slug: "b" }]);
  });

  it("GUC 是事务局部的：提交后连接回到池子不带上一个租户", async () => {
    await withTenant(db, TENANT_A, () => Promise.resolve());
    expect(await db.transaction((tx) => tx.select().from(candidates))).toHaveLength(0);
  });

  /**
   * 子表（task_events / gate_results / renders）故意不带 tenant_id：租户由 tasks 的 FK 继承。
   * 于是「按 task_id 查子表」这条最自然的查询天生就是跨租户的入口，隔离只能来自那条 EXISTS 策略。
   * 仓储层（./artifacts.ts）每一条这样的查询都指望它，所以这里两头都验：
   * 本租户读得到（策略没把业务关死），别人一行也读不到、也塞不进去（WITH CHECK 顺着 FK 追溯）。
   */
  it("不带 tenant_id 的子表靠父表 FK 继承隔离", async () => {
    const taskId = randomUUID();
    await withTenant(db, TENANT_A, async (t) => {
      await t.db.insert(tasks).values({
        id: taskId,
        tenantId: TENANT_A,
        capability: "news",
        candidateId: null,
        stage: "claimed",
        createdAt: AT,
        updatedAt: AT,
      });
      await t.db.insert(taskEvents).values({
        taskId,
        fromStage: "candidate",
        toStage: "claimed",
        reason: "认领人 周佩",
        createdAt: AT,
      });
      await t.db.insert(gateResults).values({
        id: randomUUID(),
        taskId,
        gate: "contract",
        renderVersion: null,
        passed: true,
        note: null,
        rejections: [],
        evaluatedAt: AT,
      });
      await t.db.insert(renders).values({
        id: randomUUID(),
        taskId,
        version: 1,
        pack: "demo",
        fingerprint: "sha256:test",
        timings: [],
        createdAt: AT,
      });
    });

    const counts = (tenantId: string) =>
      withTenant(db, tenantId, async (t) => ({
        events: (await t.db.select().from(taskEvents).where(eq(taskEvents.taskId, taskId))).length,
        gates: (await t.db.select().from(gateResults).where(eq(gateResults.taskId, taskId))).length,
        renders: (await t.db.select().from(renders).where(eq(renders.taskId, taskId))).length,
      }));
    expect(await counts(TENANT_A)).toEqual({ events: 1, gates: 1, renders: 1 });
    expect(await counts(TENANT_B)).toEqual({ events: 0, gates: 0, renders: 0 });

    expect(
      await rejectText(() =>
        withTenant(db, TENANT_B, (t) =>
          t.db.insert(taskEvents).values({
            taskId, // 指着 A 的任务写台账：父表租户不是 B，WITH CHECK 判假
            fromStage: "claimed",
            toStage: "failed",
            reason: "冒名写别人的台账",
            createdAt: AT,
          }),
        ),
      ),
    ).toMatch(/row-level security/);
  });
});
