import { Inject, Injectable } from "@nestjs/common";
import type { AuthUser, ModelSettingsBody } from "@heron/contracts";
import {
  type Db,
  type ModelSettings,
  getModelSettings,
  getRetentionDays,
  setAuditedModel,
  setAuditedRetention,
  withTenant,
} from "@heron/db/pg";
import { DEFAULT_MODEL_BASE_URL } from "@heron/db";

import { PG_DB } from "../db/pg";

/** 界面拿到的模型配置：只回答「配没配密钥」，密钥本身不出库。 */
export interface ModelSettingsView {
  baseUrl: string;
  model: string;
  hasApiKey: boolean;
}

function view(stored: ModelSettings): ModelSettingsView {
  return {
    baseUrl: stored.baseUrl ?? DEFAULT_MODEL_BASE_URL,
    model: stored.model ?? "",
    hasApiKey: stored.apiKey !== null,
  };
}

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

  model(user: AuthUser): Promise<ModelSettingsView> {
    return withTenant(this.db, user.tenantId, (t) => getModelSettings(t)).then(view);
  }

  async setModel(user: AuthUser, body: ModelSettingsBody): Promise<ModelSettingsView> {
    return withTenant(this.db, user.tenantId, async (t) => {
      await setAuditedModel(t, user.id, body);
      return view(await getModelSettings(t));
    });
  }
}
