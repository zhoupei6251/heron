export class ApiError extends Error {
  readonly status: number;
  readonly body: string;

  constructor(status: number, body: string) {
    super(`HTTP ${status}: ${body}`);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

/**
 * 401 不是某一次请求的小错误，是「这张票没了」。接线处（router 的守卫模块）
 * 把它变成一次清空身份 + 送回登录页；否则界面会停在一个永远读不到数据的页面上。
 * 用回调而不是在这里 import router：http 是最底层，反过来依赖路由会绕成环。
 */
let unauthorizedHandler: (() => void) | null = null;

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    // cookie 是唯一的会话载体（EventSource 带不了请求头），显式写出来让这条约定可见
    credentials: "same-origin",
    headers: { "content-type": "application/json" },
    ...init,
  });
  if (response.status === 401) unauthorizedHandler?.();
  if (!response.ok) {
    throw new ApiError(response.status, await response.text());
  }
  return (await response.json()) as T;
}
