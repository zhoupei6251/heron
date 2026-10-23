import { connectWorkerDb } from "./db";
import { MAX_ATTEMPTS, OWNER } from "./config";
import { recoverLeases } from "./scheduler";
import { tick } from "./tick";

const MIN_NODE_MAJOR = 24;
const IDLE_MS = 2_000;

function assertNode(): void {
  const major = Number.parseInt(process.versions.node, 10);
  if (Number.isNaN(major) || major < MIN_NODE_MAJOR) {
    console.error(
      `heron worker requires Node.js >= ${MIN_NODE_MAJOR} (current: ${process.versions.node})`,
    );
    process.exit(1);
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function describe(cause: unknown): string {
  return cause instanceof Error ? (cause.stack ?? cause.message) : String(cause);
}

/**
 * 串行循环而不是 setInterval：一次渲染可能跑几十秒，定时器会在上一条没跑完时叠进来，
 * 同一个进程里两条活并行——「串行」就只是一句注释了。
 */
async function main(): Promise<void> {
  assertNode();
  const db = await connectWorkerDb();

  let stopping = false;
  const stop = (signal: NodeJS.Signals): void => {
    if (stopping) return;
    stopping = true;
    console.log(`收到 ${signal}：跑完手头这条就退出`);
  };
  process.once("SIGTERM", stop);
  process.once("SIGINT", stop);

  const recovery = await recoverLeases(db);
  console.log(
    `worker 启动（${OWNER}），串行领任务；重启恢复：回队 ${recovery.returned.length} 条，判死 ${recovery.failed.length} 条（最多重试 ${MAX_ATTEMPTS} 次）`,
  );

  while (!stopping) {
    try {
      const result = await tick(db);
      if (result) console.log(result);
    } catch (cause) {
      // 单轮失败不退出：库抖动时 worker 重试，而不是让 compose 反复重启它
      console.error(`本轮调度失败：${describe(cause)}`);
    }
    if (stopping) break;
    await sleep(IDLE_MS);
  }

  await db.close();
  console.log("worker 已退出");
}

main().catch((cause: unknown) => {
  console.error("worker 启动失败：", cause instanceof Error ? cause.message : String(cause));
  process.exit(1);
});
