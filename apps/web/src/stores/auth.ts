import { defineStore } from "pinia";
import { computed, ref } from "vue";

import type { AuthUser, ConsoleMenu, ConsoleSession, LoginBody } from "@heron/contracts";

import { ApiError } from "../api";
import { authApi } from "../api/auth";
import { advanceSession, sessionGeneration } from "../api/http";
import { authorizedMenus } from "../router/menu-routes";

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

/**
 * 一个入口、一个会话。
 *
 * 租户用户和平台运营共用一张票、一套 generation：跨身份登录（先租户后平台）
 * 必须让前一个身份发出的 401 作废，否则迟到的 401 会把刚登录的新身份踢出去。
 * 两种身份真正的隔离在服务端靠 audience，前端只负责「当前是谁」这一份状态。
 */
export const useAuthStore = defineStore("auth", () => {
  const session = ref<ConsoleSession | null>(null);
  const busy = ref(false);
  const error = ref<string | null>(null);
  /** 只有租户会话有工作空间内的会员身份；平台运营在租户页面上没有立足点。 */
  const user = computed<AuthUser | null>(() =>
    session.value?.kind === "tenant" ? session.value.user : null,
  );
  /** 渲染与路由注册都读这一份：后端下发什么，界面就只能出现什么。 */
  const menus = computed<ConsoleMenu[]>(() => authorizedMenus(session.value));
  /** 守卫每次导航都会问一次「我是谁」；并发的问法共用同一个请求，不并发打 /auth/me。 */
  let meRequest: Promise<void> | null = null;

  async function login(input: LoginBody): Promise<boolean> {
    let generation = advanceSession();
    meRequest = null;
    busy.value = true;
    error.value = null;
    try {
      const me = await authApi.login(input);
      if (generation !== sessionGeneration()) return false;
      generation = advanceSession();
      meRequest = null;
      session.value = me;
      return true;
    } catch (cause) {
      if (generation === sessionGeneration()) error.value = reasonOf(cause);
      throw cause;
    } finally {
      if (generation === sessionGeneration()) busy.value = false;
    }
  }

  /**
   * 401 是「还没登录」，不是故障：返回 null 让守卫送去登录页，不往界面上写红字。
   * 其它错误要报出来——把网络故障伪装成未登录，只会让人反复输一个其实没问题的口令。
   */
  async function fetchMe(): Promise<ConsoleSession | null> {
    if (session.value || busy.value) return session.value;
    const generation = sessionGeneration();
    if (!meRequest) {
      const pending = authApi
        .me()
        .then((me) => {
          if (generation === sessionGeneration()) session.value = me;
        })
        .catch((cause: unknown) => {
          if (generation !== sessionGeneration()) return;
          if (!(cause instanceof ApiError && cause.status === 401)) {
            error.value = `读取会话失败：${String(cause)}`;
          }
          session.value = null;
        })
        .finally(() => {
          if (meRequest === pending) meRequest = null;
        });
      meRequest = pending;
    }
    await meRequest;
    return session.value;
  }

  /** 资料更新只回填身份那一格，不重发菜单：菜单的服务端来源是会话本身，不是这个人。 */
  function applyUser(next: AuthUser): void {
    if (session.value?.kind !== "tenant") return;
    session.value = { ...session.value, user: next };
  }

  function clear(): void {
    advanceSession();
    meRequest = null;
    session.value = null;
    busy.value = false;
    error.value = null;
  }

  async function logout(): Promise<void> {
    const generation = advanceSession();
    meRequest = null;
    busy.value = false;
    await authApi.logout();
    if (generation === sessionGeneration()) clear();
  }

  return { session, user, menus, busy, error, login, fetchMe, applyUser, clear, logout };
});
