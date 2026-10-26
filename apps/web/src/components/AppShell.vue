<script setup lang="ts">
import { ElAlert } from "element-plus";
import { computed, onUnmounted, ref, watch } from "vue";
import { RouterView, useRouter } from "vue-router";

import { errorMessage } from "../api/http";
import { useTaskStream } from "../composables/useTaskStream";
import { useAuthStore } from "../stores/auth";
import { useTasksStore } from "../stores/tasks";
import ConsoleFrame from "./ConsoleFrame.vue";

const auth = useAuthStore();
const tasks = useTasksStore();
const router = useRouter();
const signingOut = ref(false);
const error = ref("");
const tenant = computed(() => auth.session?.kind === "tenant");

// 平台身份没有任务队列可推；租户身份退出后也要把缓存清掉，别把上个租户的数据留给下一个。
useTaskStream(
  {
    onEvent: (event) => tasks.applyEvent(event),
    onStatus: (connected) => tasks.setConnected(connected),
  },
  tenant,
);
watch(
  tenant,
  (value) => {
    if (value) void tasks.refresh();
    else tasks.clear();
  },
  { immediate: true },
);
onUnmounted(() => tasks.clear());

async function logout(): Promise<void> {
  const route = router.currentRoute.value;
  signingOut.value = true;
  error.value = "";
  try {
    await auth.logout();
    if (auth.session || auth.busy || router.currentRoute.value !== route) return;
    tasks.clear();
    await router.replace("/login");
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    signingOut.value = false;
  }
}
</script>

<template>
  <ConsoleFrame
    v-if="auth.session"
    :connected="tasks.connected"
    :signing-out="signingOut"
    @logout="logout"
  >
    <ElAlert
      v-if="error || tasks.error"
      class="page-alert"
      :title="error || tasks.error || ''"
      type="error"
      :closable="false"
      show-icon
    />
    <RouterView />
  </ConsoleFrame>
</template>
