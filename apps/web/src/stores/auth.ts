import { defineStore } from "pinia";
import { computed, ref } from "vue";

import type {
  AuthUser,
  ConsoleGroup,
  ConsoleMenu,
  ConsoleSession,
  LoginBodyInput,
} from "@heron/contracts";
import { can, type Action } from "@heron/domain";

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
  /** 租户那一面的身份：空间成员，或平台运营的查看态（scope=platform-view，只读）。平台台没有这一格。 */
  const user = computed<AuthUser | null>(() =>
    session.value?.kind === "tenant" ? session.value.user : null,
  );
  /** 渲染与路由注册都读这一份：后端下发什么，界面就只能出现什么。 */
  const menus = computed<ConsoleMenu[]>(() => authorizedMenus(session.value));
  /** 侧栏模块：只留下确实有菜单落在里面的分组，空模块不占位置。 */
  const groups = computed<ConsoleGroup[]>(() => {
    if (!session.value) return [];
    const used = new Set(menus.value.map((menu) => menu.group));
    return session.value.groups.filter((group) => used.has(group.key));
  });
  /** 落地页与快捷导航只认不隐藏的菜单；隐藏页面（个人设置）仍注册路由。 */
  const visibleMenus = computed(() => menus.value.filter((menu) => !menu.hidden));
  const homePath = computed(() => visibleMenus.value[0]?.path ?? menus.value[0]?.path ?? "/");
  /** 查看态只读：与服务端 RolesGuard 的动词闸同一条判据，界面据此置灰写入。 */
  const readOnly = computed(() => user.value?.scope === "platform-view");
  /**
   * 写动作能不能做：角色够格 **且** 不是查看态。
   * 页面的可见性不许用它——有些读接口按写动作设门槛（GET /manage/audit 要 settings.write），
   * 查看态必须读得到，只是不许动手。
   */
  function canWrite(action: Action): boolean {
    return !readOnly.value && user.value !== null && can(user.value.role, action);
  }
  /** 守卫每次导航都会问一次「我是谁」；并发的问法共用同一个请求，不并发打 /auth/me。 */
  let meRequest: Promise<void> | null = null;

  async function login(input: LoginBodyInput): Promise<boolean> {
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

  /**
   * 换一张票：进某个空间查看（tenantId）或退出查看（null 回平台台）。
   * 与 login 一样先推进会话代次——上一个空间发出的请求迟到时，
   * 不许把结果写进新空间的界面上。失败只报原因、不动会话：那张 cookie 仍旧能用。
   */
  async function switchWorkspace(tenantId: string | null): Promise<boolean> {
    const generation = advanceSession();
    meRequest = null;
    error.value = null;
    try {
      const me = tenantId ? await authApi.enterWorkspace(tenantId) : await authApi.exitWorkspace();
      if (generation !== sessionGeneration()) return false;
      session.value = me;
      return true;
    } catch (cause) {
      if (generation === sessionGeneration()) error.value = reasonOf(cause);
      return false;
    }
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

  return {
    session,
    user,
    menus,
    groups,
    visibleMenus,
    homePath,
    readOnly,
    canWrite,
    busy,
    error,
    login,
    fetchMe,
    switchWorkspace,
    applyUser,
    clear,
    logout,
  };
});
