import { existsSync, rmSync } from "node:fs";
import { isAbsolute, join, relative, sep } from "node:path";

import { DEFAULT_RETENTION_DAYS, nowIso, VIDEOS_DIR } from "@heron/db";
import {
  getRetentionDays,
  listExpiredTaskDirs,
  listRenderObjectKeys,
  listTenantIds,
  platformDefaults,
  withTenant,
  type Db,
} from "@heron/db/pg";
import { createObjectStore, type ObjectStore } from "@heron/storage";

export interface CleanupResult {
  readonly removed: number;
  readonly kept: number;
  readonly objectsRemoved: number;
  readonly objectsFailed: number;
}

/**
 * 按各租户的保留天数删掉过期的成片目录与桶里的对象。
 *
 * 三道保护都不是可选项：`.keep` 是渲染失败留给人看的现场（任务已经 failed，删了就再也查不出为什么）；
 * 路径必须落在成片根目录里（taskId 来自数据库，仍按不可信输入校验一次）；
 * 过期判据是「该任务最后一版渲染」，见 listExpiredTaskDirs。
 *
 * 远端先删、本地后删：远端失败不阻断其余任务，只计数并逐条记 key + 原因。
 * renders 行不删，所以下一轮还能拿到同一批 key —— 失败是自愈的，不需要失败队列。
 */
export async function cleanupArtifacts(
  db: Db,
  options: {
    root?: string;
    signal?: AbortSignal;
    at?: Date;
    store?: ObjectStore;
  } = {},
): Promise<CleanupResult> {
  const root = options.root ?? VIDEOS_DIR;
  const at = options.at ?? new Date();
  const store = options.store ?? createObjectStore();
  // 平台默认保留天数，整轮读一次；读不到就按内置默认继续清，并说明用的是哪一档
  const platform = await platformDefaults(db).catch((cause: unknown) => {
    console.error(
      `读平台默认保留天数失败，本轮按内置 ${String(DEFAULT_RETENTION_DAYS)} 天处理：${String(cause)}`,
    );
    return null;
  });
  const fallbackDays = platform?.retentionDays ?? DEFAULT_RETENTION_DAYS;
  let removed = 0;
  let kept = 0;
  let objectsRemoved = 0;
  let objectsFailed = 0;

  for (const tenantId of await listTenantIds(db)) {
    if (options.signal?.aborted) break;
    const { expired, keysByTask } = await withTenant(db, tenantId, async (t) => {
      const days = await getRetentionDays(t, fallbackDays);
      const tasks = await listExpiredTaskDirs(
        t,
        nowIso(new Date(at.getTime() - days * 86_400_000)),
      );
      return { expired: tasks, keysByTask: await listRenderObjectKeys(t, tasks) };
    });
    for (const taskId of expired) {
      const dir = join(root, taskId);
      const within = relative(root, dir);
      if (!within || within === ".." || within.startsWith(`..${sep}`) || isAbsolute(within)) {
        console.error(`清理跳过：任务 ${taskId} 的目录不在成片根目录内`);
        continue;
      }
      if (existsSync(join(dir, ".keep"))) {
        kept += 1;
        continue;
      }
      for (const key of keysByTask.get(taskId) ?? []) {
        try {
          await store.remove(key);
          objectsRemoved += 1;
        } catch (error) {
          objectsFailed += 1;
          console.error(`清理远端对象失败：key=${key} 原因=${String(error)}`);
        }
      }
      // 本地那份可能早被清过或从来没在这台机器上：远端照样要删，本地没了不算跳过。
      if (existsSync(dir)) {
        rmSync(dir, { recursive: true, force: true });
        removed += 1;
      }
    }
  }
  return { removed, kept, objectsRemoved, objectsFailed };
}
