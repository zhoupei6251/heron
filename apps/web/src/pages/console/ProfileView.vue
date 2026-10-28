<script setup lang="ts">
import {
  MIN_PASSWORD_LENGTH,
  PasswordBodySchema,
  ProfileBodySchema,
  type AuthUser,
} from "@heron/contracts";
import {
  ElAlert,
  ElButton,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElTag,
} from "element-plus";
import { onBeforeUnmount, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { request, errorMessage, sessionGeneration } from "../../api/http";
import { roleLabel, VIEW_FENCE } from "../../auth/roles";
import { useAuthStore } from "../../stores/auth";
const auth = useAuthStore();
const router = useRouter();
let mounted = true;
onBeforeUnmount(() => {
  mounted = false;
});
const ownsSession = (generation: number) => mounted && generation === sessionGeneration();
const name = ref(auth.user?.displayName ?? "");
const saving = ref(false);
const error = ref<string | null>(null);
const password = reactive({ currentPassword: "", newPassword: "", confirm: "" });
async function saveProfile() {
  const parsed = ProfileBodySchema.safeParse({ displayName: name.value });
  if (!parsed.success) {
    error.value = "请输入 1–80 字的姓名。";
    return;
  }
  const generation = sessionGeneration();
  saving.value = true;
  error.value = null;
  try {
    const user = await request<AuthUser>("/auth/profile", {
      method: "PATCH",
      body: JSON.stringify(parsed.data),
    });
    if (!ownsSession(generation)) return;
    auth.applyUser(user);
    ElMessage.success("个人资料已保存");
  } catch (cause) {
    if (ownsSession(generation)) error.value = errorMessage(cause);
  } finally {
    if (ownsSession(generation)) saving.value = false;
  }
}
async function changePassword() {
  let generation = sessionGeneration();
  const parsed = PasswordBodySchema.safeParse(password);
  if (!parsed.success || password.newPassword !== password.confirm) {
    error.value = `请输入当前密码、新密码（${String(MIN_PASSWORD_LENGTH)}–128 位），并确保两次新密码一致。`;
    return;
  }
  try {
    await ElMessageBox.confirm(
      "修改密码后，所有设备的登录会话都会失效，需要重新登录。",
      "修改密码",
      { confirmButtonText: "修改并退出", cancelButtonText: "取消", type: "warning" },
    );
  } catch {
    return;
  }
  if (!ownsSession(generation)) return;
  saving.value = true;
  error.value = null;
  try {
    await request("/auth/password", { method: "POST", body: JSON.stringify(parsed.data) });
    if (!ownsSession(generation)) return;
    Object.assign(password, { currentPassword: "", newPassword: "", confirm: "" });
    auth.clear();
    generation = sessionGeneration();
    ElMessage.success("密码已修改，请重新登录");
    await router.replace("/login");
  } catch (cause) {
    if (ownsSession(generation)) error.value = errorMessage(cause);
  } finally {
    if (ownsSession(generation)) saving.value = false;
  }
}
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Your account</span>
        <h1>个人资料</h1>
        <p class="page-description">管理你的显示名称和账号安全。</p>
      </div>
    </header>
    <ElAlert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="page-alert"
    />
    <ElAlert
      v-if="auth.readOnly"
      :title="VIEW_FENCE"
      type="info"
      show-icon
      :closable="false"
      class="page-alert"
    />
    <div class="panel form-panel">
      <div class="form-section">
        <h2>基本资料</h2>
        <ElForm label-position="top" @submit.prevent="saveProfile"
          ><ElFormItem label="邮箱"
            ><span>{{ auth.user?.email }}</span></ElFormItem
          ><ElFormItem label="当前角色"
            ><ElTag v-if="auth.user">{{ roleLabel(auth.user.role) }}</ElTag></ElFormItem
          ><ElFormItem label="显示名称" for="profile-name" required
            ><ElInput
              id="profile-name"
              v-model="name"
              maxlength="80"
              autocomplete="name"
              :disabled="auth.readOnly" /></ElFormItem
          ><ElButton type="primary" native-type="submit" :loading="saving" :disabled="auth.readOnly"
            >保存资料</ElButton
          ></ElForm
        >
      </div>
      <div class="form-section">
        <h2>修改密码</h2>
        <p class="form-hint" style="margin-bottom: 20px">
          使用至少 {{ MIN_PASSWORD_LENGTH }} 位的独立密码。修改后将退出所有设备。
        </p>
        <ElForm label-position="top" @submit.prevent="changePassword"
          ><ElFormItem label="当前密码" for="current-password" required
            ><ElInput
              id="current-password"
              v-model="password.currentPassword"
              type="password"
              show-password
              autocomplete="current-password"
              maxlength="128"
              :disabled="auth.readOnly" /></ElFormItem
          ><ElFormItem label="新密码" for="new-password" required
            ><ElInput
              id="new-password"
              v-model="password.newPassword"
              type="password"
              show-password
              autocomplete="new-password"
              maxlength="128"
              :disabled="auth.readOnly" /></ElFormItem
          ><ElFormItem label="确认新密码" for="confirm-password" required
            ><ElInput
              id="confirm-password"
              v-model="password.confirm"
              type="password"
              show-password
              autocomplete="new-password"
              maxlength="128"
              :disabled="auth.readOnly" /></ElFormItem
          ><ElButton native-type="submit" :loading="saving" :disabled="auth.readOnly"
            >修改密码并退出</ElButton
          ></ElForm
        >
      </div>
    </div>
  </section>
</template>
