import { Global, Inject, Injectable, Logger, Module, type OnModuleDestroy } from "@nestjs/common";

import {
  enqueueTask,
  openRedis,
  openTaskQueue,
  parseRedisUrl,
  type Redis,
  type TaskQueue,
  type TaskQueueJob,
} from "@heron/redis";

/**
 * Redis 在 heron 里是**可选的第二存储**：临时态计数与任务派发。账本不是它——
 * 状态机、租约、尝试次数、闸痕全在 Postgres 的 tasks 表，队列只递一个 taskId。
 *
 * REDIS_URL 缺席时 provider 给 null，功能整体关闭，api 照常启动——
 * 开源自托管要能只靠 Postgres 一键跑完整流水线，多一个必装中间件就少一批人。
 * 配了就启动即连：探针要能立刻回答「Redis 起没起来」，而不是等第一个业务请求才发现。
 */
export const REDIS = "REDIS";
export const QUEUE = "QUEUE";

export type RedisHandle = Redis | null;
export type QueueHandle = TaskQueue | null;

function redisUrl(): string | null {
  return parseRedisUrl(process.env.REDIS_URL);
}

function createRedis(): RedisHandle {
  const url = redisUrl();
  if (!url) return null;
  const logger = new Logger("Redis");
  return openRedis(url, (cause) => {
    logger.warn(`Redis 连接异常：${cause.message}`);
  });
}

/** 派发给 worker 的队列。与探针各用一条连接：BullMQ 会按自己的节奏重连与阻塞，不该搭在探针上。 */
function createQueue(): QueueHandle {
  const url = redisUrl();
  if (!url) return null;
  const logger = new Logger("Queue");
  return openTaskQueue(url, (cause) => {
    logger.warn(`队列连接异常：${cause.message}`);
  });
}

/**
 * 人点「认领」「确认」之后把活推到队列里。永不抛错：
 * 投递失败只是慢一点（worker 的兜底轮询仍会领到），把 Redis 抖动变成人的请求失败才是事故。
 */
@Injectable()
export class TaskQueueService {
  private readonly logger = new Logger(TaskQueueService.name);

  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(QUEUE) private readonly queue: QueueHandle) {}

  async push(taskId: string, kind: TaskQueueJob["kind"] = "task"): Promise<void> {
    // 没配 Redis 时轮询就是唯一的调度器，这不是异常，不该每次认领都留一条 warn
    if (!this.queue) return;
    const result = await enqueueTask(this.queue, taskId, kind);
    if (!result.ok) {
      this.logger.warn(`${taskId.slice(0, 8)} 未进队列，由 worker 轮询兜底：${result.detail}`);
    }
  }
}

@Injectable()
export class RedisConnection implements OnModuleDestroy {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(
    @Inject(REDIS) private readonly redis: RedisHandle,
    @Inject(QUEUE) private readonly queue: QueueHandle,
  ) {}

  /** quit 先发 QUIT；从没连上时它自己也会失败，那就直接断开，别让关闭流程卡住。 */
  async onModuleDestroy(): Promise<void> {
    const redis = this.redis;
    if (redis) {
      await redis.quit().catch(() => {
        redis.disconnect();
      });
    }
    await this.queue?.close();
  }
}

@Global()
@Module({
  providers: [
    { provide: REDIS, useFactory: createRedis },
    { provide: QUEUE, useFactory: createQueue },
    RedisConnection,
    TaskQueueService,
  ],
  exports: [REDIS, QUEUE, TaskQueueService],
})
export class RedisModule {}
