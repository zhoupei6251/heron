import { SetMetadata } from "@nestjs/common";

import type { Action } from "@heron/domain";

/** 元数据键：这条路由要哪个**动作**。 */
export const REQUIRE_ACTION_KEY = "heron:requireAction";

/**
 * 声明「做这件事需要什么权限」。
 *
 * 标的是动作而不是角色（`@RequireRole("reviewer")` 那种写法在这里不要）：
 * 「review.confirm 要 reviewer 及以上」这条判据只有 domain/rbac.ts 一处，
 * 界面置灰、守卫放行、文案「需要 reviewer 及以上」都从它读。装饰器里再写一次角色名，
 * 就把同一个决定拆成了两处——改一处忘另一处时，症状是界面挡住了但接口没挡住。
 */
export const RequireAction = (action: Action) => SetMetadata(REQUIRE_ACTION_KEY, action);
