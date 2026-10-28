import {
  Body,
  Controller,
  Get,
  Inject,
  Param,
  ParseUUIDPipe,
  Post,
  Query,
  Res,
} from "@nestjs/common";
import { z } from "zod";
import {
  CreateProtoBodySchema,
  PageQuerySchema,
  ProtoArtifactKindSchema,
  ProtoConfirmBodySchema,
  type AuthUser,
  type CreateProtoBody,
  type Page,
  type PageQuery,
  type ProtoArtifactKind,
  type ProtoConfirmBody,
  type ProtoEngineStatus,
  type ProtoProjectDetail,
  type ProtoProjectSummary,
} from "@heron/contracts";
import type { Response } from "express";

import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RequireAction } from "../common/decorators/require-action.decorator";
import { ZodValidationPipe } from "../common/pipes/zod-validation.pipe";
import { ProtoService } from "./proto.service";

/**
 * 原型工坊的路由。能力探针声明在 `:projectId` 之前——路由按声明顺序匹配，
 * 反了的话 /proto/capability 会被当成一个 id 是 "capability" 的项目。
 */
@Controller("proto")
export class ProtoController {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(ProtoService) private readonly proto: ProtoService) {}

  @Get()
  list(
    @CurrentUser() user: AuthUser,
    @Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery,
  ): Promise<Page<ProtoProjectSummary>> {
    return this.proto.list(user, query);
  }

  @Get("capability")
  capability(@CurrentUser() user: AuthUser): Promise<ProtoEngineStatus> {
    return this.proto.capability(user);
  }

  /**
   * 生成走 task.claim、确认走 review.confirm：与新闻线同一档位的人——
   * 一次生成花的是租户的模型额度与引擎时间，确认是人的闸，都不是只读账号该做的事。
   */
  @Post()
  @RequireAction("task.claim")
  create(
    @CurrentUser() user: AuthUser,
    @Body(new ZodValidationPipe(CreateProtoBodySchema)) body: CreateProtoBody,
  ): Promise<ProtoProjectDetail> {
    return this.proto.create(user, body);
  }

  @Get(":projectId")
  detail(
    @CurrentUser() user: AuthUser,
    @Param("projectId", new ParseUUIDPipe()) projectId: string,
  ): Promise<ProtoProjectDetail> {
    return this.proto.detail(user, projectId);
  }

  @Post(":projectId/generate")
  @RequireAction("task.claim")
  regenerate(
    @CurrentUser() user: AuthUser,
    @Param("projectId", new ParseUUIDPipe()) projectId: string,
  ): Promise<ProtoProjectDetail> {
    return this.proto.regenerate(user, projectId);
  }

  @Post(":projectId/confirm")
  @RequireAction("review.confirm")
  confirm(
    @CurrentUser() user: AuthUser,
    @Param("projectId", new ParseUUIDPipe()) projectId: string,
    @Body(new ZodValidationPipe(ProtoConfirmBodySchema)) body: ProtoConfirmBody,
  ): Promise<ProtoProjectDetail> {
    return this.proto.confirm(user, projectId, body);
  }

  @Get(":projectId/revisions/:version/artifacts/:kind")
  artifact(
    @CurrentUser() user: AuthUser,
    @Param("projectId", new ParseUUIDPipe()) projectId: string,
    // 版本号为正整数在这层判：0、小数、非数字都变成 400，而不是拼出一个谁都不存在的目录名
    @Param("version", new ZodValidationPipe(z.coerce.number().int().positive())) version: number,
    @Param("kind", new ZodValidationPipe(ProtoArtifactKindSchema)) kind: ProtoArtifactKind,
    @Res() res: Response,
  ): Promise<void> {
    return this.proto.serve(user, projectId, version, kind, res);
  }
}
