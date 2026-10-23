import { BadRequestException, ConflictException, Inject, Injectable } from "@nestjs/common";
import type { AuthUser, ReviewItem, Task } from "@heron/contracts";
import { type Db, confirmByHuman, listReviewQueue, withTenant } from "@heron/db/pg";
import { GateRefusalError } from "@heron/domain";

import { PG_DB } from "../db/pg";

/**
 * 人工确认这一侧的服务：队列给 reviewer 看，确认把任务送进 approved。
 * 确认人只来自会话（user.id）；指纹来自请求体，但只用来与当前版本比对——
 * 比对不通过是状态冲突（409），语义上是「你确认的那一版已经不是要发的那一版」。
 */
@Injectable()
export class ReviewService {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(PG_DB) private readonly db: Db) {}

  pending(user: AuthUser): Promise<ReviewItem[]> {
    return withTenant(this.db, user.tenantId, (t) => listReviewQueue(t));
  }

  async confirm(
    user: AuthUser,
    taskId: string,
    input: { contactSheetFingerprint: string },
  ): Promise<Task> {
    interface Refusal {
      ok: false;
      message: string;
    }
    type Outcome = { ok: true; task: Task } | Refusal;

    let outcome: Outcome;
    try {
      outcome = await withTenant(this.db, user.tenantId, async (t): Promise<Outcome> => {
        try {
          return {
            ok: true,
            task: await confirmByHuman(t, {
              taskId,
              userId: user.id,
              contactSheetFingerprint: input.contactSheetFingerprint,
            }),
          };
        } catch (cause) {
          // catch 必须写在事务回调里面：闸拒时 gate_results 与 stage=failed 已经写在同一事务里，
          // 让 GateRefusalError 穿出去，回滚会把这点审计痕一起抹掉——那点痕是审计要求，不是日志。
          if (cause instanceof GateRefusalError) {
            return { ok: false, message: cause.message };
          }
          throw cause;
        }
      });
    } catch (cause) {
      // 非闸拒（任务不存在、状态机不许、人已停用）没有要留的痕，回滚之后再翻成 400
      throw new BadRequestException(
        `确认失败：${cause instanceof Error ? cause.message : String(cause)}`,
      );
    }
    // 闸拒要把原因原文带回界面（规格书：失败必须报原因），409 与 400 都带 message
    if (!outcome.ok) throw new ConflictException(outcome.message);
    return outcome.task;
  }
}
