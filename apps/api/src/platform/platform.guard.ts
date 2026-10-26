import {
  createParamDecorator,
  Inject,
  Injectable,
  UnauthorizedException,
  type CanActivate,
  type ExecutionContext,
} from "@nestjs/common";
import type { PlatformUser } from "@heron/contracts";
import { SESSION_COOKIE } from "../config/runtime";
import { PlatformAuthService } from "./platform-auth.service";

interface PlatformRequest {
  cookies?: Record<string, string | undefined>;
  platformUser?: PlatformUser;
}

export const CurrentPlatformUser = createParamDecorator(
  (_data: unknown, context: ExecutionContext): PlatformUser => {
    const user = context.switchToHttp().getRequest<PlatformRequest>().platformUser;
    if (!user) throw new UnauthorizedException("请先登录平台控制台");
    return user;
  },
);

/**
 * 平台这一面的守卫：读的是统一会话 cookie，但只接受平台 audience 的票。
 *
 * 统一的是入口，不是权限面：租户票在这里验不过（audience 不符），
 * 所以「平台管理员没有任何租户内容权限」仍然由 token 本身保证，不靠界面隐藏菜单。
 */
@Injectable()
export class PlatformGuard implements CanActivate {
  constructor(@Inject(PlatformAuthService) private readonly auth: PlatformAuthService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const req = context.switchToHttp().getRequest<PlatformRequest>();
    const token = req.cookies?.[SESSION_COOKIE];
    const user = token ? await this.auth.authenticate(token) : null;
    if (!user) throw new UnauthorizedException("平台会话无效，请重新登录");
    req.platformUser = user;
    return true;
  }
}
