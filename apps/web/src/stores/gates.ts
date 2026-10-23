import { defineStore } from "pinia";
import { ref } from "vue";

import type { GateState } from "@heron/contracts";

import { gatesApi } from "../api";

export const useGatesStore = defineStore("gates", () => {
  const state = ref<GateState | null>(null);
  const error = ref<string | null>(null);

  async function refresh(): Promise<void> {
    try {
      state.value = await gatesApi.state();
      error.value = null;
    } catch (cause) {
      error.value = `读取门禁位失败：${String(cause)}`;
    }
  }

  return { state, error, refresh };
});
