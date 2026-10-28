import type { Role } from "@heron/contracts";

/** 界面文案是展示层的事：判据读的是 role 代号，顶栏给人看的是这五个词。 */
export const ROLE_LABELS: Record<Role, string> = {
  owner: "所有者",
  admin: "管理员",
  reviewer: "审核员",
  operator: "操作员",
  viewer: "只读",
};

export function roleLabel(role: Role): string {
  return ROLE_LABELS[role];
}

/** 查看态写入被拒的同一句话：服务端 403 用全文，界面置灰时用它解释为什么。 */
export const VIEW_FENCE = "以平台身份查看工作空间是只读的：这一步请由该空间的成员执行。";
