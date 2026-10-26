import { Queue } from "bullmq";
import { Redis } from "ioredis";

export type { Redis };

/**
 * 探针只依赖一个方法。参数写成 `Pick<Redis, "ping">` 会把「造一个探针替身」变成
 * 「伪造整个客户端」——重载还带字面量返回类型，测试替身根本过不了类型检查。
 */
export interface RedisPinger {
  ping(): Promise<string>;
}

export interface RedisProbeResult {
  configured: boolean;
  ok: boolean;
  detail: string;
}

const DEFAULT_TIMEOUT_MS = 1500;

/** 连接错误只走事件，探针拿不到；按客户端记下最近一条，失败时才能说出真正的原因。 */
const lastErrors = new WeakMap<object, Error>();

function messageOf(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

/**
 * Redis 只有一条配置来源，且没有默认地址。
 * 默认值会把「忘了配」变成「连到一台谁都不知道的实例」，那种故障比启动报错难查得多。
 * 返回原串而不是重建的 URL：口令里的 %40 再解码一次就成了分隔符，ioredis 会把后半截当主机名。
 */
export function parseRedisUrl(raw: string | undefined): string | null {
  const url = raw?.trim();
  if (!url) return null;
  let protocol: string;
  try {
    protocol = new URL(url).protocol;
  } catch {
    throw new Error(`REDIS_URL 不是合法 URL："${url}"`);
  }
  if (protocol !== "redis:" && protocol !== "rediss:") {
    throw new Error(`REDIS_URL 只认 redis:// 或 rediss://，现在是 "${protocol}"`);
  }
  return url;
}

/**
 * 启动就建连，不等到第一个业务请求：探针要能立刻回答「Redis 起没起来」。
 *
 * enableOfflineQueue=false 是关键——默认值会把断连期间的命令攒在内存里，等 Redis 回来一起发，
 * 对缓存是延迟，对限流是补记，都不是你要的语义；宁可直接失败，由调用方决定降级。
 * retryStrategy 保留重连（Redis 重启后能自愈），但退避封顶 5 秒，不无限密集重试。
 */
export function openRedis(url: string, onError: (cause: Error) => void): Redis {
  const redis = new Redis(url, {
    lazyConnect: false,
    enableOfflineQueue: false,
    maxRetriesPerRequest: 1,
    connectTimeout: DEFAULT_TIMEOUT_MS,
    commandTimeout: DEFAULT_TIMEOUT_MS,
    retryStrategy: (times) => Math.min(times * 500, 5000),
  });
  // ioredis 的 'error' 事件没有监听器就是 uncaught exception：断线是常态，进程不许跟着死。
  redis.on("error", (cause) => {
    lastErrors.set(redis, cause);
    onError(cause);
  });
  return redis;
}

/**
 * 探针自带超时。commandTimeout 只在命令写出去之后计时，命令还排在连接队列里时它什么也不算，
 * 所以这里再包一层 race——健康检查被一个 Redis 挂住，比报告它不健康更糟。
 */
export async function probeRedis(
  client: RedisPinger | null,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<RedisProbeResult> {
  if (!client) {
    return {
      configured: false,
      ok: true,
      detail: "REDIS_URL 未配置：临时态与任务派发都退回 Postgres 自己跑",
    };
  }
  let timer: NodeJS.Timeout | undefined;
  try {
    const pong = await Promise.race([
      client.ping(),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => {
          reject(new Error(`超时（${timeoutMs}ms）`));
        }, timeoutMs);
      }),
    ]);
    return { configured: true, ok: pong === "PONG", detail: pong };
  } catch (cause) {
    return {
      configured: true,
      ok: false,
      // 没连上时 ping 只会报「流不可写」这句内部措辞；真正的原因在连接错误里。
      detail: lastErrors.get(client)?.message ?? messageOf(cause),
    };
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/** 队列名。job 里只有一个 taskId，任务状态与租约始终在 Postgres 的 tasks 表。 */
export const TASK_QUEUE = "heron-task";

/** 队列载荷：派发动作不该携带任何业务状态，携带了就等于有两个真相源。 */
export interface TaskQueueJob {
  readonly taskId: string;
}

/** 带上载荷类型：拿到的 job 才有 .taskId，而不是一个 any。 */
export type TaskQueue = Queue<TaskQueueJob>;

export interface EnqueueResult {
  ok: boolean;
  detail: string;
}

/**
 * 投递给生产者的连接就是 openRedis 那一份：命令超时、不排队离线命令、有限重试。
 * 生产者只发不等，用不上阻塞命令，所以不需要 Worker 那套 maxRetriesPerRequest=null。
 */
export function openTaskQueue(
  url: string | null,
  onError: (cause: Error) => void,
): TaskQueue | null {
  if (!url) return null;
  return new Queue<TaskQueueJob>(TASK_QUEUE, { connection: openRedis(url, onError) });
}

/**
 * 投一条任务进队列。永不抛错，也绝不许挂住调用方：
 * 这是人点「认领」「确认」之后的加速动作，投递失败只意味着 worker 靠轮询兜底会慢一点，
 * 而把一次 Redis 抖动变成人的请求失败，等于让加速器决定主链路。
 */
export async function enqueueTask(
  queue: TaskQueue | null,
  taskId: string,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<EnqueueResult> {
  if (!queue) return { ok: false, detail: "队列未配置：worker 会按轮询领任务" };
  let timer: NodeJS.Timeout | undefined;
  try {
    await Promise.race([
      queue.add(TASK_QUEUE, { taskId } satisfies TaskQueueJob, ENQUEUE_JOB_OPTIONS),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => {
          reject(new Error(`投递超时（${timeoutMs}ms）`));
        }, timeoutMs);
      }),
    ]);
    return { ok: true, detail: `已投递 ${taskId.slice(0, 8)}` };
  } catch (cause) {
    return { ok: false, detail: `投递失败，交给 worker 轮询兜底：${messageOf(cause)}` };
  } finally {
    // 投完就把这颗计时器摘掉：它比连接活得久，进程退出会被自己的计时器拖住。
    if (timer) clearTimeout(timer);
  }
}

/**
 * 不给 attempts：重投由 Postgres 说了算（recoverExpiredLeases 数心跳、够次数才判死等人），
 * 队列自己再重试一遍就等于两个地方各自决定「这条活该做几次」。
 * 完成的 job 立刻删；失败的留 100 条给人看现场。
 */
const ENQUEUE_JOB_OPTIONS = { removeOnComplete: true, removeOnFail: 100 } as const;
