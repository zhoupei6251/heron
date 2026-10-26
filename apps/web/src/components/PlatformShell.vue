<script setup lang="ts">
import { ElAlert } from "element-plus";
import { ref } from "vue";
import { RouterView, useRouter } from "vue-router";

import { errorMessage } from "../api/http";
import { usePlatformAuthStore } from "../stores/platform-auth";
import ConsoleFrame from "./ConsoleFrame.vue";

const auth = usePlatformAuthStore();
const router = useRouter();
const signingOut = ref(false);
const error = ref("");
async function logout(): Promise<void> {
  const route = router.currentRoute.value;
  signingOut.value = true;
  error.value = "";
  try {
    await auth.logout();
    if (auth.user || auth.busy || router.currentRoute.value !== route) return;
    await router.replace("/platform/login");
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
    platform
    workspace="heron 平台运营"
    :name="auth.user.displayName"
    role="平台管理员"
    :signing-out="signingOut"
    @logout="logout"
  >
    <ElAlert
      v-if="error"
      class="page-alert"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    />
    <RouterView />
  </ConsoleFrame>
</template>
