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
 *
 * 统一入口后只有一张票：不再分租户/平台两套代次，否则「换了身份但旧请求的 401
 * 落在新会话上」这种竞态会分别漏一次。
 */
let unauthorizedHandler: (() => void) | null = null;
let generation = 0;

export function sessionGeneration(): number {
  return generation;
}

/** Invalidate work belonging to the previous session. */
export function advanceSession(): number {
  return ++generation;
}

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const current = sessionGeneration();
  const response = await fetch(`/api${path}`, {
    // cookie 是唯一的会话载体（EventSource 带不了请求头），显式写出来让这条约定可见
    credentials: "same-origin",
    headers: { "content-type": "application/json" },
    ...init,
  });
  // 登录和探针自己就是「票还没建立」的入口：它们的 401 属于表单，不是会话失效
  const sessionProbe = /^\/auth\/(login|me)$/.test(path);
  if (response.status === 401 && !sessionProbe && current === sessionGeneration()) {
    unauthorizedHandler?.();
  }
  if (!response.ok) {
    throw new ApiError(response.status, await response.text());
  }
  return (await response.json()) as T;
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    try {
      const body = JSON.parse(error.body) as { message?: unknown };
      if (typeof body.message === "string") return body.message;
      if (Array.isArray(body.message) && body.message.every((item) => typeof item === "string")) {
        return body.message.join("；");
      }
    } catch {
      return `请求失败（HTTP ${error.status}），请重试`;
    }
    return `请求失败（HTTP ${error.status}）`;
  }
  return error instanceof Error ? error.message : "无法连接服务，请检查网络后重试";
}
