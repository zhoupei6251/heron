import { Module } from "@nestjs/common";
import { VIDEOS_DIR } from "@heron/db";
import { createObjectStore } from "@heron/storage";

import { PgModule } from "../db/pg";
import { ARTIFACT_ROOT, ArtifactService, OBJECT_STORE } from "./artifact.service";
import { FeedService } from "./feed.service";
import { ManageController } from "./manage.controller";

/** 管理台读写面：控制器只做传输，产物路径校验在 ArtifactService、抓取在 FeedService。 */
@Module({
  imports: [PgModule],
  controllers: [ManageController],
  providers: [
    ArtifactService,
    FeedService,
    { provide: ARTIFACT_ROOT, useValue: VIDEOS_DIR },
    // 凭据只从环境变量读（见 @heron/storage 的 config）；这里不落库、不进镜像。
    { provide: OBJECT_STORE, useValue: createObjectStore() },
  ],
})
export class ManageModule {}
