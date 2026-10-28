import { LEASE_TTL_SEC } from "@heron/db";
import {
  claimNextTask,
  claimNextProtoProject,
  claimProtoProjectById,
  claimTaskById,
  recoverExpiredLeases,
  recoverExpiredProtoLeases,
  type ClaimedProtoProject,
  type ClaimedTask,
  type Db,
} from "@heron/db/pg";
// 特权通道（heron_owner / BYPASSRLS）唯一的租户外使用者。eslint 按路径只放行这一个文件：
// 「拿到 AdminDb 就等于拿到全部租户的数据」，别的业务代码不许有第二次机会。
import { withAdmin } from "@heron/db/pg/admin";

import { MAX_ATTEMPTS, OWNER } from "./config";

/** 领下一条活。跨租户，所以走特权通道；返回的 tenantId 是接下来 withTenant 的入口。 */
export function claimNext(db: Db): Promise<ClaimedTask | null> {
  return withAdmin(db, (a) => claimNextTask(a, OWNER, LEASE_TTL_SEC));
}

/** 队列送来 taskId 时按 id 领活。判据与 claimNext 完全相同（同一条 FOR UPDATE SKIP LOCKED），
 * 所以 BullMQ 重复推、队列与兜底轮询同时跑，都不会把一条任务渲染两遍。
 */
export function claimById(db: Db, taskId: string): Promise<ClaimedTask | null> {
  return withAdmin(db, (a) => claimTaskById(a, taskId, OWNER, LEASE_TTL_SEC));
}

/** 原型线：在调用方的推进互斥锁内先回收再领，重启时尚未过期的租约也能在后续轮询恢复。 */
export async function claimNextProto(db: Db): Promise<ClaimedProtoProject | null> {
  await recoverProtoLeases(db);
  return withAdmin(db, (a) => claimNextProtoProject(a, OWNER, LEASE_TTL_SEC));
}

/** 队列送来 projectId 时按 id 领，判据与 claimNextProto 相同。 */
export function claimProtoById(db: Db, projectId: string): Promise<ClaimedProtoProject | null> {
  return withAdmin(db, (a) => claimProtoProjectById(a, projectId, OWNER, LEASE_TTL_SEC));
}

/** 重启恢复：把心跳过期的活放回池子，或超过重试上限判死等人。 */
export function recoverLeases(db: Db): Promise<{ returned: string[]; failed: string[] }> {
  return withAdmin(db, (a) =>
    recoverExpiredLeases(a, { leaseTtlSec: LEASE_TTL_SEC, maxAttempts: MAX_ATTEMPTS }),
  );
}

export function recoverProtoLeases(db: Db): Promise<{ returned: string[]; failed: string[] }> {
  return withAdmin(db, (a) =>
    recoverExpiredProtoLeases(a, { leaseTtlSec: LEASE_TTL_SEC, maxAttempts: MAX_ATTEMPTS }),
  );
}
