import type { Role } from "@heron/contracts";
import { ForbiddenException, UnauthorizedException } from "@nestjs/common";
import type { ExecutionContext } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { describe, expect, it } from "vitest";

import { REQUIRE_ACTION_KEY } from "../decorators/require-action.decorator";
import { RolesGuard } from "./roles.guard";

/**
 * RolesGuard 的全部工作是「这条路由要哪个动作」× 「这个人能不能做」，
 * 判据不在这里——can() 与 MIN_ROLE 在 domain，界面置灰读的是同一张表。
 * 这里因此只测接线与两种越界。
 */

function contextFor(input: { action?: string; user?: { role: Role } }): ExecutionContext {
  const handler = function handler() {};
  if (input.action) Reflect.defineMetadata(REQUIRE_ACTION_KEY, input.action, handler);
  const request = { user: input.user };
  return {
    getType: () => "http",
    getHandler: () => handler,
    getClass: () => class Controller {},
    getArgByIndex: () => request,
    switchToHttp: () => ({ getRequest: () => request }),
  } as unknown as ExecutionContext;
}

const guard = new RolesGuard(new Reflector());

describe("RolesGuard", () => {
  it("路由没声明动作就只要求登录：/api/auth/me 这类不该被权限表挡住", async () => {
    await expect(guard.canActivate(contextFor({ user: { role: "viewer" } }))).resolves.toBe(true);
  });

  it("人是谁都还没定就挡下：守卫顺序被改乱时宁可拒绝，不许默认放行", async () => {
    await expect(
      guard.canActivate(contextFor({ action: "review.confirm" })),
    ).rejects.toBeInstanceOf(UnauthorizedException);
  });

  it("review.confirm 要 reviewer 及以上：viewer 与 operator 都差一档", async () => {
    for (const role of ["viewer", "operator"] as Role[]) {
      await expect(
        guard.canActivate(contextFor({ action: "review.confirm", user: { role } })),
      ).rejects.toBeInstanceOf(ForbiddenException);
    }
    for (const role of ["reviewer", "admin", "owner"] as Role[]) {
      await expect(
        guard.canActivate(contextFor({ action: "review.confirm", user: { role } })),
      ).resolves.toBe(true);
    }
  });

  it("aigc.set 只有 admin 及以上：改姿态就是改角标，它是最高危的一次写", async () => {
    await expect(
      guard.canActivate(contextFor({ action: "aigc.set", user: { role: "reviewer" } })),
    ).rejects.toBeInstanceOf(ForbiddenException);
    await expect(
      guard.canActivate(contextFor({ action: "aigc.set", user: { role: "admin" } })),
    ).resolves.toBe(true);
  });

  it("不存在的动作名不放行：写错字符串必须红，而不是变成一条无门槛路由", async () => {
    await expect(
      guard.canActivate(contextFor({ action: "task.rename", user: { role: "owner" } })),
    ).rejects.toBeInstanceOf(ForbiddenException);
  });
});
