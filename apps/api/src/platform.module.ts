import { Module } from "@nestjs/common";
import { JwtModule } from "@nestjs/jwt";
import { PgModule } from "./db/pg";
import { jwtSecret, SESSION_TTL } from "./config/runtime";
import { PlatformAuthService } from "./platform-auth.service";
import { PlatformController, PlatformLoginController } from "./platform.controller";
import { PlatformGuard } from "./platform.guard";

@Module({
  imports: [
    PgModule,
    JwtModule.registerAsync({
      useFactory: () => ({ secret: jwtSecret(), signOptions: { expiresIn: SESSION_TTL } }),
    }),
  ],
  controllers: [PlatformController, PlatformLoginController],
  providers: [PlatformAuthService, PlatformGuard],
})
export class PlatformModule {}
