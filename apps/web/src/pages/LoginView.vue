<script setup lang="ts">
import { MIN_PASSWORD_LENGTH } from "@heron/contracts";
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput } from "element-plus";
import { computed, onBeforeUnmount, reactive, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import ThemeSwitcher from "../components/ThemeSwitcher.vue";
import { safeRedirect } from "../router/guards";
import { useAuthStore } from "../stores/auth";

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const form = reactive({ identifier: "", password: "" });
const ready = computed(
  () => form.identifier.trim() !== "" && form.password.length >= MIN_PASSWORD_LENGTH,
);
// 身份由账号形状定（带 @ 是空间邮箱，不带是运营用户名），界面不再问工作空间：
// 一次提交只有一个落点，submission 只用来作废「提交后又改账号或离开本页」的那次在途登录。
let submission = 0;
let mounted = true;
watch(
  () => form.identifier,
  () => {
    submission++;
    // 在途那次登录已经不代表这个人了：连会话代次一起作废，迟到的 200 才登不进旧账号
    if (auth.busy) auth.clear();
  },
  // 同步：一次按键与紧随其后的提交可能落在同一个 tick 里，作废必须赶在下一次提交计数之前
  { flush: "sync" },
);
onBeforeUnmount(() => {
  mounted = false;
  submission++;
});

async function submit(): Promise<void> {
  if (!ready.value || auth.busy) return;
  const current = ++submission;
  // 落点在提交那一刻定下来：登录期间地址栏再变，也不改这次要去的页面。
  const redirect = route.query.redirect;
  try {
    const applied = await auth.login({
      identifier: form.identifier.trim(),
      password: form.password,
    });
    if (!applied || !mounted || current !== submission) return;
    form.password = "";
    await router.replace(safeRedirect(redirect, auth.session));
  } catch {
    if (mounted && current === submission) form.password = "";
  }
}
</script>

<template>
  <div class="login-screen">
    <section class="login-story" aria-label="heron 控制台">
      <div class="brand">
        <svg viewBox="0 0 32 36" fill="none" aria-hidden="true" class="brand-mark">
          <path
            d="M5 27 15 10 24 6 19 15 28 28M15 10 15 27M6 28h21M19 15l-4 12"
            stroke="currentColor"
            stroke-width="2.2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        <span>heron<span class="brand-caption">CONTENT, WITH CONFIDENCE.</span></span>
      </div>
      <div class="login-editorial">
        <span class="eyebrow">ONE CONSOLE, YOUR ACCESS</span>
        <h1>一个入口，<br />进入你能管理的那部分。</h1>
        <p>
          工作空间成员与平台运营共用同一个登录入口：邮箱进内容工作台，用户名进平台控制台。
          你能看到哪些页面，由服务端按身份下发。
        </p>
        <div class="editorial-line" aria-label="内容生产流程">
          <span>发现选题</span><i></i><span>内容生产</span><i></i><span>人工确认</span>
        </div>
      </div>
      <span class="login-story-footer">自主部署 / 租户隔离 / 人工确认</span>
    </section>
    <div class="login-form-side">
      <div class="login-appearance"><ThemeSwitcher /></div>
      <ElForm class="login-form" label-position="top" @submit.prevent="submit">
        <span class="eyebrow">WELCOME TO HERON</span>
        <h2>登录 heron</h2>
        <p>菜单与页面权限由服务端按你的身份下发。</p>
        <ElFormItem label="账号" for="login-identifier">
          <ElInput
            id="login-identifier"
            v-model="form.identifier"
            type="text"
            autocomplete="username"
            placeholder="工作空间邮箱，或平台管理员用户名"
            maxlength="254"
          />
        </ElFormItem>
        <ElFormItem label="密码" for="login-password">
          <ElInput
            id="login-password"
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            :placeholder="`至少 ${MIN_PASSWORD_LENGTH} 位`"
            maxlength="128"
          />
        </ElFormItem>
        <ElAlert
          v-if="auth.error"
          class="page-alert"
          :title="auth.error"
          type="error"
          :closable="false"
          show-icon
        />
        <ElButton
          class="login-submit"
          type="primary"
          native-type="submit"
          :disabled="!ready"
          :loading="auth.busy"
          >进入控制台</ElButton
        >
        <div class="login-footer">
          <span>没有账号？请联系空间管理员或部署管理员</span>
        </div>
      </ElForm>
    </div>
  </div>
</template>
