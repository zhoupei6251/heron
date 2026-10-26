<script setup lang="ts">
import { ElConfigProvider } from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { RouterView, useRoute } from "vue-router";

import AppShell from "./components/AppShell.vue";
import { useAppearanceStore } from "./stores/appearance";
import { useAuthStore } from "./stores/auth";

useAppearanceStore();
const auth = useAuthStore();
const route = useRoute();
</script>

<template>
  <ElConfigProvider :locale="zhCn">
    <!-- 登录页与 404 不带外壳；其余页面在有会话时共用同一个控制台布局。 -->
    <RouterView v-if="route.meta.public || !auth.session" />
    <AppShell v-else />
  </ElConfigProvider>
</template>
