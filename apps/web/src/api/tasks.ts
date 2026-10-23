import type { Task } from "@heron/contracts";

import { request } from "./http";

export const tasksApi = {
  list: () => request<{ tasks: Task[] }>("/tasks"),
  create: (input: { capability: string; title: string }) =>
    request<Task>("/tasks", { method: "POST", body: JSON.stringify(input) }),
  // 认领人来自会话 cookie，不带请求体：接口上没有第二个能塞「替别人认领」的位置
  claim: (taskId: string) => request<Task>(`/tasks/${taskId}/claim`, { method: "POST" }),
};
