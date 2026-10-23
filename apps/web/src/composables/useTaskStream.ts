import { useEventSource } from "@vueuse/core";
import { onScopeDispose, watch } from "vue";

import type { TaskEvent } from "@heron/contracts";

export interface TaskStreamHandlers {
  onEvent: (event: TaskEvent) => void;
  onStatus?: (connected: boolean) => void;
}

export function useTaskStream(handlers: TaskStreamHandlers): void {
  const { data, status, error, close } = useEventSource("/api/events");

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
