import {
  Inject,
  Injectable,
  Logger,
  type OnApplicationBootstrap,
  type OnApplicationShutdown,
} from "@nestjs/common";
import type { TaskEvent } from "@heron/contracts";
import { type Db, listTaskEventsSince, maxTaskEventId, withTenant } from "@heron/db/pg";
import { Observable, Subject } from "rxjs";

import { PG_DB } from "../db/pg";

const POLL_INTERVAL_MS = 1000;

/**
 * Nest 的 SSE 消息形状是 { type, id, data }：顶层 `type` 会变成 `event:` 行，
 * 浏览器只在有 `data:` 时才派发消息，所以 TaskEvent 必须整个装进 data。
 */
export type SseMessage = { data: TaskEvent };

/** 一个租户一条流：Subject 是广播口，cursor 是这个租户读到的台账位置。 */
interface TenantStream {
  readonly stream: Subject<SseMessage>;
  /** 订阅数：没人听的租户连库都不碰——轮询空转是在为不存在的页面花钱。 */
  subscribers: number;
  /** null = 还没起过游标（下个 tick 从当前最大 id 开始，不重播历史）。 */
  cursor: number | null;
}

/**
 * SSE 的每租户一条流。T6 之前它是**全局一个 Subject 广播所有租户的 task_events**——
 * 那是跨租户泄漏，不是「暂时凑合」：B 台的任务推进会出现在 A 台的浏览器里。
 * 现在租户坐标来自会话（guard 解出的 tid），流的隔离与查询的隔离（RLS）走同一个来源。
 *
 * worker 是独立进程，事件只进 DB，所以进度靠轮询 task_events 转成 SSE；
 * 账本永远是 DB，这里丢了连接、炸了轮询都不丢数据。
 */
@Injectable()
export class EventsService implements OnApplicationBootstrap, OnApplicationShutdown {
  private readonly logger = new Logger(EventsService.name);
  private readonly tenants = new Map<string, TenantStream>();
  private timer: NodeJS.Timeout | null = null;

  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(PG_DB) private readonly db: Db) {}

  /**
   * 一个连接一个订阅：订阅计数归零的租户不再轮询。
   * 游标留在原地，人回来时不重播走掉的那段——要看历史去读台账，SSE 只管「现在起」。
   */
  stream(tenantId: string): Observable<SseMessage> {
    const entry = this.entryFor(tenantId);
    return new Observable<SseMessage>((subscriber) => {
      entry.subscribers += 1;
      const inner = entry.stream.subscribe(subscriber);
      return () => {
        entry.subscribers -= 1;
        inner.unsubscribe();
      };
    });
  }

  onApplicationBootstrap(): void {
    this.timer = setInterval(() => void this.poll(), POLL_INTERVAL_MS);
    this.timer.unref();
  }

  onApplicationShutdown(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  private entryFor(tenantId: string): TenantStream {
    let entry = this.tenants.get(tenantId);
    if (!entry) {
      entry = { stream: new Subject<SseMessage>(), subscribers: 0, cursor: null };
      this.tenants.set(tenantId, entry);
    }
    return entry;
  }

  private async poll(): Promise<void> {
    for (const [tenantId, entry] of this.tenants) {
      if (entry.subscribers === 0) continue;
      try {
        if (entry.cursor === null) {
          entry.cursor = await withTenant(this.db, tenantId, (t) => maxTaskEventId(t));
          continue;
        }
        const cursor = entry.cursor;
        const entries = await withTenant(this.db, tenantId, (t) => listTaskEventsSince(t, cursor));
        for (const { id, event } of entries) {
          entry.cursor = id;
          entry.stream.next({ data: event });
        }
      } catch (cause) {
        // 轮询炸了不许拖倒进程：SSE 是进度展示不是账本，下一秒还会再试
        this.logger.warn(
          `租户 ${tenantId} 的台账轮询失败：${cause instanceof Error ? cause.message : String(cause)}`,
        );
      }
    }
  }
}
