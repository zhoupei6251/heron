import { cleanupArtifacts } from "./cleanup";
import { collectFeeds } from "./collect";
import { connectWorkerDb } from "./db";
import { MAX_ATTEMPTS, OWNER } from "./config";
import { tickProto } from "./proto";
import { createMutex, startQueueWorker } from "./queue";
import { recoverLeases, recoverProtoLeases } from "./scheduler";
import { tick } from "./tick";

const MIN_NODE_MAJOR = 24;
/** 源的最小间隔是 60s，30s 扫一次足够；真到期与否由库里的 last_attempt_at 判，不靠这个节拍。 */
const COLLECT_INTERVAL_MS = 30_000;
/** 保留天数按天算，一小时扫一次足够；真要腾空间也不差这一小时。 */
const CLEANUP_INTERVAL_MS = 3_600_000;
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
 * 定时线（采集、清理各一条）。上一拍没跑完就跳过这一拍，否则慢源会让同一个源被并发抓；
 * 停机时先停表、再等在手那一拍跑完，因为关库要排在它后面。
 */
function recurring(label: string, everyMs: number, run: () => Promise<void>) {
  const state: { inflight: Promise<void> | null } = { inflight: null };
  const tick = (): void => {
    if (state.inflight) return;
    state.inflight = run()
      .catch((cause: unknown) => console.error(`${label}：${describe(cause)}`))
      .finally(() => {
        state.inflight = null;
      });
  };
  const timer = setInterval(tick, everyMs);
  return {
    tick,
    stop: async (): Promise<void> => {
      clearInterval(timer);
      if (state.inflight) await state.inflight;
    },
  };
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
  // 抓取不在主循环里跑：一个慢源会拖住渲染队列。停机时用它中止在途请求。
  const shutdown = new AbortController();
  const stop = (signal: NodeJS.Signals): void => {
    if (stopping) return;
    stopping = true;
    shutdown.abort();
    wake();
    console.log(`收到 ${signal}：跑完手头这条就退出`);
  };
  process.once("SIGTERM", stop);
  process.once("SIGINT", stop);

  const runExclusive = createMutex();
  const worker = startQueueWorker(db, runExclusive);
  const recovery = await recoverLeases(db);
  const protoRecovery = await runExclusive(() => recoverProtoLeases(db));
  console.log(
    `worker 启动（${OWNER}），${worker ? "BullMQ 派发 + 60s 轮询兜底" : "串行轮询领任务（未配 REDIS_URL）"}；重启恢复：回队 ${String(recovery.returned.length)} 条，判死 ${String(recovery.failed.length)} 条（最多重试 ${String(MAX_ATTEMPTS)} 次）；原型回队 ${String(protoRecovery.returned.length)} 个，判死 ${String(protoRecovery.failed.length)} 个`,
  );

  const collection = recurring("本轮采集失败", COLLECT_INTERVAL_MS, async () => {
    const result = await collectFeeds(db, { signal: shutdown.signal });
    if (result.ok + result.failed > 0) {
      console.log(
        `采集：成功 ${result.ok} 源、失败 ${result.failed} 源，合并 ${result.imported} 条候选`,
      );
    }
  });
  collection.tick();

  const cleanup = recurring("本轮清理失败", CLEANUP_INTERVAL_MS, async () => {
    const result = await cleanupArtifacts(db, { signal: shutdown.signal });
    if (result.removed + result.kept + result.objectsRemoved + result.objectsFailed > 0) {
      console.log(
        `保留清理：删除 ${result.removed} 个成片目录，.keep 保留 ${result.kept} 个，` +
          `桶内删 ${result.objectsRemoved} 个对象、失败 ${result.objectsFailed} 个`,
      );
    }
  });
  cleanup.tick();

  const idleMs = worker ? POLL_WITH_QUEUE_MS : POLL_ONLY_MS;
  while (!stopping) {
    try {
      // 新闻线优先：两条线共用同一把互斥锁，一次只推一条
      const result =
        (await runExclusive(() => tick(db))) ?? (await runExclusive(() => tickProto(db)));
      if (result) console.log(result);
    } catch (cause) {
      // 单轮失败不退出：库抖动时 worker 重试，而不是让 compose 反复重启它
      console.error(`本轮调度失败：${describe(cause)}`);
    }
    if (stopping) break;
    await sleep(idleMs, stopped);
  }

  await worker?.close();
  // 两条定时线都用连接：等它们跑完再关库，否则只是一串「连接已关闭」的噪音
  await collection.stop();
  await cleanup.stop();
  await db.close();
  console.log("worker 已退出");
}

main().catch((cause: unknown) => {
  console.error("worker 启动失败：", cause instanceof Error ? cause.message : String(cause));
  process.exit(1);
});
