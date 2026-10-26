<script setup lang="ts">
import { ElConfigProvider } from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { RouterView, useRoute } from "vue-router";

import AppShell from "./components/AppShell.vue";
import PlatformShell from "./components/PlatformShell.vue";
import { useAuthStore } from "./stores/auth";
import { usePlatformAuthStore } from "./stores/platform-auth";
import { useAppearanceStore } from "./stores/appearance";

useAppearanceStore();
const auth = useAuthStore();
const platformAuth = usePlatformAuthStore();
const route = useRoute();
</script>

<template>
  <ElConfigProvider :locale="zhCn">
    <RouterView v-if="route.meta.public" />
    <PlatformShell v-else-if="route.meta.platform && platformAuth.user" />
    <AppShell v-else-if="!route.meta.platform && auth.user" />
    <RouterView v-else />
  </ElConfigProvider>
</template>
