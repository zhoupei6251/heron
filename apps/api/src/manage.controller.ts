import {
  Body,
  Controller,
  Get,
  Inject,
  Param,
  ParseUUIDPipe,
  Patch,
  Post,
  Put,
  Query,
  Res,
} from "@nestjs/common";
import { z } from "zod";
import {
  ArtifactKindSchema,
  CreateMemberBodySchema,
  FeedSourceBodySchema,
  PageQuerySchema,
  UpdateMemberBodySchema,
  WorkspaceBodySchema,
  type ArtifactKind,
  type AuthUser,
  type CreateMemberBody,
  type FeedSourceBody,
  type PageQuery,
  type UpdateMemberBody,
  type WorkspaceBody,
} from "@heron/contracts";
import {
  type Db,
  withTenant,
  workspaceOverview,
  listMembers,
  createManagedMember,
  updateMember,
  getWorkspace,
  updateWorkspace,
  listFeedSources,
  createFeedSource,
  updateFeedSource,
  listCandidates,
  listTaskSummaries,
  listAssets,
  listPublishes,
  listAudit,
  listUsage,
} from "@heron/db/pg";
import { hashPassword } from "@heron/domain";
import type { Response } from "express";
import { CurrentUser } from "./common/decorators/current-user.decorator";
import { RequireAction } from "./common/decorators/require-action.decorator";
import { ZodValidationPipe } from "./common/pipes/zod-validation.pipe";
import { PG_DB } from "./db/pg";
import { ArtifactService } from "./artifact.service";

@Controller("manage")
export class ManageController {
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(ArtifactService) private readonly artifacts: ArtifactService,
  ) {}

  @Get("overview")
  overview(@CurrentUser() user: AuthUser) {
    return withTenant(this.db, user.tenantId, workspaceOverview);
  }

  @Get("members")
  @RequireAction("user.manage")
  members(
    @CurrentUser() user: AuthUser,
    @Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery,
  ) {
    return withTenant(this.db, user.tenantId, (t) => listMembers(t, query));
  }

  @Post("members")
  @RequireAction("user.manage")
  createMember(
    @CurrentUser() user: AuthUser,
    @Body(new ZodValidationPipe(CreateMemberBodySchema)) body: CreateMemberBody,
  ) {
    const { password, ...safe } = body;
    const passwordHash = hashPassword(password);
    return withTenant(this.db, user.tenantId, (t) =>
      createManagedMember(t, user.id, { ...safe, passwordHash }),
    );
  }

  @Patch("members/:id")
  @RequireAction("user.manage")
  updateMember(
    @CurrentUser() user: AuthUser,
    @Param("id", new ParseUUIDPipe()) id: string,
    @Body(new ZodValidationPipe(UpdateMemberBodySchema)) body: UpdateMemberBody,
  ) {
    return withTenant(this.db, user.tenantId, (t) => updateMember(t, user.id, id, body));
  }

  @Get("workspace")
  workspace(@CurrentUser() user: AuthUser) {
    return withTenant(this.db, user.tenantId, getWorkspace);
  }

  @Patch("workspace")
  @RequireAction("settings.write")
  updateWorkspace(
    @CurrentUser() user: AuthUser,
    @Body(new ZodValidationPipe(WorkspaceBodySchema)) body: WorkspaceBody,
  ) {
    return withTenant(this.db, user.tenantId, (t) => updateWorkspace(t, user.id, body));
  }

  @Get("feeds")
  @RequireAction("settings.write")
  feeds(
    @CurrentUser() user: AuthUser,
    @Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery,
  ) {
    return withTenant(this.db, user.tenantId, (t) => listFeedSources(t, query));
  }

  @Post("feeds")
  @RequireAction("settings.write")
  createFeed(
    @CurrentUser() user: AuthUser,
    @Body(new ZodValidationPipe(FeedSourceBodySchema)) body: FeedSourceBody,
  ) {
    return withTenant(this.db, user.tenantId, (t) => createFeedSource(t, user.id, body));
  }

  @Put("feeds/:id")
  @RequireAction("settings.write")
  updateFeed(
    @CurrentUser() user: AuthUser,
    @Param("id", new ParseUUIDPipe()) id: string,
    @Body(new ZodValidationPipe(FeedSourceBodySchema)) body: FeedSourceBody,
  ) {
    return withTenant(this.db, user.tenantId, (t) => updateFeedSource(t, user.id, id, body));
  }

  @Get("candidates")
  candidates(
    @CurrentUser() user: AuthUser,
    @Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery,
  ) {
    return withTenant(this.db, user.tenantId, (t) => listCandidates(t, query));
  }

  @Get("tasks")
  tasks(
    @CurrentUser() user: AuthUser,
    @Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery,
  ) {
    return withTenant(this.db, user.tenantId, (t) => listTaskSummaries(t, query));
  }

  @Get("assets")
  assets(
    @CurrentUser() user: AuthUser,
    @Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery,
  ) {
    return withTenant(this.db, user.tenantId, (t) => listAssets(t, query));
  }

  @Get("publishes")
  publishes(
    @CurrentUser() user: AuthUser,
    @Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery,
  ) {
    return withTenant(this.db, user.tenantId, (t) => listPublishes(t, query));
  }

  @Get("audit")
  @RequireAction("settings.write")
  audit(
    @CurrentUser() user: AuthUser,
    @Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery,
  ) {
    return withTenant(this.db, user.tenantId, (t) => listAudit(t, query));
  }

  @Get("usage")
  @RequireAction("usage.read")
  usage(@CurrentUser() user: AuthUser) {
    return withTenant(this.db, user.tenantId, listUsage);
  }

  @Get("artifacts/:taskId/:kind")
  artifact(
    @CurrentUser() user: AuthUser,
    @Param("taskId", new ParseUUIDPipe()) taskId: string,
    @Param("kind", new ZodValidationPipe(ArtifactKindSchema)) kind: ArtifactKind,
    @Query("version", new ZodValidationPipe(z.coerce.number().int().positive())) version: number,
    @Res() res: Response,
  ): Promise<void> {
    return this.artifacts.serve(user, taskId, kind, version, res);
  }
}
