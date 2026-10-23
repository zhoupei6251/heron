<script setup lang="ts">
import { ElTag } from "element-plus";
import { onMounted } from "vue";
import { RouterLink, RouterView } from "vue-router";

import { roleLabel } from "../auth/roles";
import { useTaskStream } from "../composables/useTaskStream";
import { useAuthStore } from "../stores/auth";
import { useTasksStore } from "../stores/tasks";

/**
 * 已登录后的壳：任务列表与 SSE 只在这里启动。
 * 放在 App.vue 里直接启动的话，登录页一挂载就会去打 /api/tasks 与 /api/events（两个 401），
 * 把「未登录」渲染成一屏错误——那是界面在替服务端制造噪音。
 */
const auth = useAuthStore();
const store = useTasksStore();

useTaskStream({
  onEvent: (event) => store.applyEvent(event),
  onStatus: (connected) => store.setConnected(connected),
});

onMounted(() => {
  void store.refresh();
});
</script>

<template>
  <div class="min-h-screen">
    <header class="flex items-center gap-4 border-b border-neutral-200 bg-white px-6 py-3">
      <span class="text-lg font-semibold">heron</span>
      <nav class="flex gap-3 text-sm">
        <RouterLink to="/candidates">候选流</RouterLink>
        <RouterLink to="/production">生产台</RouterLink>
        <RouterLink to="/review">审片台</RouterLink>
        <RouterLink to="/publish">发布台</RouterLink>
        <RouterLink to="/settings">设置</RouterLink>
      </nav>
      <div class="ml-auto flex items-center gap-3 text-xs text-neutral-500">
        <span v-if="auth.user" class="text-neutral-700">{{ auth.user.tenantName }}</span>
        <ElTag v-if="auth.user" size="small">{{ roleLabel(auth.user.role) }}</ElTag>
        <span v-if="auth.user">{{ auth.user.displayName }}</span>
        <span>{{ store.connected ? "SSE 已连接" : "SSE 未连接" }}</span>
      </div>
    </header>
    <main class="p-6">
      <p v-if="store.error" class="mb-4 whitespace-pre-wrap text-sm text-red-700">
        {{ store.error }}
      </p>
      <RouterView />
    </main>
  </div>
</template>
