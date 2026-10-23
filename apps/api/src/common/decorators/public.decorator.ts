import { SetMetadata } from "@nestjs/common";

/** 元数据键。守卫读它，测试也读它——写成字符串字面量就得在两处保持一致。 */
export const IS_PUBLIC_KEY = "heron:isPublic";

/**
 * 这条路由不需要会话。全局守卫（APP_GUARD）默认挡下所有请求，所以公开是**声明**出来的，
 * 不是「忘了加守卫」的结果——后者在多租户平台上等于一条匿名读接口。
 *
 * 目前只有两处该挂：`/health`（compose 与 nginx 的探针必须在没有会话时打得到，
 * 且它只报进程与库通不通，不报任何租户数据）与 `POST /auth/login`（没有会话才需要登录）。
 */
export const Public = () => SetMetadata(IS_PUBLIC_KEY, true);
