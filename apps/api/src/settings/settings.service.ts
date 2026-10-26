import { Inject, Injectable } from "@nestjs/common";
import type { AuthUser } from "@heron/contracts";
import { type Db, getRetentionDays, setAuditedRetention, withTenant } from "@heron/db/pg";

import { PG_DB } from "../db/pg";

/**
 * 设置是「每个租户各存一份」（settings 的主键是 (tenant_id, key)）：
 * 哪怕将来界面上只有一个全局开关的错觉，落库也是按租户走的——共享开关在多租户里不存在。
 */
@Injectable()
export class SettingsService {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(PG_DB) private readonly db: Db) {}

  retentionDays(user: AuthUser): Promise<number> {
    return withTenant(this.db, user.tenantId, (t) => getRetentionDays(t));
  }

  /** 入参已被 RetentionBodySchema 校验过（正整数、上限 3650），这里不再兜第二层。 */
  async setRetention(user: AuthUser, days: number): Promise<number> {
    return withTenant(this.db, user.tenantId, (t) => setAuditedRetention(t, user.id, days));
  }
}
