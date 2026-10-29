import { nowIso } from "@heron/db";
import {
  type ClaimedTask,
  type Db,
  type PlatformDefaults,
  markFailed,
  platformDefaults,
  withTenant,
} from "@heron/db/pg";
import { GateRefusalError } from "@heron/domain";

import { OWNER } from "./config";
import { QuotaRefusalError, walkTask, type WalkTrace } from "./pipeline";
import { claimById, claimNext } from "./scheduler";

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
  return walk(db, claimed);
}

/**
 * 队列投来的 taskId 走这条路：按 id 领，判据与领「最早一条」完全相同。
 * 领不到（已被别人的租约占着、还停在等人的状态）就什么都不做——
 * 队列重复投递、队列与兜底轮询同时跑，都只是多一次空转，不会渲染第二遍。
 */
export async function runTask(db: Db, taskId: string): Promise<string | null> {
  const claimed = await claimById(db, taskId);
  if (!claimed) return null;
  return walk(db, claimed);
}

async function walk(db: Db, claimed: ClaimedTask): Promise<string> {
  const { task, tenantId } = claimed;
  const label = task.id.slice(0, 8);
  // 每条任务读一次平台默认值（不缓存）：改了设置，下一单就按新值走。
  // 读不到不该让整条流水线停摆——退到租户设置与环境变量，并且说出来。
  const platform: PlatformDefaults | null = await platformDefaults(db).catch((cause: unknown) => {
    console.error(`读平台默认值失败，本单只用租户设置与环境变量：${describe(cause)}`);
    return null;
  });

  let outcome: WalkOutcome;
  try {
    outcome = await withTenant(db, tenantId, async (t): Promise<WalkOutcome> => {
      try {
        return { ok: true, trace: await walkTask(t, task, OWNER, { platform }) };
      } catch (cause) {
        if (cause instanceof GateRefusalError) {
          return { ok: false, gate: cause.gate, reason: cause.reason };
        }
        throw cause;
      }
    });
  } catch (cause) {
    // 额度拦下是业务结论不是崩溃：写它自己的那句话，别让一整段堆栈盖住「30/30 该升档了」
    const reason =
      cause instanceof QuotaRefusalError ? cause.message : `worker 异常：${describe(cause)}`;
    try {
      await withTenant(db, tenantId, (t) => markFailed(t, task.id, reason, nowIso(), null));
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
