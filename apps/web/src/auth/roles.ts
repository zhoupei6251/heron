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
