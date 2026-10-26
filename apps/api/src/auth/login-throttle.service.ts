import { createHash } from "node:crypto";

import { HttpException, HttpStatus, Inject, Injectable, Logger } from "@nestjs/common";

import { REDIS } from "../redis/redis.module";

export const MAX_FAILURES = 5;
export const WINDOW_MS = 15 * 60_000;

const KEY_PREFIX = "heron:login-fail:";
const MEMORY_LIMIT = 10_000;

/** 只依赖这四条命令：替身不必伪造整个客户端（同 @heron/redis 的 RedisPinger）。 */
export interface LoginThrottleStore {
  get(key: string): Promise<string | null>;
  set(key: string, value: string, token: "PX", ttl: number, nx: "NX"): Promise<unknown>;
  incr(key: string): Promise<number>;
  del(key: string): Promise<number>;
}

interface Bucket {
  count: number;
  resetAt: number;
}

/**
 * 登录失败计数：配了 Redis 就跨进程共享，没配就进程内。
 *
 * 只数失败、成功即清零；阈值与窗口写死——这是防撞库的最小门闩，不是可调业务参数。
 * Redis 只提速与共享，不是判据来源：断线退回进程内，谁也不许因此登不进来。
 */
@Injectable()
export class LoginThrottleService {
  private readonly logger = new Logger(LoginThrottleService.name);
  private readonly memory = new Map<string, Bucket>();

  constructor(@Inject(REDIS) private readonly redis: LoginThrottleStore | null) {}

  async assertAllowed(realm: string, email: string): Promise<void> {
    if ((await this.read(this.key(realm, email))) < MAX_FAILURES) return;
    throw new HttpException(
      { statusCode: HttpStatus.TOO_MANY_REQUESTS, message: "登录失败次数过多，请 15 分钟后再试" },
      HttpStatus.TOO_MANY_REQUESTS,
    );
  }

  /** 登录只结算一次：成功清零，失败加一。 */
  async settle(realm: string, email: string, ok: boolean): Promise<void> {
    const key = this.key(realm, email);
    if (ok) await this.clear(key);
    else await this.record(key);
  }

  /** 键里不放邮箱：Redis 的 MONITOR 与慢日志会把原始键打出来。 */
  private key(realm: string, email: string): string {
    const digest = createHash("sha256")
      .update(`${realm.trim().toLowerCase()}\n${email.trim().toLowerCase()}`)
      .digest("hex");
    return `${KEY_PREFIX}${digest.slice(0, 32)}`;
  }

  private async read(key: string): Promise<number> {
    const redis = this.redis;
    if (redis) {
      try {
        return Number(await redis.get(key)) || 0;
      } catch (cause) {
        this.warnDown(cause);
      }
    }
    return this.peekMemory(key);
  }

  private async record(key: string): Promise<void> {
    const redis = this.redis;
    if (redis) {
      try {
        // 先 NX 立好 TTL 再自增：不存在「有计数、没窗口」的永久锁死。
        await redis.set(key, "0", "PX", WINDOW_MS, "NX");
        await redis.incr(key);
        // 同写一份进程内镜像：Redis 中途断线时，这台机器的计数据不用从零开始。
        this.bumpMemory(key);
        return;
      } catch (cause) {
        this.warnDown(cause);
      }
    }
    this.bumpMemory(key);
  }

  private async clear(key: string): Promise<void> {
    const redis = this.redis;
    if (redis) {
      try {
        await redis.del(key);
      } catch (cause) {
        this.warnDown(cause);
      }
    }
    this.memory.delete(key);
  }

  /** 限流是可用性友好的闸：Redis 挂了退回进程内计数，接口照常可用。 */
  private warnDown(cause: unknown): void {
    this.logger.warn(
      `登录限流退回进程内计数：${cause instanceof Error ? cause.message : String(cause)}`,
    );
  }

  private peekMemory(key: string): number {
    const bucket = this.memory.get(key);
    if (!bucket) return 0;
    if (bucket.resetAt <= Date.now()) {
      this.memory.delete(key);
      return 0;
    }
    return bucket.count;
  }

  private bumpMemory(key: string): void {
    const now = Date.now();
    const bucket = this.memory.get(key);
    if (bucket && bucket.resetAt > now) {
      bucket.count += 1;
      return;
    }
    // shortcut: 进程内计数是尽力而为——超一万个键就整体清空，升级到带过期的 LRU 或始终配 Redis。
    if (this.memory.size >= MEMORY_LIMIT) this.memory.clear();
    this.memory.set(key, { count: 1, resetAt: now + WINDOW_MS });
  }
}
