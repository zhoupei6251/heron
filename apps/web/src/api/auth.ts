import type { ConsoleSession, LoginBodyInput } from "@heron/contracts";

import { request } from "./http";

/** 切换器要的那一列：工作空间坐标，不含任何业务数据（服务端还带 slug，这里用不上）。 */
export interface WorkspaceRef {
  id: string;
  name: string;
}

export const authApi = {
  /** token 只从 Set-Cookie 进来，响应体就是会话表；前端不碰 token。 */
  login: (input: LoginBodyInput) =>
    request<ConsoleSession>("/auth/login", { method: "POST", body: JSON.stringify(input) }),
  me: () => request<ConsoleSession>("/auth/me"),
  logout: () => request<{ ok: boolean }>("/auth/logout", { method: "POST" }),
  workspaces: () => request<WorkspaceRef[]>("/auth/workspaces"),
  /** 换票：返回新会话表，同时服务端已把 cookie 换成那张租户查看票。 */
  enterWorkspace: (tenantId: string) =>
    request<ConsoleSession>("/auth/workspace-view", {
      method: "POST",
      body: JSON.stringify({ tenantId }),
    }),
  exitWorkspace: () => request<ConsoleSession>("/auth/workspace-view/end", { method: "POST" }),
};
