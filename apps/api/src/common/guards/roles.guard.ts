import {
  ForbiddenException,
  Inject,
  Injectable,
  type CanActivate,
  type ExecutionContext,
  UnauthorizedException,
} from "@nestjs/common";
import { Reflector } from "@nestjs/core";

import type { AuthUser } from "@heron/contracts";
import { can, isAction, requiredRoleFor } from "@heron/domain";

import { REQUIRE_ACTION_KEY } from "../decorators/require-action.decorator";

/**
 * 权限闸：这条路由要哪个动作 × 这个人能不能做。
 *
 * 判据不在这里——can() 与 MIN_ROLE 在 domain/rbac.ts，界面置灰读的是同一张表。
 * 这个守卫只做接线，所以它的全部工作是「读元数据、取 req.user、比一下」。
 *
 * 三种情况分开处理，因为它们的成因与文案都不一样：
 * - 没声明动作 → 只要求登录（/api/auth/me 这类：谁都能问「我是谁」）。
 * - 声明了动作但没有身份 → 401。正常流程到不了这里（JwtAuthGuard 在前），
 *   走到就说明守卫顺序被改乱了；此时宁可拒绝也不许默认放行。
 * - 有身份但不够格 → 403，并给出最低角色，让人知道找谁要权限。
 */
@Injectable()
export class RolesGuard implements CanActivate {
  // @Inject 不能省成裸类型：tsx 不执行 emitDecoratorMetadata，隐式注入在启动期才炸。
  constructor(@Inject(Reflector) private readonly reflector: Reflector) {}

  /**
   * 判据本身是同步的，但守卫对外给的是 Promise：拒绝必须以 rejection 的形式出去，
   * 而不是从一个同步方法里抛出——调用方（Nest 的守卫链、本案的单测）都在 await 它。
   */
  canActivate(context: ExecutionContext): Promise<boolean> {
    return Promise.resolve().then(() => this.decide(context));
  }

  private decide(context: ExecutionContext): boolean {
    const action = this.reflector.getAllAndOverride<string | undefined>(REQUIRE_ACTION_KEY, [
      context.getHandler(),
      context.getClass(),
    ]);
    if (action === undefined) return true;

    const user = context.switchToHttp().getRequest<{ user?: AuthUser }>().user;
    if (!user) throw new UnauthorizedException("没有会话身份，权限判不了");

    // 名单外的动作名（拼错的字符串）必须红，而不是悄悄变成一条无门槛路由
    if (!isAction(action)) {
      throw new ForbiddenException(`权限表里没有这个动作：${action}`);
    }
    if (!can(user.role, action)) {
      throw new ForbiddenException(
        `${action} 需要 ${requiredRoleFor(action)} 及以上权限，当前角色是 ${user.role}`,
      );
    }
    return true;
  }
}
