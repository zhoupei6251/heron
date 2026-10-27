import { Module } from "@nestjs/common";
import { VIDEOS_DIR } from "@heron/db";

import { PgModule } from "../db/pg";
import { ARTIFACT_ROOT, ArtifactService } from "./artifact.service";
import { FeedService } from "./feed.service";
import { ManageController } from "./manage.controller";

/** 管理台读写面：控制器只做传输，产物路径校验在 ArtifactService、抓取在 FeedService。 */
@Module({
  imports: [PgModule],
  controllers: [ManageController],
  providers: [ArtifactService, FeedService, { provide: ARTIFACT_ROOT, useValue: VIDEOS_DIR }],
})
export class ManageModule {}
