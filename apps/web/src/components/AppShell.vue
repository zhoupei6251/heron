<script setup lang="ts">
import { ElAlert } from "element-plus";
import { onMounted, onUnmounted, ref } from "vue";
import { RouterView, useRouter } from "vue-router";

import { errorMessage } from "../api/http";
import { roleLabel } from "../auth/roles";
import { useTaskStream } from "../composables/useTaskStream";
import { useAuthStore } from "../stores/auth";
import { useTasksStore } from "../stores/tasks";
import ConsoleFrame from "./ConsoleFrame.vue";

const auth = useAuthStore();
const tasks = useTasksStore();
const router = useRouter();
const signingOut = ref(false);
const error = ref("");

useTaskStream({
  onEvent: (event) => tasks.applyEvent(event),
  onStatus: (connected) => tasks.setConnected(connected),
});
onMounted(() => {
  void tasks.refresh();
});
onUnmounted(() => tasks.clear());

async function logout(): Promise<void> {
  const route = router.currentRoute.value;
  signingOut.value = true;
  error.value = "";
  try {
    await auth.logout();
    if (auth.user || auth.busy || router.currentRoute.value !== route) return;
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
    v-if="auth.user"
    :workspace="auth.user.tenantName"
    :name="auth.user.displayName"
    :role="roleLabel(auth.user.role)"
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
