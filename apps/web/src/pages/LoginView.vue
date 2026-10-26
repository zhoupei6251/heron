<script setup lang="ts">
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput } from "element-plus";
import { computed, onBeforeUnmount, reactive, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";

import { safeRedirect } from "../router/guards";
import ThemeSwitcher from "../components/ThemeSwitcher.vue";
import { useAuthStore } from "../stores/auth";
import { usePlatformAuthStore } from "../stores/platform-auth";

const tenantAuth = useAuthStore();
const platformAuth = usePlatformAuthStore();
const router = useRouter();
const route = useRoute();
const platform = computed(() => route.meta.platform === true);
const auth = computed(() => (platform.value ? platformAuth : tenantAuth));
const form = reactive({ tenant: "default", email: "", password: "" });
const ready = computed(
  () =>
    (platform.value || form.tenant.trim() !== "") &&
    form.email.trim() !== "" &&
    form.password.length >= 12,
);
let submission = 0;
let mounted = true;
watch(
  platform,
  () => {
    submission++;
    form.email = "";
    form.password = "";
    auth.value.error = null;
  },
  { flush: "sync" },
);
onBeforeUnmount(() => {
  mounted = false;
  submission++;
});

async function submit(): Promise<void> {
  if (!ready.value || auth.value.busy) return;
  const current = ++submission;
  const isPlatform = platform.value;
  const destination = safeRedirect(route.query.redirect, isPlatform ? "platform" : "tenant");
  const ownsForm = () => mounted && current === submission && isPlatform === platform.value;
  try {
    const credentials = { email: form.email.trim(), password: form.password };
    const applied = isPlatform
      ? await platformAuth.login(credentials)
      : await tenantAuth.login({ ...credentials, tenant: form.tenant.trim() });
    if (!applied || !ownsForm()) return;
    form.password = "";
    await router.replace(destination);
  } catch {
    if (ownsForm()) form.password = "";
  }
}
</script>

<template>
  <div class="login-screen">
    <section class="login-story" aria-label="heron 内容工作室">
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
        <span class="eyebrow">{{
          platform ? "PLATFORM OPERATIONS" : "YOUR CONTENT WORKSPACE"
        }}</span>
        <h1>
          {{ platform ? "连接每个空间，" : "把值得关注的事，" }}<br />{{
            platform ? "守好每一道边界。" : "变成值得发布的内容。"
          }}
        </h1>
        <p>
          {{
            platform
              ? "在独立的运营控制台管理租户、查看用量与审计，让内容工作室各自有序运转。"
              : "从发现选题，到审阅成片。在一个有序的工作空间里，让每一次创作有迹可循。"
          }}
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
        <span class="eyebrow">{{ platform ? "PLATFORM ACCESS" : "WELCOME TO HERON" }}</span>
        <h2>{{ platform ? "登录平台控制台" : "回到你的工作空间" }}</h2>
        <p>
          {{
            platform
              ? "使用独立的平台管理员账号，不接受工作空间账号。"
              : "所有选题、任务与团队，都在这里。"
          }}
        </p>
        <ElFormItem v-if="!platform" label="工作空间" for="login-tenant">
          <ElInput
            id="login-tenant"
            v-model="form.tenant"
            autocomplete="organization"
            placeholder="工作空间标识，例如 default"
            maxlength="63"
          />
        </ElFormItem>
        <ElFormItem label="邮箱" for="login-email">
          <ElInput
            id="login-email"
            v-model="form.email"
            type="email"
            autocomplete="username"
            placeholder="输入你的邮箱"
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
            placeholder="至少 12 位"
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
          >{{ platform ? "进入平台控制台" : "进入工作空间" }}</ElButton
        >
        <div class="login-footer">
          <span>{{ platform ? "平台账号由部署管理员创建" : "没有账号？请联系空间管理员" }}</span
          ><RouterLink :to="platform ? '/login' : '/platform/login'">{{
            platform ? "工作空间登录" : "平台管理入口"
          }}</RouterLink>
        </div>
      </ElForm>
    </div>
  </div>
</template>
