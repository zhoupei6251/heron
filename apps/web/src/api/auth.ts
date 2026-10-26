import type { ConsoleSession, LoginBody } from "@heron/contracts";

import { request } from "./http";

export const authApi = {
  /** token 只从 Set-Cookie 进来，响应体就是会话表；前端不碰 token。 */
  login: (input: LoginBody) =>
    request<ConsoleSession>("/auth/login", { method: "POST", body: JSON.stringify(input) }),
  me: () => request<ConsoleSession>("/auth/me"),
  logout: () => request<{ ok: boolean }>("/auth/logout", { method: "POST" }),
};
