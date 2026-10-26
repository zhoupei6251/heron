import {
  createParamDecorator,
  Inject,
  Injectable,
  UnauthorizedException,
  type CanActivate,
  type ExecutionContext,
} from "@nestjs/common";

import type { ConsoleSession } from "@heron/contracts";

import { extractSessionCookie } from "./jwt.strategy";
import { ConsoleAuthService } from "./console-auth.service";

interface ConsoleRequest {
  cookies?: Record<string, string | undefined>;
  consoleSession?: ConsoleSession;
}

export const CurrentSession = createParamDecorator(
  (_data: unknown, context: ExecutionContext): ConsoleSession => {
    const session = context.switchToHttp().getRequest<ConsoleRequest>().consoleSession;
    if (!session) throw new UnauthorizedException("没有会话身份：守卫没解出会话");
    return session;
  },
);

/**
 * `/auth/me` 与 `/auth/logout` 的守卫：两种 actor 共用一张 cookie，
 * 所以判「这张票是谁的」必须走 ConsoleAuthService 的两次 audience 验签。
 *
 * 这里不写 @Public 之外的例外：业务接口仍旧只认租户票（JwtStrategy），
 * 平台票能进的只有 `/platform/*`（PlatformGuard）——统一的是入口，不是权限面。
 */
@Injectable()
export class ConsoleSessionGuard implements CanActivate {
  constructor(@Inject(ConsoleAuthService) private readonly auth: ConsoleAuthService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const req = context.switchToHttp().getRequest<ConsoleRequest>();
    const token = extractSessionCookie(req);
    const session = token ? await this.auth.sessionFor(token) : null;
    if (!session) throw new UnauthorizedException("会话无效，请重新登录");
    req.consoleSession = session;
    return true;
  }
}
