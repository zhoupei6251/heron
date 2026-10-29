import {
  Body,
  Controller,
  Delete,
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
  quotaStatus,
  listSessions,
  revokeSession,
} from "@heron/db/pg";
import { can, hashPassword } from "@heron/domain";
import type { Response } from "express";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RequireAction } from "../common/decorators/require-action.decorator";
import { ZodValidationPipe } from "../common/pipes/zod-validation.pipe";
import { PG_DB } from "../db/pg";
import { ArtifactService } from "./artifact.service";
import { FeedService } from "./feed.service";

@Controller("manage")
export class ManageController {
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(ArtifactService) private readonly artifacts: ArtifactService,
    @Inject(FeedService) private readonly feedService: FeedService,
  ) {}

  @Get("overview")
  overview(@CurrentUser() user: AuthUser) {
    return withTenant(this.db, user.tenantId, workspaceOverview);
  }

  /** 任何成员都能看自己的额度：写稿的人得知道这个月还能不能再渲一条。 */
  @Get("quota")
  quota(@CurrentUser() user: AuthUser) {
    return withTenant(this.db, user.tenantId, (t) => quotaStatus(t));
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

  /**
   * 在线会话。没有 session.manage 的人只拿到自己的那几条——过滤在查询里，不在界面上。
   * current 由服务端比对 ticket 的 sid，界面不用（也不能）自己判「这条是不是我」。
   */
  @Get("sessions")
  sessions(
    @CurrentUser() user: AuthUser,
    @Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery,
  ) {
    return withTenant(this.db, user.tenantId, (t) =>
      listSessions(
        t,
        { id: user.id, canManage: can(user.role, "session.manage") },
        query,
        user.sessionId,
      ),
    );
  }

  /** 单条强退。自己的那条谁都能退（那是退出登录），别人的要 session.manage——判据在仓库里再判一次。 */
  @Delete("sessions/:id")
  revoke(@CurrentUser() user: AuthUser, @Param("id", new ParseUUIDPipe()) id: string) {
    return withTenant(this.db, user.tenantId, (t) => revokeSession(t, user.id, id));
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

  @Post("feeds/:id/fetch")
  @RequireAction("settings.write")
  fetchFeed(@CurrentUser() user: AuthUser, @Param("id", new ParseUUIDPipe()) id: string) {
    return this.feedService.fetchNow(user, id);
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
