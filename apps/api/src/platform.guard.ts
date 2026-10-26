import {
  createParamDecorator,
  Inject,
  Injectable,
  UnauthorizedException,
  type CanActivate,
  type ExecutionContext,
} from "@nestjs/common";
import type { PlatformUser } from "@heron/contracts";
import { PLATFORM_COOKIE } from "./config/runtime";
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

/** Separate identity slot: a platform login never populates tenant req.user. */
@Injectable()
export class PlatformGuard implements CanActivate {
  constructor(@Inject(PlatformAuthService) private readonly auth: PlatformAuthService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const req = context.switchToHttp().getRequest<PlatformRequest>();
    const token = req.cookies?.[PLATFORM_COOKIE];
    const user = token ? await this.auth.authenticate(token) : null;
    if (!user) throw new UnauthorizedException("平台会话无效，请重新登录");
    req.platformUser = user;
    return true;
  }
}
