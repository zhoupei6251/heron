import { Controller, Inject, Sse } from "@nestjs/common";
import type { AuthUser } from "@heron/contracts";
import { Observable } from "rxjs";

import { CurrentUser } from "../common/decorators/current-user.decorator";
import { EventsService, type SseMessage } from "./events.service";

@Controller("events")
export class EventsController {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(EventsService) private readonly events: EventsService) {}

  /**
   * 这条路由没有 @Public：流的租户来自会话（guard 解出的 tid），
   * 匿名连不上 SSE 不是限制，是隔离本身——全局守卫挡不住的话，一个 Subject 就把
   * 所有租户的 task_events 广播出去了。
   * 断线由浏览器自动重连；事件本身靠 DB 游标补，不靠连接里的内存。
   */
  @Sse()
  stream(@CurrentUser() user: AuthUser): Observable<SseMessage> {
    return this.events.stream(user.tenantId);
  }
}
