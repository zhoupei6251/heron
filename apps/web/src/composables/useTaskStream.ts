import { useEventSource } from "@vueuse/core";
import { computed, onScopeDispose, toValue, watch, type MaybeRefOrGetter } from "vue";

import type { TaskEvent } from "@heron/contracts";

export interface TaskStreamHandlers {
  onEvent: (event: TaskEvent) => void;
  onStatus?: (connected: boolean) => void;
}

/**
 * 事件流属于租户会话：平台的运营视角没有任务队列可推。
 *
 * 连接本身不在 composable 创建时打开（`immediate: false`），由 enabled 驱动开关，
 * 这样切到平台身份时不会留下一条一直在重连的 SSE。
 */
export function useTaskStream(
  handlers: TaskStreamHandlers,
  enabled: MaybeRefOrGetter<boolean> = true,
): void {
  const active = computed(() => toValue(enabled));
  const { data, status, error, open, close } = useEventSource("/api/events", [], {
    immediate: false,
  });

  watch(
    active,
    (value) => {
      if (value) open();
      else close();
    },
    { immediate: true, flush: "sync" },
  );

  watch(data, (raw) => {
    if (typeof raw !== "string" || raw.length === 0) return;
    handlers.onEvent(JSON.parse(raw) as TaskEvent);
  });

  watch(status, (value) => {
    handlers.onStatus?.(String(value).toUpperCase() === "OPEN");
  });

  watch(error, (value) => {
    if (value) console.error("SSE 断开：", value);
  });

  onScopeDispose(() => close());
}
