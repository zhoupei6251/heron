<script setup lang="ts">
import {
  PLAN_IDS,
  PLATFORM_SETTING_DEFS,
  type PlatformSettingItem,
  type PlatformSettings,
  type PlanId,
} from "@heron/contracts";
import { PLAN_LABELS } from "@heron/domain";
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
  ElTag,
} from "element-plus";
import { onMounted, ref } from "vue";
import { errorMessage, request } from "../../api/http";
import { dateTime } from "../../format";

const item = ref<PlatformSettingItem[]>([]);
const baseUrl = ref("");
const modelName = ref("");
const retentionDays = ref<number | null>(null);
const plan = ref<PlanId>("free");
const loading = ref(false);
const saving = ref(false);
const error = ref<string | null>(null);

/** 每项「默认还是设过」的状态来自服务端：这里只按 key 取出，界面与库里的判断就不会分叉。 */
function apply(settings: PlatformSettings) {
  item.value = settings.items;
  const byKey = new Map(settings.items.map((entry) => [entry.key, entry.value]));
  baseUrl.value = byKey.get("default_model_base_url") ?? "";
  modelName.value = byKey.get("default_model_name") ?? "";
  const days = Number(byKey.get("default_retention_days") ?? "");
  retentionDays.value = Number.isFinite(days) && days > 0 ? days : null;
  plan.value = (byKey.get("default_plan") ?? "free") as PlanId;
}

async function load() {
  loading.value = true;
  error.value = null;
  try {
    apply(await request<PlatformSettings>("/platform/settings"));
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    loading.value = false;
  }
}

async function save() {
  saving.value = true;
  error.value = null;
  try {
    const next = await request<PlatformSettings>("/platform/settings", {
      method: "PUT",
      // 四项一起交；空串表示「恢复默认」。值没变的项服务端不写、也不记审计。
      body: JSON.stringify({
        values: {
          default_model_base_url: baseUrl.value.trim(),
          default_model_name: modelName.value.trim(),
          default_retention_days: retentionDays.value === null ? "" : String(retentionDays.value),
          default_plan: plan.value,
        },
      }),
    });
    apply(next);
    ElMessage.success("平台设置已保存");
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
        <span class="eyebrow">Platform / defaults</span>
        <h1>平台设置</h1>
        <p class="page-description">
          一台机器一份的默认值。租户在工作空间设置里自己配了，就以租户的为准；这里是「没配时的那一层」。
        </p>
      </div>
      <div class="page-actions">
        <ElButton type="primary" :loading="saving" @click="save">保存</ElButton>
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
    <div class="panel">
      <div class="panel-header">
        <h2>租户没配时用的默认值</h2>
        <ElTag :type="loading ? 'info' : 'success'">{{ loading ? "读取中" : "已加载" }}</ElTag>
      </div>
      <div class="panel-body">
        <ElForm label-position="top">
          <ElFormItem label="默认模型地址">
            <ElInput
              v-model="baseUrl"
              :placeholder="PLATFORM_SETTING_DEFS.default_model_base_url.fallback"
              clearable
            />
            <p class="form-hint">{{ PLATFORM_SETTING_DEFS.default_model_base_url.hint }}</p>
          </ElFormItem>
          <ElFormItem label="默认模型名">
            <ElInput
              v-model="modelName"
              placeholder="例如 qiniu/conatus-free；留空表示不设默认"
              clearable
            />
            <p class="form-hint">{{ PLATFORM_SETTING_DEFS.default_model_name.hint }}</p>
          </ElFormItem>
          <ElFormItem label="默认成片保留天数">
            <ElInputNumber
              v-model="retentionDays"
              :min="1"
              :max="3650"
              :step="1"
              step-strictly
              controls-position="right"
            />
            <p class="form-hint">
              {{ PLATFORM_SETTING_DEFS.default_retention_days.hint }}留空按内置 30 天。
            </p>
          </ElFormItem>
          <ElFormItem label="新空间默认档位">
            <ElSelect v-model="plan" style="max-width: 240px">
              <ElOption v-for="id in PLAN_IDS" :key="id" :label="PLAN_LABELS[id]" :value="id" />
            </ElSelect>
            <p class="form-hint">{{ PLATFORM_SETTING_DEFS.default_plan.hint }}</p>
          </ElFormItem>
        </ElForm>
        <p class="form-hint">
          模型密钥不在这里：它只活在租户自己的「工作空间设置」或服务器环境变量里，平台表既不存也不回显。
        </p>
      </div>
    </div>
    <div class="panel">
      <div class="panel-header">
        <h2>当前来源</h2>
      </div>
      <div class="panel-body">
        <ul class="setting-sources">
          <li v-for="entry in item" :key="entry.key">
            <span class="mono">{{ entry.key }}</span>
            <ElTag :type="entry.isDefault ? 'info' : 'success'">
              {{ entry.isDefault ? "内置默认" : `平台设置 · ${dateTime(entry.updatedAt)}` }}
            </ElTag>
            <span v-if="!entry.isDefault && entry.updatedBy" class="form-hint mono">
              {{ entry.updatedBy.slice(0, 8) }}
            </span>
          </li>
        </ul>
        <p class="form-hint">
          解析顺序：租户设置 → 平台设置 → 环境变量 → 内置默认。每次改动都在平台审计里留一行
          <span class="mono">platform_setting.updated</span>，带改前与改后的值。
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.setting-sources {
  display: grid;
  gap: 10px;
  margin: 0 0 16px;
  padding: 0;
  list-style: none;
  font-size: 12px;
}

.setting-sources li {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
