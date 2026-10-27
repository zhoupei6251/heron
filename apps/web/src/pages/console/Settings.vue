<script setup lang="ts">
import { WorkspaceBodySchema, type Tenant, type GateState } from "@heron/contracts";
import { can } from "@heron/domain";
import {
  ElAlert,
  ElButton,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { settingsApi, gatesApi } from "../../api";
import { request, errorMessage, sessionGeneration } from "../../api/http";
import { useAuthStore } from "../../stores/auth";
const auth = useAuthStore();
let mounted = true;
onBeforeUnmount(() => {
  mounted = false;
});
const ownsSession = (generation: number) => mounted && generation === sessionGeneration();
const allowed = computed(() => !!auth.user && can(auth.user.role, "settings.write"));
const workspace = ref<Tenant | null>(null);
const gates = ref<GateState | null>(null);
const name = ref("");
const days = ref<number | undefined>();
/** 七牛当前的三个免费模型；下拉可搜索，也可直接输入别家的模型 id。 */
const modelPresets = [
  "qiniu/conatus-free",
  "nvidia/nemotron-3-ultra-550b-a55b0-free",
  "moonshotai/kimi-k3-free",
];
const modelBaseUrl = ref("");
const modelName = ref("");
const modelApiKey = ref("");
const hasApiKey = ref(false);
const loading = ref(false);
const saving = ref(false);
const error = ref<string | null>(null);
async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [tenant, state, settings] = await Promise.all([
      request<Tenant>("/manage/workspace"),
      gatesApi.state(),
      allowed.value ? settingsApi.get() : Promise.resolve(null),
    ]);
    workspace.value = tenant;
    name.value = tenant.name;
    gates.value = state;
    days.value = settings?.retentionDays;
    modelBaseUrl.value = settings?.model.baseUrl ?? "";
    modelName.value = settings?.model.model ?? "";
    hasApiKey.value = settings?.model.hasApiKey ?? false;
    modelApiKey.value = "";
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    loading.value = false;
  }
}
async function saveWorkspace() {
  const parsed = WorkspaceBodySchema.safeParse({ name: name.value });
  if (!parsed.success) {
    error.value = "请输入 1–100 字的工作空间名称。";
    return;
  }
  const generation = sessionGeneration();
  saving.value = true;
  error.value = null;
  try {
    const tenant = await request<Tenant>("/manage/workspace", {
      method: "PATCH",
      body: JSON.stringify(parsed.data),
    });
    if (!ownsSession(generation)) return;
    workspace.value = tenant;
    if (auth.user) auth.applyUser({ ...auth.user, tenantName: tenant.name });
    ElMessage.success("工作空间已更新");
  } catch (cause) {
    if (ownsSession(generation)) error.value = errorMessage(cause);
  } finally {
    if (ownsSession(generation)) saving.value = false;
  }
}
async function saveRetention() {
  if (!Number.isInteger(days.value) || !days.value || days.value < 1 || days.value > 3650) {
    error.value = "保留天数必须是 1–3650 的整数。";
    return;
  }
  saving.value = true;
  error.value = null;
  try {
    days.value = (await settingsApi.setRetention(days.value)).retentionDays;
    ElMessage.success("保留策略已更新");
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    saving.value = false;
  }
}
async function saveModel() {
  const baseUrl = modelBaseUrl.value.trim();
  const model = modelName.value.trim();
  try {
    const parsed = new URL(baseUrl);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") throw new Error();
  } catch {
    error.value = "接口地址必须是 http(s) 的完整 URL，例如 https://api.qnaigc.com/v1。";
    return;
  }
  if (!model) {
    error.value = "请选择或填写模型 id。";
    return;
  }
  saving.value = true;
  error.value = null;
  try {
    const saved = await settingsApi.setModel({
      baseUrl,
      model,
      apiKey: modelApiKey.value.trim() || undefined,
    });
    modelBaseUrl.value = saved.baseUrl;
    modelName.value = saved.model;
    hasApiKey.value = saved.hasApiKey;
    modelApiKey.value = "";
    ElMessage.success("模型配置已保存");
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    saving.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Workspace / preferences</span>
        <h1>工作空间设置</h1>
        <p class="page-description">管理空间信息、成片保留与写稿模型，查看生产安全门禁。</p>
      </div>
      <div class="page-actions"><ElButton :loading="loading" @click="load">重新读取</ElButton></div>
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
      v-if="!allowed"
      title="当前为只读查看，修改设置需要管理员或所有者权限。"
      type="info"
      :closable="false"
      class="page-alert"
    />
    <div class="split-grid">
      <div class="panel">
        <div class="form-section">
          <h2>空间信息</h2>
          <ElForm label-position="top" @submit.prevent="saveWorkspace"
            ><ElFormItem label="工作空间名称" for="workspace-name"
              ><ElInput
                id="workspace-name"
                v-model="name"
                maxlength="100"
                :disabled="!allowed || !workspace" /></ElFormItem
            ><ElFormItem label="登录标识"
              ><span class="mono">{{ workspace?.slug ?? "—" }}</span></ElFormItem
            >
            <p class="form-hint">登录标识由平台开通时设置，不随名称变更。</p>
            <div class="form-actions">
              <ElButton
                type="primary"
                native-type="submit"
                :loading="saving"
                :disabled="!allowed || !workspace || loading"
                >保存空间信息</ElButton
              >
            </div></ElForm
          >
        </div>
        <div v-if="allowed" class="form-section">
          <h2>成片保留策略</h2>
          <ElForm label-position="top" @submit.prevent="saveRetention"
            ><ElFormItem label="保留天数" for="retention-days"
              ><ElInputNumber
                id="retention-days"
                v-model="days"
                :min="1"
                :max="3650"
                :precision="0"
                :disabled="loading"
            /></ElFormItem>
            <p class="form-hint">保存的是保留策略，不会立即删除文件。实际清理由服务端流程执行。</p>
            <div class="form-actions">
              <ElButton
                native-type="submit"
                :loading="saving"
                :disabled="days === undefined || loading"
                >保存保留策略</ElButton
              >
            </div></ElForm
          >
        </div>
        <div v-if="allowed" class="form-section">
          <h2>写稿模型</h2>
          <ElForm label-position="top" @submit.prevent="saveModel"
            ><ElFormItem label="接口地址" for="model-base-url"
              ><ElInput
                id="model-base-url"
                v-model="modelBaseUrl"
                :disabled="loading"
                placeholder="https://api.qnaigc.com/v1" /></ElFormItem
            ><ElFormItem label="模型" for="model-name"
              ><ElSelect
                id="model-name"
                v-model="modelName"
                filterable
                allow-create
                default-first-option
                :disabled="loading"
                placeholder="选一个免费模型，或直接输入别家的模型 id"
                style="width: 100%"
                ><ElOption
                  v-for="preset in modelPresets"
                  :key="preset"
                  :label="preset"
                  :value="preset" /></ElSelect></ElFormItem
            ><ElFormItem label="API Key" for="model-api-key"
              ><ElInput
                id="model-api-key"
                v-model="modelApiKey"
                type="password"
                show-password
                autocomplete="new-password"
                :disabled="loading"
                :placeholder="hasApiKey ? '已配置，留空不改' : '粘贴供应商的 API key'"
            /></ElFormItem>
            <p class="form-hint">
              写稿阶段由 worker 使用；没填的项回落到服务端环境变量。密钥只用于调用模型，接口不回显。
            </p>
            <div class="form-actions">
              <ElButton native-type="submit" :loading="saving" :disabled="loading"
                >保存模型配置</ElButton
              >
            </div></ElForm
          >
        </div>
      </div>
      <div class="panel">
        <div class="panel-header">
          <h2>生产门禁</h2>
          <ElTag :type="gates?.open ? 'success' : 'warning'">{{
            gates?.open ? "已满足" : "受限"
          }}</ElTag>
        </div>
        <ElTable :data="gates?.locations ?? []" empty-text="门禁尚未读取"
          ><ElTableColumn prop="gate" label="闸门" width="115" /><ElTableColumn
            label="状态"
            width="100"
            ><template #default="{ row }"
              ><ElTag :type="row.satisfied ? 'success' : 'warning'">{{
                row.satisfied ? "满足" : "未满足"
              }}</ElTag></template
            ></ElTableColumn
          ><ElTableColumn prop="detail" label="说明" min-width="200"
        /></ElTable>
        <div class="panel-body">
          <p class="form-hint">
            这里展示服务端实际门禁，不能用设置开关跳过事实核查、合规或人工确认。
          </p>
        </div>
      </div>
    </div>
  </section>
</template>
