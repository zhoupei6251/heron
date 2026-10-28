import { Worker } from "bullmq";

import { type Db } from "@heron/db/pg";
import { TASK_QUEUE, type TaskQueueJob } from "@heron/redis";

import { OWNER, REDIS_URL } from "./config";
import { runProto } from "./proto";
import { runTask } from "./tick";

/** 队列里的一条活只带 id 与 kind：谁领、领不领得到，全由 Postgres 的判据说了算。 */

/**
 * 同进程互斥。BullMQ 的 `concurrency: 1` 只约束 job 之间，挡不住兜底轮询在渲染跑到一半时又领一条，
 * 而一次渲染 ≈60s 墙钟——「串行」必须是事实，不能只是注释。
 */
export function createMutex(): <T>(run: () => Promise<T>) => Promise<T> {
  let tail: Promise<void> = Promise.resolve();
  return function runExclusive<T>(run: () => Promise<T>): Promise<T> {
    const waiting = tail;
    let release!: () => void;
    tail = new Promise<void>((resolve) => {
      release = resolve;
    });
    return waiting.then(async () => {
      try {
        return await run();
      } finally {
        release();
      }
    });
  };
}

/**
 * 起队列消费者。REDIS_URL 没配就返回 null，worker 退回纯轮询：
 * 开源自托管只靠 Postgres 也要能跑完整条流水线，Redis 是加速器不是前提。
 */
export function startQueueWorker(
  db: Db,
  runExclusive: <T>(run: () => Promise<T>) => Promise<T>,
): Worker<TaskQueueJob> | null {
  if (!REDIS_URL) return null;
  const worker = new Worker<TaskQueueJob>(
    TASK_QUEUE,
    (job) =>
      runExclusive(async () => {
        try {
          return job.data.kind === "proto"
            ? await runProto(db, job.data.taskId)
            : await runTask(db, job.data.taskId);
        } catch (cause) {
          // 任务状态与失败原因都已落在 Postgres；这里静默的话，Redis 里只剩一条看不懂的 failed job。
          return `job 异常：${cause instanceof Error ? (cause.stack ?? cause.message) : String(cause)}`;
        }
      }),
    {
      // maxRetriesPerRequest 必须是 null：BullMQ 用阻塞命令取活，有重试上限时命令一断就永久失败。
      connection: { url: REDIS_URL, maxRetriesPerRequest: null, enableOfflineQueue: false },
      concurrency: 1,
    },
  );
  // 连接错误只发事件、不抛到调用栈：不接住就等于整条派发通道无声地停。
  worker.on("error", (cause) => {
    console.error(`队列异常（${OWNER}）：${cause.message}`);
  });
  return worker;
}
