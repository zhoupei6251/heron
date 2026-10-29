import { defineStore } from "pinia";
import { ref } from "vue";

import type { Task, TaskEvent } from "@heron/contracts";

import { tasksApi } from "../api";

export const useTasksStore = defineStore("tasks", () => {
  const tasks = ref<Task[]>([]);
  const error = ref<string | null>(null);
  const connected = ref(false);
  let generation = 0;

  function clear(): void {
    generation += 1;
    tasks.value = [];
    error.value = null;
    connected.value = false;
  }

  async function refresh(): Promise<void> {
    const current = generation;
    try {
      const result = await tasksApi.list();
      if (current !== generation) return;
      tasks.value = result.tasks;
      error.value = null;
    } catch (cause) {
      if (current !== generation) return;
      error.value = `读取任务列表失败：${String(cause)}`;
    }
  }

  function applyEvent(event: TaskEvent): void {
    const index = tasks.value.findIndex((task) => task.id === event.taskId);
    if (index === -1) {
      void refresh();
      return;
    }
    tasks.value[index] = {
      ...tasks.value[index]!,
      stage: event.to,
      updateTime: event.at,
    };
    // 事件里的 reason 是台账说明（闸放行也写），失败详情只存在 tasks.error_reason 上，
    // 所以落到 failed 时回读一次，别把「闸 contract 放行」渲染成错误。
    if (event.to === "failed") void refresh();
  }

  function setConnected(value: boolean): void {
    connected.value = value;
  }

  return { tasks, error, connected, refresh, applyEvent, setConnected, clear };
});
