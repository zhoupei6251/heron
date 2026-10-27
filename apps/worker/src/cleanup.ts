import { existsSync, rmSync } from "node:fs";
import { isAbsolute, join, relative, sep } from "node:path";

import { nowIso, VIDEOS_DIR } from "@heron/db";
import {
  getRetentionDays,
  listExpiredTaskDirs,
  listTenantIds,
  withTenant,
  type Db,
} from "@heron/db/pg";

export interface CleanupResult {
  readonly removed: number;
  readonly kept: number;
}

/**
 * 按各租户的保留天数删掉过期的成片目录。
 *
 * 三道保护都不是可选项：`.keep` 是渲染失败留给人看的现场（任务已经 failed，删了就再也查不出为什么）；
 * 路径必须落在成片根目录里（taskId 来自数据库，仍按不可信输入校验一次）；
 * 过期判据是「该任务最后一版渲染」，见 listExpiredTaskDirs。
 */
export async function cleanupArtifacts(
  db: Db,
  options: { root?: string; signal?: AbortSignal; at?: Date } = {},
): Promise<CleanupResult> {
  const root = options.root ?? VIDEOS_DIR;
  const at = options.at ?? new Date();
  let removed = 0;
  let kept = 0;

  for (const tenantId of await listTenantIds(db)) {
    if (options.signal?.aborted) break;
    const expired = await withTenant(db, tenantId, async (t) => {
      const days = await getRetentionDays(t);
      return listExpiredTaskDirs(t, nowIso(new Date(at.getTime() - days * 86_400_000)));
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
      if (!existsSync(dir)) continue;
      rmSync(dir, { recursive: true, force: true });
      removed += 1;
    }
  }
  return { removed, kept };
}
