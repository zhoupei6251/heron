import { defineStore } from "pinia";
import { ref } from "vue";

import type { AuthUser, LoginBody } from "@heron/contracts";

import { ApiError } from "../api";
import { authApi } from "../api/auth";
import { advanceSession, sessionGeneration } from "../api/http";

/** 服务端把拒绝理由放在 JSON 的 message 里（校验失败时是字符串数组）；取不到就退回一句人话。 */
function reasonOf(cause: unknown): string {
  if (cause instanceof ApiError) {
    try {
      const body = JSON.parse(cause.body) as { message?: string | string[] };
      if (Array.isArray(body.message)) return body.message.join("；");
      if (body.message) return body.message;
    } catch {
      // 不是 JSON（网关页、代理错误页）：下面统一给一句可读的原因
    }
    return `登录失败（HTTP ${cause.status}）`;
  }
  return "登录失败：连不上服务端，稍后重试";
}

export const useAuthStore = defineStore("auth", () => {
  const user = ref<AuthUser | null>(null);
  const busy = ref(false);
  const error = ref<string | null>(null);
  /** 守卫每次导航都会问一次「我是谁」；并发的问法共用同一个请求，不并发打 /auth/me。 */
  let meRequest: Promise<void> | null = null;

  async function login(input: LoginBody): Promise<boolean> {
    let generation = advanceSession("tenant");
    meRequest = null;
    busy.value = true;
    error.value = null;
    try {
      const me = await authApi.login(input);
      if (generation !== sessionGeneration("tenant")) return false;
      generation = advanceSession("tenant");
      meRequest = null;
      user.value = me;
      return true;
    } catch (cause) {
      if (generation === sessionGeneration("tenant")) error.value = reasonOf(cause);
      throw cause;
    } finally {
      if (generation === sessionGeneration("tenant")) busy.value = false;
    }
  }

  /**
   * 401 是「还没登录」，不是故障：返回 null 让守卫送去登录页，不往界面上写红字。
   * 其它错误要报出来——把网络故障伪装成未登录，只会让人反复输一个其实没问题的口令。
   */
  async function fetchMe(): Promise<AuthUser | null> {
    if (user.value || busy.value) return user.value;
    const generation = sessionGeneration("tenant");
    if (!meRequest) {
      const pending = authApi
        .me()
        .then((me) => {
          if (generation === sessionGeneration("tenant")) user.value = me;
        })
        .catch((cause: unknown) => {
          if (generation !== sessionGeneration("tenant")) return;
          if (!(cause instanceof ApiError && cause.status === 401)) {
            error.value = `读取会话失败：${String(cause)}`;
          }
          user.value = null;
        })
        .finally(() => {
          if (meRequest === pending) meRequest = null;
        });
      meRequest = pending;
    }
    await meRequest;
    return user.value;
  }

  function clear(): void {
    advanceSession("tenant");
    meRequest = null;
    user.value = null;
    busy.value = false;
    error.value = null;
  }

  async function logout(): Promise<void> {
    const generation = advanceSession("tenant");
    meRequest = null;
    busy.value = false;
    await authApi.logout();
    if (generation === sessionGeneration("tenant")) clear();
  }

  return { user, busy, error, login, fetchMe, clear, logout };
});
