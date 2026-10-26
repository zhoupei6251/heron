import { Module } from "@nestjs/common";
import { APP_FILTER, APP_GUARD } from "@nestjs/core";
import { PgModule } from "./db/pg";
import { RedisModule } from "./redis/redis.module";
import { ManageModule } from "./manage/manage.module";
import { PlatformModule } from "./platform/platform.module";
import { ApiExceptionFilter } from "./common/api-exception.filter";

import { AuthModule } from "./auth/auth.module";
import { JwtAuthGuard } from "./common/guards/jwt-auth.guard";
import { RolesGuard } from "./common/guards/roles.guard";
import { EventsModule } from "./events/events.module";
import { GatesModule } from "./gates/gates.module";
import { HealthModule } from "./health/health.module";
import { ReviewModule } from "./review/review.module";
import { SettingsModule } from "./settings/settings.module";
import { TasksModule } from "./tasks/tasks.module";

/**
 * 全局两道闸，顺序即语义：先认人（JwtAuthGuard 写 req.user），再判权限（RolesGuard 读它）。
 * 反了的症状是每条受保护的路由都 401，而 @Public 的路由反倒可能被权限表挡住——
 * 因为注册顺序就是执行顺序，这里没有别的机制能纠正它。
 */
@Module({
  imports: [
    PgModule,
    RedisModule,
    PlatformModule,
    ManageModule,
    AuthModule,
    TasksModule,
    ReviewModule,
    GatesModule,
    SettingsModule,
    EventsModule,
    HealthModule,
  ],
  providers: [
    { provide: APP_FILTER, useClass: ApiExceptionFilter },
    { provide: APP_GUARD, useClass: JwtAuthGuard },
    { provide: APP_GUARD, useClass: RolesGuard },
  ],
})
export class AppModule {}
