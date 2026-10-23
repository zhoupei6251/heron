import { Body, Controller, Get, Inject, Put } from "@nestjs/common";
import { type AuthUser, type RetentionBody, RetentionBodySchema } from "@heron/contracts";

import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RequireAction } from "../common/decorators/require-action.decorator";
import { ZodValidationPipe } from "../common/pipes/zod-validation.pipe";
import { SettingsService } from "./settings.service";

@Controller("settings")
export class SettingsController {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(SettingsService) private readonly settings: SettingsService) {}

  @Get()
  state(@CurrentUser() user: AuthUser): Promise<{ retentionDays: number }> {
    return this.settings.retentionDays(user).then((retentionDays) => ({ retentionDays }));
  }

  @Put("retention")
  @RequireAction("settings.write")
  setRetention(
    @CurrentUser() user: AuthUser,
    @Body(new ZodValidationPipe(RetentionBodySchema)) body: RetentionBody,
  ): Promise<{ retentionDays: number }> {
    return this.settings.setRetention(user, body.days).then((retentionDays) => ({ retentionDays }));
  }
}
