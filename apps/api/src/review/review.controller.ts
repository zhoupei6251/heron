import { Body, Controller, Get, Inject, Param, ParseUUIDPipe, Post } from "@nestjs/common";
import {
  ConfirmBodySchema,
  type AuthUser,
  type ConfirmBody,
  type ReviewItem,
  type Task,
} from "@heron/contracts";

import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RequireAction } from "../common/decorators/require-action.decorator";
import { ZodValidationPipe } from "../common/pipes/zod-validation.pipe";
import { ReviewService } from "./review.service";

/**
 * 人的闸在 HTTP 上的两条路由，都收在 /reviews 一处：队列与确认是同一件事的两端，
 * 拆到 /tasks/:id/confirm 会让「谁在审、怎么确认」的接线散进两个模块。
 */
@Controller("reviews")
export class ReviewController {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(ReviewService) private readonly reviews: ReviewService) {}

  @Get("pending")
  pending(@CurrentUser() user: AuthUser): Promise<{ reviews: ReviewItem[] }> {
    return this.reviews.pending(user).then((reviews) => ({ reviews }));
  }

  @Post(":taskId/confirm")
  @RequireAction("review.confirm")
  confirm(
    @CurrentUser() user: AuthUser,
    @Param("taskId", new ParseUUIDPipe()) taskId: string,
    @Body(new ZodValidationPipe(ConfirmBodySchema)) body: ConfirmBody,
  ): Promise<Task> {
    return this.reviews.confirm(user, taskId, body);
  }
}
