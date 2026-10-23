import type { AuthUser, LoginBody } from "@heron/contracts";

import { request } from "./http";

export const authApi = {
  /** token 只从 Set-Cookie 进来，响应体就是身份本身；前端不碰 token。 */
  login: (input: LoginBody) =>
    request<AuthUser>("/auth/login", { method: "POST", body: JSON.stringify(input) }),
  me: () => request<AuthUser>("/auth/me"),
};
