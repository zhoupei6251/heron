import { Body, Controller, Get, Inject, Param, Post } from "@nestjs/common";
import {
  CreateFakeTaskInputSchema,
  type AuthUser,
  type CreateFakeTaskInput,
  type Task,
} from "@heron/contracts";

import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RequireAction } from "../common/decorators/require-action.decorator";
import { ZodValidationPipe } from "../common/pipes/zod-validation.pipe";
import { TasksService } from "./tasks.service";

/**
 * 任务的路由。认领不再收请求体：以前 body 里的 owner 是自由文本（“web-ui”），
 * 谁都能替别人认领；现在认领人只来自会话，接口上没有第二个入口能塞值进来。
 */
@Controller("tasks")
export class TasksController {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(TasksService) private readonly tasks: TasksService) {}

  @Get()
  list(@CurrentUser() user: AuthUser): Promise<{ tasks: Task[] }> {
    return this.tasks.list(user).then((tasks) => ({ tasks }));
  }

  @Post()
  create(
    @CurrentUser() user: AuthUser,
    @Body(new ZodValidationPipe(CreateFakeTaskInputSchema)) input: CreateFakeTaskInput,
  ): Promise<Task> {
    return this.tasks.create(user, input);
  }

  @Post(":id/claim")
  @RequireAction("task.claim")
  claim(@CurrentUser() user: AuthUser, @Param("id") id: string): Promise<Task> {
    return this.tasks.claim(user, id);
  }
}
