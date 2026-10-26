import { Controller, Get, Inject, ServiceUnavailableException } from "@nestjs/common";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { type Db, dbPing } from "@heron/db/pg";
import { publishBlockers, publishGateOpen } from "@heron/domain";
import { probeRedis, type RedisProbeResult } from "@heron/redis";

import { Public } from "../common/decorators/public.decorator";
import { WEB_DIST } from "../config/runtime";
import { PG_DB } from "../db/pg";
import { REDIS, type RedisHandle } from "../redis/redis.module";

interface Probe {
  ok: boolean;
  detail: string;
}

export interface HealthReport {
  ok: boolean;
  at: string;
  node: string;
  db: Probe;
  webDist: Probe;
  /** 未配置时 ok=true、configured=false：没这东西不代表坏了，页面要能分清两种「绿」。 */
  redis: RedisProbeResult;
  /** 门禁位不影响 ok：进程健康 ≠ 允许发布，混在一起会把「闸还没搬齐」误报成「服务挂了」。 */
  publish: { open: boolean; blockers: string[] };
}

/**
 * 库探针走 api 自己那个池。不做「写一行再读回」了：多租户库里写探针得先回答
 * 「写哪个租户的哪张表」——那不是健康检查该做的决定；写路径每秒都在被业务请求走到，
 * 探针只负责「池活着、库答话」。driver 名（pglite / postgres）一并报出，
 * 「探针打的是内存库」这种事必须在健康报告里一眼可见。
 */
async function probeDb(db: Db): Promise<Probe> {
  try {
    const version = await dbPing(db);
    if (!version) return { ok: false, detail: "库答了话但没报版本号，探针结果异常" };
    return { ok: true, detail: `${db.driver} · ${version}` };
  } catch (cause) {
    return {
      ok: false,
      detail: `探针失败：${cause instanceof Error ? cause.message : String(cause)}`,
    };
  }
}

function probeWebDist(): Probe {
  const index = join(WEB_DIST, "index.html");
  return existsSync(index)
    ? { ok: true, detail: index }
    : { ok: false, detail: `${index} 不存在，先跑 pnpm web:build` };
}

@Controller("health")
export class HealthController {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(REDIS) private readonly redis: RedisHandle,
  ) {}

  /**
   * 全局守卫默认要求会话，这一条挂 @Public：compose 与 nginx 的探针在没有任何登录态的
   * 情况下必须打得到它，否则健康检查自己就成了第一个 401。
   * 公开它不泄露租户数据——报的是进程健康、库通不通、前端产物在不在，一行租户内容都没有。
   */
  @Public()
  @Get()
  async check(): Promise<HealthReport> {
    const report: HealthReport = {
      ok: false,
      at: new Date().toISOString(),
      node: process.versions.node,
      db: await probeDb(this.db),
      webDist: probeWebDist(),
      redis: await probeRedis(this.redis),
      publish: { open: publishGateOpen(), blockers: publishBlockers() },
    };
    // Redis 不进 ok：登录限流退回进程内计数、任务派发退回 worker 轮询，两边都有不依赖它的兜底，
    // 挂了不该让 compose 把整个 api 判死。哪天有东西只落 Redis（没有第二条路），这个判定要跟着改。
    report.ok = report.db.ok && report.webDist.ok;
    // 不健康时带整份报告抛 503，理由必须在响应体里，不能只剩一个状态码。
    if (!report.ok) throw new ServiceUnavailableException(report);
    return report;
  }
}
