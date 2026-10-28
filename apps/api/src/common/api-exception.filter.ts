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
      // ManagementError 的 message 是仓储里写死的那几句话（不是驱动异常，不带 SQL 与参数），
      // 而它正是「为什么被拒」的唯一来源：换成通用文案，界面就只能显示"操作失败"。
      const status = { not_found: 404, conflict: 409, forbidden: 403 }[error.code];
      res.status(status).json({ statusCode: status, message: error.message });
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
