import {
  Catch,
  HttpException,
  Logger,
  type ArgumentsHost,
  type ExceptionFilter,
} from "@nestjs/common";
import { ManagementError } from "@heron/db/pg";
import type { Response } from "express";

/** Never serialize database errors: Drizzle errors can include SQL parameters and hashes. */
@Catch()
export class ApiExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger(ApiExceptionFilter.name);

  catch(error: unknown, host: ArgumentsHost): void {
    const res = host.switchToHttp().getResponse<Response>();
    if (res.headersSent) {
      res.destroy();
      return;
    }
    if (error instanceof ManagementError) {
      const replies = {
        not_found: { statusCode: 404, message: "当前工作空间中未找到该记录或产物" },
        conflict: {
          statusCode: 409,
          message: "记录已存在或状态已变化，请刷新后重试；必须保留至少一位有效所有者",
        },
        forbidden: {
          statusCode: 403,
          message: "工作空间已停用或权限不足；不能停用或降级自身所有者账号",
        },
      };
      const reply = replies[error.code];
      res.status(reply.statusCode).json(reply);
      return;
    }
    if (error instanceof HttpException) {
      const body = error.getResponse();
      res
        .status(error.getStatus())
        .json(typeof body === "string" ? { statusCode: error.getStatus(), message: body } : body);
      return;
    }
    this.logger.error("API 请求失败：内部操作异常（不记录请求体、SQL 或凭据）");
    res.status(500).json({ statusCode: 500, message: "内部操作失败，请稍后重试" });
  }
}
