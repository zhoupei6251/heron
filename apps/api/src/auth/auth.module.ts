import { Module } from "@nestjs/common";
import { JwtModule } from "@nestjs/jwt";

import { SESSION_TTL, jwtSecret } from "../config/runtime";
import { PgModule } from "../db/pg";
import { PlatformModule } from "../platform/platform.module";
import { AuthController } from "./auth.controller";
import { AuthService } from "./auth.service";
import { ConsoleAuthService } from "./console-auth.service";
import { ConsoleSessionGuard } from "./console-session.guard";
import { JwtStrategy } from "./jwt.strategy";
import { LoginThrottleService } from "./login-throttle.service";

/**
 * 认证这一层的全部接线。
 *
 * 没有 PassportModule：策略在 PassportStrategy 的构造里就把自已注册进了 passport 单例，
 * 而守卫每次都是按名字（"jwt"）取策略。import 那个模块只对 `defaultStrategy` 有意义，
 * 本案没有匿名可选策略，所以不留一条装饰性依赖。
 *
 * JwtModule 用 registerAsync 而不是 register：密钥要在**启动时**读环境，不是在 import 时。
 * 差别在容器化部署里才显现——测试进程 import 这个文件时没有 HERON_JWT_SECRET，
 * 按 import 求值会让它连自己都起不来；按启动求值才把「没配」报成一次明确的启动失败。
 */
@Module({
  imports: [
    PgModule,
    // 统一入口同时要问两边的账号表；平台模块只导出服务，不导出它的登录路由。
    PlatformModule,
    JwtModule.registerAsync({
      useFactory: () => ({ secret: jwtSecret(), signOptions: { expiresIn: SESSION_TTL } }),
    }),
  ],
  controllers: [AuthController],
  providers: [
    AuthService,
    ConsoleAuthService,
    ConsoleSessionGuard,
    JwtStrategy,
    LoginThrottleService,
  ],
  exports: [AuthService],
})
export class AuthModule {}
