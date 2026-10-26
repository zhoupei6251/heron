import { Module } from "@nestjs/common";
import { APP_FILTER, APP_GUARD } from "@nestjs/core";
import { VIDEOS_DIR } from "@heron/db";
import { PgModule } from "./db/pg";
import { ManageController } from "./manage.controller";
import { ArtifactService, ARTIFACT_ROOT } from "./artifact.service";
import { PlatformModule } from "./platform.module";
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
    PlatformModule,
    AuthModule,
    TasksModule,
    ReviewModule,
    GatesModule,
    SettingsModule,
    EventsModule,
    HealthModule,
  ],
  controllers: [ManageController],
  providers: [
    ArtifactService,
    { provide: ARTIFACT_ROOT, useValue: VIDEOS_DIR },
    { provide: APP_FILTER, useClass: ApiExceptionFilter },
    { provide: APP_GUARD, useClass: JwtAuthGuard },
    { provide: APP_GUARD, useClass: RolesGuard },
  ],
})
export class AppModule {}
