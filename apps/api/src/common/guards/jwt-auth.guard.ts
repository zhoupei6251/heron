import { Inject, Injectable, type ExecutionContext } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { AuthGuard } from "@nestjs/passport";

import { IS_PUBLIC_KEY } from "../decorators/public.decorator";

/**
 * 会话闸：默认所有接口都要带有效会话，只有挂了 @Public 的两条例外（/health、login）。
 *
 * 顺序有意义——它排在 RolesGuard 前面（app.module.ts 的 APP_GUARD 列表），
 * 因为 RolesGuard 要读的 `req.user` 是这里写进去的。反过来的症状是每条受保护的路由都 401，
 * 而 @Public 的路由反倒能被权限表挡住，很难一眼看出是顺序错了。
 *
 * 验签只认 cookie（见 jwt.strategy.ts 的抽取器）。这里不做「没 cookie 就当游客放行」的兜底：
 * 匿名放行在多租户平台上等于开了一条不带租户坐标的读接口。
 */
@Injectable()
export class JwtAuthGuard extends AuthGuard("jwt") {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(Reflector) private readonly reflector: Reflector) {
    super();
  }

  // 返回类型不写：Nest 12 的 CanActivate 允许 boolean | Promise | Observable，
  // 注解收窄成前两种的话 super.canActivate() 就过不了类型——让推导去接基队的形状。
  override canActivate(context: ExecutionContext) {
    const isPublic = this.reflector.getAllAndOverride<boolean>(IS_PUBLIC_KEY, [
      context.getHandler(),
      context.getClass(),
    ]);
    if (isPublic === true) return true;
    return super.canActivate(context);
  }
}
