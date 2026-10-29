import { BadRequestException, Inject, Injectable } from "@nestjs/common";
import type { AuthUser, GateVerdict, Task } from "@heron/contracts";
import { NEWS_CAPABILITY } from "@heron/capability-news";
import {
  type Db,
  claimByHuman,
  createCandidate,
  createTask,
  findCandidateByNormKey,
  getTask,
  hasPublishedTaskForCandidate,
  listTasks,
  markFailed,
  ManagementError,
  requireActiveActor,
  startProductionFromCandidate,
  withTenant,
} from "@heron/db/pg";
import { nowIso } from "@heron/db";
import { dedupeVerdict, formatErrorReason, normKey } from "@heron/domain";

import { PG_DB } from "../db/pg";
import { TaskQueueService } from "../redis/redis.module";

/**
 * 任务读写（pg 数据面）。每个请求先由会话定租户：withTenant 的那句 set_config 是
 * 本租户能看见什么、写什么的唯一开关，服务层不再拿裸连接——拿到的就是租户内的视角。
 */

@Injectable()
export class TasksService {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(
    @Inject(PG_DB) private readonly db: Db,
    @Inject(TaskQueueService) private readonly queue: TaskQueueService,
  ) {}

  list(user: AuthUser): Promise<Task[]> {
    return withTenant(this.db, user.tenantId, (t) => listTasks(t));
  }

  /**
   * 建任务。去重闸在这里判：拒了不是静默返回已有那条，而是留一条 failed 的任务——
   * 界面要能展开「为什么拒」，一条不存在的记录展示不出任何东西。
   */
  create(user: AuthUser, input: { title: string }): Promise<Task> {
    const key = normKey(input.title);
    return withTenant(this.db, user.tenantId, async (t) => {
      await requireActiveActor(t, user.id, "task.claim");
      const existing = await findCandidateByNormKey(t, key);
      const verdict: GateVerdict = dedupeVerdict({
        normKey: key,
        alreadyPublished: existing !== null && (await hasPublishedTaskForCandidate(t, existing)),
      });
      if (!verdict.passed) {
        const blocked = await createTask(t, {
          capability: NEWS_CAPABILITY,
          candidateId: existing,
        });
        await markFailed(t, blocked.id, formatErrorReason(verdict), nowIso(), user.id);
        return (await getTask(t, blocked.id)) ?? blocked;
      }
      const candidateId =
        existing ?? (await createCandidate(t, { title: input.title, summary: "", heat: 0 }));
      return createTask(t, { capability: NEWS_CAPABILITY, candidateId });
    });
  }

  /**
   * 从候选开始生产：一条候选一条在途任务，重复点由仓储用候选行锁挡住。
   * 推队列排在事务之后，理由同 claim()：账本先落定，worker 才有活可领。
   */
  async startProduction(user: AuthUser, candidateId: string): Promise<Task> {
    let task: Task;
    try {
      task = await withTenant(this.db, user.tenantId, async (t) => {
        await requireActiveActor(t, user.id, "task.claim");
        return startProductionFromCandidate(t, candidateId, user.id, NEWS_CAPABILITY);
      });
    } catch (cause) {
      if (cause instanceof ManagementError) throw cause;
      throw new BadRequestException(
        "开始生产失败：候选不存在、状态已变化或操作人不可用，请刷新后重试",
      );
    }
    await this.queue.push(task.id);
    return task;
  }

  /**
   * 人认领。认领人就是会话里的人（user.id）——请求体里谁写什么算谁的时代在 T7 结束：
   * claimed_by 是 uuid 外键，指到的是回库查过归属的那个真人。
   *
   * 投递排在事务之后：认领这件事已经写进 Postgres 了才推队列，
   * 反过来的话 worker 可能先领到一条还没被认领的活，白跑一趟还得等兜底轮询。
   */
  async claim(user: AuthUser, taskId: string): Promise<Task> {
    let task: Task;
    try {
      task = await withTenant(this.db, user.tenantId, async (t) => {
        await requireActiveActor(t, user.id, "task.claim");
        return claimByHuman(t, taskId, user.id);
      });
    } catch (cause) {
      if (cause instanceof ManagementError) throw cause;
      throw new BadRequestException("认领失败：任务不存在、状态已变化或操作人不可用，请刷新后重试");
    }
    await this.queue.push(task.id);
    return task;
  }
}
