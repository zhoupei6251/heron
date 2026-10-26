import { Module } from "@nestjs/common";
import { JwtModule } from "@nestjs/jwt";
import { PgModule } from "../db/pg";
import { jwtSecret, SESSION_TTL } from "../config/runtime";
import { PlatformAuthService } from "./platform-auth.service";
import { PlatformController } from "./platform.controller";
import { PlatformGuard } from "./platform.guard";

@Module({
  imports: [
    PgModule,
    JwtModule.registerAsync({
      useFactory: () => ({ secret: jwtSecret(), signOptions: { expiresIn: SESSION_TTL } }),
    }),
  ],
  controllers: [PlatformController],
  providers: [PlatformAuthService, PlatformGuard],
  // 统一登录入口在 AuthModule：它要能调到平台账号的验口令与回库复查。
  exports: [PlatformAuthService],
})
export class PlatformModule {}
