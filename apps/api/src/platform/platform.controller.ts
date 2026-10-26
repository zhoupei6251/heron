import { existsSync } from "node:fs";
import { join } from "node:path";
import {
  Body,
  Controller,
  Get,
  Inject,
  Param,
  ParseUUIDPipe,
  Patch,
  Post,
  Query,
  ServiceUnavailableException,
  UseGuards,
} from "@nestjs/common";
import {
  CreateTenantBodySchema,
  PageQuerySchema,
  TenantStatusBodySchema,
  type CreateTenantBody,
  type PageQuery,
  type PlatformUser,
  type TenantStatusBody,
} from "@heron/contracts";
import {
  type Db,
  listPlatformTenants,
  provisionTenant,
  changeTenantStatus,
  platformUsage,
  listPlatformAudit,
  dbPing,
} from "@heron/db/pg";
import { hashPassword, publishBlockers, publishGateOpen } from "@heron/domain";
import { probeRedis } from "@heron/redis";
import { Public } from "../common/decorators/public.decorator";
import { ZodValidationPipe } from "../common/pipes/zod-validation.pipe";
import { WEB_DIST } from "../config/runtime";
import { PG_DB } from "../db/pg";
import { REDIS, type RedisHandle } from "../redis/redis.module";
import type { HealthReport } from "../health/health.controller";
import { CurrentPlatformUser, PlatformGuard } from "./platform.guard";

// @Public bypasses ONLY the tenant guard. Every endpoint in this class requires
// PlatformGuard, with no owner fallback and no per-route anonymous exceptions.
// Login/me/logout live in the unified /auth controller: one cookie, two audiences.
@Public()
@UseGuards(PlatformGuard)
@Controller("platform")
export class PlatformController {
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(REDIS) private readonly redis: RedisHandle,
  ) {}

  @Get("tenants")
  tenants(@Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery) {
    return listPlatformTenants(this.db, query);
  }

  @Post("tenants")
  provision(
    @CurrentPlatformUser() user: PlatformUser,
    @Body(new ZodValidationPipe(CreateTenantBodySchema)) body: CreateTenantBody,
  ) {
    const { ownerPassword, ...safe } = body;
    return provisionTenant(this.db, user.id, {
      ...safe,
      ownerPasswordHash: hashPassword(ownerPassword),
    });
  }

  @Patch("tenants/:id/status")
  status(
    @CurrentPlatformUser() user: PlatformUser,
    @Param("id", new ParseUUIDPipe()) id: string,
    @Body(new ZodValidationPipe(TenantStatusBodySchema)) body: TenantStatusBody,
  ) {
    return changeTenantStatus(this.db, user.id, id, body.suspended);
  }

  @Get("usage")
  usage() {
    return platformUsage(this.db);
  }

  @Get("audit")
  audit(@Query(new ZodValidationPipe(PageQuerySchema)) query: PageQuery) {
    return listPlatformAudit(this.db, query);
  }

  @Get("health")
  async health(): Promise<HealthReport> {
    let databaseOk: boolean;
    try {
      databaseOk = Boolean(await dbPing(this.db));
    } catch {
      databaseOk = false;
    }
    const webOk = existsSync(join(WEB_DIST, "index.html"));
    const report: HealthReport = {
      ok: databaseOk && webOk,
      at: new Date().toISOString(),
      node: process.versions.node,
      db: {
        ok: databaseOk,
        detail: databaseOk ? `${this.db.driver} 连接正常` : "数据库不可用",
      },
      webDist: { ok: webOk, detail: webOk ? "前端产物存在" : "前端产物未构建" },
      redis: await probeRedis(this.redis),
      publish: { open: publishGateOpen(), blockers: publishBlockers() },
    };
    if (!report.ok) throw new ServiceUnavailableException(report);
    return report;
  }
}
