import { connectWorkerDb } from "./db";
import { MAX_ATTEMPTS, OWNER } from "./config";
import { createMutex, startQueueWorker } from "./queue";
import { recoverLeases } from "./scheduler";
import { tick } from "./tick";

const MIN_NODE_MAJOR = 24;
/**
 * 队列在时这条循环只是兜底：投递丢了一次（Redis 抖动、api 起在 Redis 之前）也要在 60s 内被捞起来。
 * 队列不在时它就是唯一的调度器，2s 是人点完认领到看见动静的体感上限。
 */
const POLL_WITH_QUEUE_MS = 60_000;
const POLL_ONLY_MS = 2_000;

function assertNode(): void {
  const major = Number.parseInt(process.versions.node, 10);
  if (Number.isNaN(major) || major < MIN_NODE_MAJOR) {
    console.error(
      `heron worker requires Node.js >= ${MIN_NODE_MAJOR} (current: ${process.versions.node})`,
    );
    process.exit(1);
  }
}

/** 可打断的等待：兜底轮询一觉可能睡 60s，SIGTERM 不该等满一整轮才被看见。 */
function sleep(ms: number, wake: Promise<void>): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    void wake.then(() => {
      clearTimeout(timer);
      resolve();
    });
  });
}

function describe(cause: unknown): string {
  return cause instanceof Error ? (cause.stack ?? cause.message) : String(cause);
}

/**
 * 串行循环而不是 setInterval：一次渲染可能跑几十秒，定时器会在上一条没跑完时叠进来，
 * 同一个进程里两条活并行——「串行」就只是一句注释了。
 *
 * 有了 BullMQ 之后这条原则更容易被打破（job 与轮询是两个入口），所以所有推进都过同一把互斥锁：
 * 队列的 concurrency:1 只约束 job 之间，挡不住轮询在渲染中途又领一条。
 */
async function main(): Promise<void> {
  assertNode();
  const db = await connectWorkerDb();

  let stopping = false;
  let wake!: () => void;
  const stopped = new Promise<void>((resolve) => {
    wake = resolve;
  });
  const stop = (signal: NodeJS.Signals): void => {
    if (stopping) return;
    stopping = true;
    wake();
    console.log(`收到 ${signal}：跑完手头这条就退出`);
  };
  process.once("SIGTERM", stop);
  process.once("SIGINT", stop);

  const runExclusive = createMutex();
  const worker = startQueueWorker(db, runExclusive);
  const recovery = await recoverLeases(db);
  console.log(
    `worker 启动（${OWNER}），${worker ? "BullMQ 派发 + 60s 轮询兜底" : "串行轮询领任务（未配 REDIS_URL）"}；重启恢复：回队 ${recovery.returned.length} 条，判死 ${recovery.failed.length} 条（最多重试 ${MAX_ATTEMPTS} 次）`,
  );

  const idleMs = worker ? POLL_WITH_QUEUE_MS : POLL_ONLY_MS;
  while (!stopping) {
    try {
      const result = await runExclusive(() => tick(db));
      if (result) console.log(result);
    } catch (cause) {
      // 单轮失败不退出：库抖动时 worker 重试，而不是让 compose 反复重启它
      console.error(`本轮调度失败：${describe(cause)}`);
    }
    if (stopping) break;
    await sleep(idleMs, stopped);
  }

  await worker?.close();
  await db.close();
  console.log("worker 已退出");
}

main().catch((cause: unknown) => {
  console.error("worker 启动失败：", cause instanceof Error ? cause.message : String(cause));
  process.exit(1);
});
