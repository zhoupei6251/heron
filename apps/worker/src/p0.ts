import type { Role } from "@heron/contracts";
import { DATABASE_URL } from "@heron/db";
import {
  claimByHuman,
  confirmByHuman,
  createCandidate,
  createTask,
  createUser,
  DEFAULT_TENANT_ID,
  findTenantBySlug,
  createTenant,
  getTask,
  getUserByEmail,
  latestRender,
  listTaskEventsSince,
  migrate,
  openDb,
  waitForSchema,
  withTenant,
  type Db,
  type TenantDb,
} from "@heron/db/pg";
import { publishBlockers, UNUSABLE_PREFIX } from "@heron/domain";

import { P0_FAKE } from "./capabilities";
import { tick } from "./tick";

/**
 * P0 出口判据：空 capability 也能在一个具体租户里跑完十态。
 *
 * 与旧版的区别不是「换成异步」，是**假任务现在真的有租户与真的有人认领/确认**：
 * 两个合成 actor（`!` 前缀的不可用口令）分别扮演认领人与审核人，审核那一步走 confirmByHuman，
 * 与网页上点「确认」是同一条路径、同一道闸。worker 不许替人放行，所以这里也必须替人点这一下。
 *
 * 有 DATABASE_URL 就连真库（不迁移：api 是唯一迁移方），否则开内存 pglite 并自己迁移。
 */

async function openP0Db(): Promise<Db> {
  if (DATABASE_URL) {
    const db = openDb(DATABASE_URL);
    await waitForSchema(db);
    return db;
  }
  const db = openDb("memory");
  await migrate(db);
  return db;
}

/**
 * 默认工作空间。真库里 seed-owner 可能已经建过（id 是随机的），那就用它——
 * 打印出来的 tenant 是实际用的那个，而不是硬写 DEFAULT_TENANT_ID 骗过判据。
 */
async function ensureTenant(db: Db): Promise<string> {
  const existing = await findTenantBySlug(db, "default");
  if (existing) return existing.id;
  return createTenant(db, { id: DEFAULT_TENANT_ID, name: "默认工作空间", slug: "default" });
}

/** 合成 actor：审计列留得下「是谁干的」，登录口永远验不过（见 domain/password.ts）。 */
async function ensureActor(
  t: TenantDb,
  email: string,
  displayName: string,
  role: Role,
): Promise<string> {
  const existing = await getUserByEmail(t, email);
  if (existing) return existing.id;
  return createUser(t, {
    email,
    displayName,
    passwordHash: `${UNUSABLE_PREFIX}p0-synthetic`,
    role,
  });
}

async function main(): Promise<void> {
  const db = await openP0Db();
  const tenantId = await ensureTenant(db);
  const actors = await withTenant(db, tenantId, async (t) => ({
    script: await ensureActor(t, "p0-script@local", "P0 假编剧", "operator"),
    reviewer: await ensureActor(t, "p0-reviewer@local", "P0 假审核", "reviewer"),
  }));

  const title = `P0 假任务 ${new Date().toISOString()}`;
  const taskId = await withTenant(db, tenantId, async (t) => {
    const candidateId = await createCandidate(t, { title, summary: "", heat: 0 });
    const task = await createTask(t, { capability: P0_FAKE, candidateId });
    await claimByHuman(t, task.id, actors.script);
    return task.id;
  });
  console.log(`已建立假任务 ${taskId}（租户 ${tenantId}，认领人 ${actors.script}）`);

  console.log((await tick(db)) ?? "没有领到任务");

  // worker 停在 review 等人。这里代替网页上那一次点击：提交的必须是当前联络表的指纹。
  const fingerprint = await withTenant(db, tenantId, async (t) => {
    const render = await latestRender(t, taskId);
    if (!render) {
      throw new Error("假任务走到 review 却没有渲染版本：pipeline 的 rendering 副作用没接上");
    }
    return render.fingerprint;
  });
  const approved = await withTenant(db, tenantId, (t) =>
    confirmByHuman(t, {
      taskId,
      userId: actors.reviewer,
      contactSheetFingerprint: fingerprint,
    }),
  );
  console.log(
    `假确认：review -> ${approved.stage}（确认人 ${actors.reviewer}，指纹 ${fingerprint.slice(0, 12)}）`,
  );

  console.log((await tick(db)) ?? "没有领到任务");
  const drained = await tick(db);
  if (drained) {
    console.log(`第二次领到的任务不该存在：${drained}`);
    await db.close();
    process.exit(1);
  }

  const events = await withTenant(db, tenantId, (t) => listTaskEventsSince(t, "0"));
  const mine = events.filter((entry) => entry.event.taskId === taskId);
  const final = await withTenant(db, tenantId, (t) => getTask(t, taskId));
  if (!final) {
    console.log("任务写入了但读不回来");
    await db.close();
    process.exit(1);
  }

  console.log(`转移记录 ${mine.length} 条：`);
  for (const { event } of mine) {
    console.log(`  ${event.from} -> ${event.to}${event.reason ? ` · ${event.reason}` : ""}`);
  }
  console.log(`终态：${final.stage}，error_reason：${final.errorReason ?? "无"}`);
  console.log(`门禁位仍关闭（发布按钮必须置灰）：${publishBlockers().length} 道闸未启用`);
  // 这一行是 T8 的判据：假任务确实在一个具体租户上下文里跑完，两个动作都有真实 actor
  console.log(`tenant=${tenantId} actor=${actors.reviewer}`);

  const ok = final.stage === "published";
  console.log(ok ? "P0 出口判据成立：空 capability 跑完一条假任务" : "P0 未通过");
  await db.close();
  process.exit(ok ? 0 : 1);
}

main().catch((cause: unknown) => {
  console.error("P0 失败：", cause instanceof Error ? cause.message : String(cause));
  process.exit(1);
});
