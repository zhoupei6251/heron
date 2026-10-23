import { Module } from "@nestjs/common";

import { ReviewController } from "./review.controller";
import { ReviewService } from "./review.service";

/**
 * 审片台的队列与确认。PgModule 是 @Global 的，这里不需要再 import——
 * 数据面在整个 api 进程里只有一份（apps/api/src/db/pg.ts）。
 */
@Module({ controllers: [ReviewController], providers: [ReviewService] })
export class ReviewModule {}
