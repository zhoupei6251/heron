<script setup lang="ts">
import { ElAlert, ElButton, ElInput } from "element-plus";
import { computed, reactive } from "vue";
import { useRoute, useRouter } from "vue-router";

import { useAuthStore } from "../stores/auth";

/**
 * 登录只做一件事：把「工作空间 slug + 邮箱 + 口令」换成一张服务端签发的会话。
 * 失焦校验与口令强度不在前端判——判据只有服务端那一份，前端多写一遍就会出现两套口径。
 */
const auth = useAuthStore();
const router = useRouter();
const route = useRoute();

const form = reactive({ tenant: "default", email: "", password: "" });
const ready = computed(
  () => form.tenant.trim() !== "" && form.email.trim() !== "" && form.password.length >= 12,
);

async function submit(): Promise<void> {
  if (!ready.value || auth.busy) return;
  try {
    await auth.login({
      tenant: form.tenant.trim(),
      email: form.email.trim(),
      password: form.password,
    });
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/";
    await router.replace(redirect);
  } catch {
    // 原因已在 store.error 里；先清掉口令，别让一次失败的口令留在输入框里
    form.password = "";
  }
}
</script>

<template>
  <div class="flex min-h-screen items-center justify-center bg-neutral-50 px-4">
    <form
      class="w-full max-w-sm rounded-lg border border-neutral-200 bg-white p-6 shadow-sm"
      @submit.prevent="submit"
    >
      <h1 class="text-xl font-semibold">heron</h1>
      <p class="mt-1 text-sm text-neutral-600">登录到你的工作空间</p>

      <label class="mt-6 block text-sm">
        <span class="text-neutral-700">工作空间</span>
        <ElInput
          v-model="form.tenant"
          class="mt-1"
          placeholder="default"
          autocomplete="organization"
        />
      </label>

      <label class="mt-4 block text-sm">
        <span class="text-neutral-700">邮箱</span>
        <ElInput
          v-model="form.email"
          class="mt-1"
          placeholder="you@example.com"
          autocomplete="username"
        />
      </label>

      <label class="mt-4 block text-sm">
        <span class="text-neutral-700">口令</span>
        <ElInput
          v-model="form.password"
          class="mt-1"
          type="password"
          show-password
          autocomplete="current-password"
        />
      </label>

      <ElAlert v-if="auth.error" type="error" :closable="false" class="mt-4" :title="auth.error" />

      <ElButton
        type="primary"
        native-type="submit"
        :loading="auth.busy"
        :disabled="!ready"
        class="mt-5 w-full"
      >
        登录
      </ElButton>

      <p class="mt-4 text-xs text-neutral-500">账号由管理员创建；口令至少 12 位。</p>
    </form>
  </div>
</template>
