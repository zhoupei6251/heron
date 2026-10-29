import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import type { GateName, GateVerdict, Stage, Task } from "@heron/contracts";
import { sha256, VIDEOS_DIR } from "@heron/db";
import {
  advanceTask,
  findPublishByKey,
  getScript,
  insertPublish,
  insertRender,
  latestRender,
  nextRenderVersion,
  quotaStatus,
  recordUsage,
  releaseLease,
  updatePublish,
  type TenantDb,
} from "@heron/db/pg";
import {
  DECLARATION_TEXT,
  exceeds,
  excessMessage,
  gateForTransition,
  scriptVerdict,
  type GateEvaluator,
} from "@heron/domain";
import { createObjectStore, objectKey, type ObjectStore } from "@heron/storage";

import { getManifest } from "./capabilities";
import { declaresScript, generateScript, type ScriptDeps } from "./scripting";

/** 测试替掉真模型与真存储；默认两个都走真实实现（存储默认本地驱动，等于不搬）。 */
export interface WalkDeps extends ScriptDeps {
  store?: ObjectStore;
}

/**
 * 把一版渲染的产物搬到桶里，返回要写进 renders 的 key。
 * 键带租户前缀（按租户导出/删桶）与版本号（重渲换片，不覆盖上一版）。
 */
async function uploadArtifacts(
  store: ObjectStore,
  tenantId: string,
  taskId: string,
  version: number,
  files: { mp4: string; contactSheet?: string; aigcSidecar?: string },
): Promise<{ mp4Key: string; contactSheetKey?: string; aigcSidecarKey?: string }> {
  // 日期只取一次：一次上传的三个产物要落在同一层里，不能一个 10-29 一个 10-30。
  const at = new Date();
  const mp4Key = objectKey({ tenantId, taskId, version, kind: "mp4", at });
  await store.put(mp4Key, files.mp4);
  const keys: { mp4Key: string; contactSheetKey?: string; aigcSidecarKey?: string } = { mp4Key };
  if (files.contactSheet) {
    keys.contactSheetKey = objectKey({ tenantId, taskId, version, kind: "contact-sheet", at });
    await store.put(keys.contactSheetKey, files.contactSheet);
  }
  if (files.aigcSidecar) {
    keys.aigcSidecarKey = objectKey({ tenantId, taskId, version, kind: "aigc-sidecar", at });
    await store.put(keys.aigcSidecarKey, files.aigcSidecar);
  }
  return keys;
}

export interface WalkTrace {
  readonly taskId: string;
  readonly steps: readonly string[];
  readonly finalStage: Stage;
}

function taskDir(taskId: string): string {
  return join(VIDEOS_DIR, taskId);
}

/**
 * contract / visual / compliance 三道闸的尺子还在 harness-factory，P1/P5 才搬过来。
 * 这里显式占位放行并打点：domain 的门禁位仍报“未启用”，界面发布按钮保持置灰。
 * 例外是 news 的合同闸：它已有 domain 实现（scriptVerdict），不再占位。
 */
function placeholderVerdict(gate: GateName): GateVerdict {
  return {
    gate,
    passed: true,
    rejections: [],
    note: "占位放行：尺子还在 harness-factory，门禁位不因此开启",
  };
}

/**
 * 人的闸（review → approved）不归 worker 判。判定与落库的唯一入口是 confirmByHuman：
 * verdict 必须由「界面提交的指纹对上当前版本」这个动作产出，而不是谁递一个 passed 进来。
 * 正常路径上 worker 领不到 review（见 CLAIMABLE_EXCLUDED），真走到这一格就明确拒绝——
 * 宁可失败也不许替人放行。
 */
function humanNotWorkerDecidable(taskId: string): GateVerdict {
  return {
    gate: "human",
    passed: false,
    rejections: [
      {
        ruleId: "human.not_worker_decidable",
        snippet: taskId,
        why: "人的闸只能由确认动作产出（review.confirm），worker 不许替人放行",
      },
    ],
  };
}

function buildEvaluator(scripted: boolean, script: unknown): GateEvaluator {
  return ({ gate, task }) => {
    if (gate === "human") return humanNotWorkerDecidable(task.id);
    // 没声明写稿提示词的 capability（p0-fake）保持占位：它没有稿子可判
    if (gate === "contract" && scripted) return scriptVerdict(script);
    return placeholderVerdict(gate);
  };
}

/** 额度拦下的任务不是「worker 崩了」：tick 据此写一条干净的原因，而不是一整段堆栈。 */
export class QuotaRefusalError extends Error {
  constructor(reason: string) {
    super(reason);
    this.name = "QuotaRefusalError";
  }
}

/**
 * 额度闸：进 rendering / publishing 之前判一次本月账。
 *
 * 判据走 domain 的纯函数，与界面提示同一份——两处各写一遍的症状是「界面说还能发、worker 拒了」。
 * 超额抛错而不是静默跳过：任务会带着原因变 failed（tick 的 catch 里 markFailed），
 * 而「失败必须报原因」要求这句话里能看出是哪道门、用了几条、该去哪儿升档。
 */
async function refuseWhenOverQuota(tdb: TenantDb, task: Task): Promise<void> {
  const quota = await quotaStatus(tdb);
  const excess = exceeds(quota.plan, quota.usage);
  if (excess)
    throw new QuotaRefusalError(`任务 ${task.id} 被额度拦下：${excessMessage(quota.plan, excess)}`);
}

/** 进入某个阶段之前的副作用。人的确认不在这里：那一步由真实的人点出来。 */
async function onEnter(
  tdb: TenantDb,
  task: Task,
  to: Stage,
  note: string[],
  deps: WalkDeps,
  scripted: boolean,
): Promise<void> {
  if (to === "scripting") {
    if (!scripted) return;
    note.push(await generateScript(tdb, task, deps));
    return;
  }

  if (to === "rendering") {
    await refuseWhenOverQuota(tdb, task);
    const dir = taskDir(task.id);
    mkdirSync(dir, { recursive: true });
    const contactSheet = `task=${task.id}\npack=demo\nshots=1\n`;
    writeFileSync(join(dir, "contact-sheet.txt"), contactSheet, "utf8");
    writeFileSync(join(dir, "fake.mp4"), "not-a-real-video", "utf8");
    const version = await nextRenderVersion(tdb, task.id);
    // 先传再落库：上传失败就抛，事务回滚、任务带原因失败。
    // 静默降级成「只有本地有」会让一台渲染机成为唯一副本，而那正是对象存储要解决的问题。
    // 本地驱动（默认）不搬任何东西，keys 保持为空，取址仍走 sendFile。
    const store = deps.store ?? createObjectStore();
    const keys =
      store.driver === "local"
        ? {}
        : await uploadArtifacts(store, tdb.tenantId, task.id, version, {
            mp4: join(dir, "fake.mp4"),
            contactSheet: join(dir, "contact-sheet.txt"),
          });
    const durationMs = 1_000;
    await insertRender(tdb, {
      taskId: task.id,
      version,
      pack: "demo",
      mp4Path: join(dir, "fake.mp4"),
      contactSheetPath: join(dir, "contact-sheet.txt"),
      durationMs,
      fingerprint: sha256(contactSheet),
      ...keys,
    });
    // render_seconds 记的是真花的秒数：真渲染器接上后这里换成 timings 的实测值，
    // 与「月渲染条数」（额度）不是一回事，账本里别混用。
    await recordUsage(tdb, {
      kind: "render_seconds",
      quantity: String(durationMs / 1_000),
      unit: "seconds",
      taskId: task.id,
      detail: { version, pack: "demo" },
    });
    note.push(`rendering:v${version}`);
    return;
  }

  if (to === "publishing") {
    const render = await latestRender(tdb, task.id);
    if (!render) return;
    const key = `${task.id}:v${render.version}`;
    // 重试前先查幂等键：回执丢失不等于没发出去，直接重发就是发两遍
    if (await findPublishByKey(tdb, key)) {
      note.push(`publishing:幂等命中 ${key}`);
      return;
    }
    // 额度闸放在幂等键之后：重试同一版本是「补发」，不该被本月新增发布的额度挡住
    await refuseWhenOverQuota(tdb, task);
    const publishId = await insertPublish(tdb, {
      renderId: render.id,
      idempotencyKey: key,
      declaration: DECLARATION_TEXT,
    });
    await updatePublish(tdb, publishId, { status: "confirmed", receipt: "p0-stub-receipt" });
    // 一条发布记录记一次：重试命中幂等键时不会走到这里，账不会被重试刷高
    await recordUsage(tdb, {
      kind: "publish",
      quantity: "1",
      unit: "count",
      taskId: task.id,
      detail: { idempotencyKey: key, platform: "douyin" },
    });
    note.push(`publishing:${key}`);
  }
}

/**
 * 把一条活往前推，直到终态或者人手那道闸。
 *
 * review 是终点之一：到了就释放租约并停下，任务留在待审队列里等 confirmByHuman。
 * 释放必须和推进在同一个事务里（调用方 withTenant）——否则「停在 review 却还占着租约」
 * 的窗口里崩一次，租约过期会被当成进程死亡去计数，几次之后一条等人的任务就变成 failed。
 */
export async function walkTask(
  tdb: TenantDb,
  task: Task,
  owner: string,
  deps: WalkDeps = {},
): Promise<WalkTrace> {
  const manifest = getManifest(task.capability);
  const scripted = declaresScript(manifest.promptKeys);
  const steps: string[] = [task.stage];
  let current = task;

  while (current.stage !== "published" && current.stage !== "failed") {
    if (current.stage === "review") {
      await releaseLease(tdb, current.id, owner);
      break;
    }
    const index = manifest.pipeline.indexOf(current.stage);
    if (index === -1) {
      throw new Error(
        `任务 ${current.id} 停在 ${current.stage}，不在 capability ${manifest.name} 的 pipeline 里`,
      );
    }
    const next = manifest.pipeline[index + 1];
    if (!next) {
      throw new Error(`任务 ${current.id} 在 ${current.stage} 之后没有后继，pipeline 声明不完整`);
    }
    const note: string[] = [];
    await onEnter(tdb, current, next, note, deps, scripted);
    // 稿子在这一轮里才生成，所以要到合同闸那一刻再读，不能在循环外一次读死
    const script =
      scripted && gateForTransition(current.stage, next) === "contract"
        ? await getScript(tdb, current.id)
        : null;
    current = await advanceTask(tdb, {
      taskId: current.id,
      owner,
      to: next,
      evaluateGate: buildEvaluator(scripted, script),
    });
    steps.push(note.length > 0 ? `${next}(${note.join(",")})` : next);
  }

  return { taskId: current.id, steps, finalStage: current.stage };
}
