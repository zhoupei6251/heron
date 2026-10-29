import { existsSync } from "node:fs";
import { join } from "node:path";
import {
  Body,
  Controller,
  Get,
  Inject,
  Param,
  Patch,
  Post,
  Put,
  Query,
  ServiceUnavailableException,
  UseGuards,
} from "@nestjs/common";
import {
  ConsoleMenuConfigSchema,
  CreateTenantBodySchema,
  PageQuerySchema,
  PlatformSettingsBodySchema,
  TenantPlanBodySchema,
  TenantStatusBodySchema,
  type ConsoleMenuConfig,
  type CreateTenantBody,
  type PageQuery,
  type PlatformSettingsBody,
  type PlatformUser,
  type TenantPlanBody,
  type TenantStatusBody,
} from "@heron/contracts";
import {
  type Db,
  getConsoleMenuConfig,
  listPlatformTenants,
  provisionTenant,
  changeTenantPlan,
  changeTenantStatus,
  platformUsage,
  listPlatformAudit,
  getPlatformSettings,
  writePlatformSettings,
  writeConsoleMenuConfig,
  dbPing,
} from "@heron/db/pg";
import { probeEngine, probeTemplate, type EngineStatus, type TemplateStatus } from "@heron/db";
import { hashPassword, publishBlockers, publishGateOpen } from "@heron/domain";
import { probeRedis } from "@heron/redis";
import { Public } from "../common/decorators/public.decorator";
import { ZodValidationPipe } from "../common/pipes/zod-validation.pipe";
import { WEB_DIST } from "../config/runtime";
import { PG_DB } from "../db/pg";
import { REDIS, type RedisHandle } from "../redis/redis.module";
import type { HealthReport } from "../health/health.controller";
import { CurrentPlatformUser, PlatformGuard } from "./platform.guard";
import { SnowflakeIdPipe } from "../common/pipes/snowflake-id.pipe";

export interface PlatformHealthReport extends HealthReport {
  /** 含部署路径，仅平台可见；原型依赖不参与整体就绪判定，新闻线不依赖它们。 */
  proto: { engine: EngineStatus; template: TemplateStatus };
}

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
    @Param("id", new SnowflakeIdPipe()) id: string,
    @Body(new ZodValidationPipe(TenantStatusBodySchema)) body: TenantStatusBody,
  ) {
    return changeTenantStatus(this.db, user.id, id, body.suspended);
  }

  @Patch("tenants/:id/plan")
  plan(
    @CurrentPlatformUser() user: PlatformUser,
    @Param("id", new SnowflakeIdPipe()) id: string,
    @Body(new ZodValidationPipe(TenantPlanBodySchema)) body: TenantPlanBody,
  ) {
    return changeTenantPlan(this.db, user.id, id, body.plan);
  }

  @Get("settings")
  settings() {
    return getPlatformSettings(this.db);
  }

  /** 写入留痕在 platform_audit_logs（platform_setting.updated），一次改动一行。 */
  @Put("settings")
  updateSettings(
    @CurrentPlatformUser() user: PlatformUser,
    @Body(new ZodValidationPipe(PlatformSettingsBodySchema)) body: PlatformSettingsBody,
  ) {
    return writePlatformSettings(this.db, user.id, body.values);
  }

  /** 写平台设置时 `console_menus` 不在标量设置名单里，单独走这两条：它是整棵树，不是一格文本。 */
  @Get("menus")
  menus() {
    return getConsoleMenuConfig(this.db);
  }

  @Put("menus")
  updateMenus(
    @CurrentPlatformUser() user: PlatformUser,
    @Body(new ZodValidationPipe(ConsoleMenuConfigSchema)) body: ConsoleMenuConfig,
  ) {
    return writeConsoleMenuConfig(this.db, user.id, body);
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
  async health(): Promise<PlatformHealthReport> {
    let databaseOk: boolean;
    try {
      databaseOk = Boolean(await dbPing(this.db));
    } catch {
      databaseOk = false;
    }
    const webOk = existsSync(join(WEB_DIST, "index.html"));
    const report: PlatformHealthReport = {
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
      proto: { engine: probeEngine(), template: probeTemplate() },
    };
    if (!report.ok) throw new ServiceUnavailableException(report);
    return report;
  }
}
