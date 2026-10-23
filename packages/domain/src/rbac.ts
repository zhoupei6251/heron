import { ROLES, type Role } from "@heron/contracts";

/**
 * 判据是纯函数：api 用来挡住请求，web 用来置灰按钮并显示「需要 reviewer 及以上」，
 * worker 将来判断某次自动动作有没有人授权。一份名单三处用，权限就不会出现「界面挡住了但接口没挡」。
 */
export const ACTIONS = [
  "task.claim",
  "review.confirm",
  "settings.write",
  "aigc.set",
  "user.manage",
  "usage.read",
] as const;

export type Action = (typeof ACTIONS)[number];

/**
 * 名单校验。装饰器的参数类型在编译期就挡住了写错的动作名，但守卫拿到的是**运行时元数据**：
 * 一个字符串拼错（"task.rename"）如果不判，can() 里的 MIN_ROLE[action] 是 undefined，
 * RANK[undefined] 也是 undefined，比较结果 NaN → false，看着也像拒绝——
 * 但那是靠巧合，不是靠判据。显式判一次，拒的时候才说得出「这个动作不存在」。
 */
export function isAction(value: string): value is Action {
  return (ACTIONS as readonly string[]).includes(value);
}

/**
 * 名次从 contracts 的 ROLES 顺序推（owner 最先 → 名次最大）。
 * 所以加一档角色只改 contracts 一处；顺序错了 rbac.test.ts 会红，而不是静默地把权限翻转。
 */
const RANK = Object.fromEntries(ROLES.map((role, i) => [role, ROLES.length - i])) as Record<
  Role,
  number
>;

/** 每个动作的最低角色。这张表必须写满：新增动作而不配角色，编译期就过不去。 */
const MIN_ROLE: Record<Action, Role> = {
  "task.claim": "operator",
  // 规格书的「人点确认」不能是只读账号，也不能是只管生产的操作账号
  "review.confirm": "reviewer",
  "settings.write": "admin",
  // AIGC 姿态一改就能把角标和声明绕掉，它是最高危的一次写
  "aigc.set": "admin",
  "user.manage": "owner",
  "usage.read": "viewer",
};

export function can(role: Role, action: Action): boolean {
  return RANK[role] >= RANK[MIN_ROLE[action]];
}

/** 界面置灰时那句人话的主语：给最低角色，而不是给一句写死的文案。 */
export function requiredRoleFor(action: Action): Role {
  return MIN_ROLE[action];
}
