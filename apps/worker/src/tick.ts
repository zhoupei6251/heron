import { type Db, markFailed, withTenant } from "@heron/db/pg";
import { GateRefusalError } from "@heron/domain";

import { OWNER } from "./config";
import { walkTask, type WalkTrace } from "./pipeline";
import { claimNext } from "./scheduler";

type WalkOutcome =
  | { readonly ok: true; readonly trace: WalkTrace }
  | { readonly ok: false; readonly gate: string; readonly reason: string };

function describe(cause: unknown): string {
  return cause instanceof Error ? (cause.stack ?? cause.message) : String(cause);
}

/**
 * 领一条活并推到终态或人的闸前。null 表示池子里没有可领的。
 *
 * 闸拒必须**在 withTenant 回调内**接住：拒绝时 gate_results 与 stage=failed 已经写在同一个事务里，
 * 让 GateRefusalError 穿出去，回滚会把「为什么被拒」这点审计痕一起抹掉。
 * 其余异常则相反——回滚整条推进，再单开一个事务把原因写进 error_reason。
 */
export async function tick(db: Db): Promise<string | null> {
  const claimed = await claimNext(db);
  if (!claimed) return null;
  const { task, tenantId } = claimed;
  const label = task.id.slice(0, 8);

  let outcome: WalkOutcome;
  try {
    outcome = await withTenant(db, tenantId, async (t): Promise<WalkOutcome> => {
      try {
        return { ok: true, trace: await walkTask(t, task, OWNER) };
      } catch (cause) {
        if (cause instanceof GateRefusalError) {
          return { ok: false, gate: cause.gate, reason: cause.reason };
        }
        throw cause;
      }
    });
  } catch (cause) {
    const reason = `worker 异常：${describe(cause)}`;
    try {
      await withTenant(db, tenantId, (t) => markFailed(t, task.id, reason));
    } catch (markCause) {
      // 连失败原因都写不回去时，两段都得出现在输出里，别让人只看到后半句
      return `${label} failed 未落库 · ${reason}；写失败原因时又出错：${describe(markCause)}`;
    }
    return `${label} failed · ${reason}`;
  }

  if (!outcome.ok) return `${label} failed[${outcome.gate}] · ${outcome.reason}`;
  if (outcome.trace.finalStage === "review") {
    return `${label} review · ${outcome.trace.steps.join(" → ")}，租约已释放，等人确认`;
  }
  return `${label} ${outcome.trace.finalStage} · ${outcome.trace.steps.join(" → ")}`;
}
