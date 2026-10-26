import { defineStore } from "pinia";
import { ref } from "vue";

import type { PlatformUser } from "@heron/contracts";

import { advanceSession, ApiError, errorMessage, request, sessionGeneration } from "../api/http";

export const usePlatformAuthStore = defineStore("platform-auth", () => {
  const user = ref<PlatformUser | null>(null);
  const busy = ref(false);
  const error = ref<string | null>(null);
  let meRequest: Promise<void> | null = null;

  async function login(input: { email: string; password: string }): Promise<boolean> {
    let generation = advanceSession("platform");
    meRequest = null;
    busy.value = true;
    error.value = null;
    try {
      const me = await request<PlatformUser>("/platform/auth/login", {
        method: "POST",
        body: JSON.stringify(input),
      });
      if (generation !== sessionGeneration("platform")) return false;
      generation = advanceSession("platform");
      meRequest = null;
      user.value = me;
      return true;
    } catch (cause) {
      if (generation === sessionGeneration("platform")) error.value = errorMessage(cause);
      throw cause;
    } finally {
      if (generation === sessionGeneration("platform")) busy.value = false;
    }
  }

  async function fetchMe(): Promise<PlatformUser | null> {
    if (user.value || busy.value) return user.value;
    const generation = sessionGeneration("platform");
    if (!meRequest) {
      const pending = request<PlatformUser>("/platform/auth/me")
        .then((me) => {
          if (generation === sessionGeneration("platform")) user.value = me;
        })
        .catch((cause: unknown) => {
          if (generation !== sessionGeneration("platform")) return;
          if (!(cause instanceof ApiError && cause.status === 401)) {
            error.value = errorMessage(cause);
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
    advanceSession("platform");
    meRequest = null;
    user.value = null;
    busy.value = false;
    error.value = null;
  }

  async function logout(): Promise<void> {
    const generation = advanceSession("platform");
    meRequest = null;
    busy.value = false;
    await request("/platform/auth/logout", { method: "POST" });
    if (generation === sessionGeneration("platform")) clear();
  }

  return { user, busy, error, login, fetchMe, clear, logout };
});
