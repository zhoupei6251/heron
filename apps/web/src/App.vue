<script setup lang="ts">
import { RouterView } from "vue-router";

import AppShell from "./components/AppShell.vue";
import { useAuthStore } from "./stores/auth";

/**
 * 只有两种壳：登录页（无导航）与登录后的 AppShell。
 * 守卫保证不会出现「没身份却渲染 AppShell」——这里只负责选一种。
 */
const auth = useAuthStore();
</script>

<template>
  <AppShell v-if="auth.user" />
  <RouterView v-else />
</template>
