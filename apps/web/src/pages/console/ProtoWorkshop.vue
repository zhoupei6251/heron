<script setup lang="ts">
import {
  PROTO_REQUIREMENT_MAX,
  PROTO_THEMES,
  type CreateProtoBody,
  type ProtoEngineStatus,
  type ProtoFailureKind,
  type ProtoProjectDetail,
  type ProtoProjectSummary,
  type ProtoRevision,
  type ProtoStage,
  type ProtoTheme,
} from "@heron/contracts";
import { Plus } from "lucide-vue-next";
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDrawer,
  ElFormItem,
  ElInput,
  ElLink,
  ElMessage,
  ElOption,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import { computed, onMounted, reactive, ref, watch } from "vue";

import { errorMessage } from "../../api/http";
import { protoApi, protoArtifactUrl } from "../../api/proto";
import { VIEW_FENCE } from "../../auth/roles";
import ListPanel from "../../components/ListPanel.vue";
import { usePagedList } from "../../composables/usePagedList";
import { dateTime } from "../../format";
import { useAuthStore } from "../../stores/auth";

const auth = useAuthStore();
const list = reactive(usePagedList<ProtoProjectSummary>("/proto"));
const capability = ref<ProtoEngineStatus | null>(null);
const detail = ref<ProtoProjectDetail | null>(null);
const drawer = ref(false);
const busy = ref(false);

/** 阶段话术只在界面这一处：状态名进契约，人怎么读它是设计。 */
const STAGE: Record<
  ProtoStage,
  { label: string; type: "info" | "primary" | "success" | "warning" | "danger" }
> = {
  draft: { label: "待生成", type: "info" },
  scripting: { label: "写稿中", type: "primary" },
  compiling: { label: "编译中", type: "primary" },
  review: { label: "待人工确认", type: "warning" },
  approved: { label: "已确认，待交付", type: "warning" },
  delivered: { label: "已交付", type: "success" },
  failed: { label: "失败", type: "danger" },
};
const THEME_LABEL: Record<ProtoTheme, string> = {
  "enterprise-light": "企业浅色",
  "modern-dark": "现代深色",
  "cobalt-pro": "钴蓝专业",
  "mobile-lowfi": "移动端低保真",
  "mobile-hifi": "移动端高保真",
};
const FAILURE_LABEL: Record<ProtoFailureKind, string> = {
  model: "模型：超时、限流或空输出",
  contract: "契约闸：RP/1 结构校验未过",
  visual: "视觉闸：审美四项未过",
  engine: "引擎：依赖或空白模板缺失",
};

const writable = computed(() => auth.canWrite("task.claim"));
const confirmable = computed(() => auth.canWrite("review.confirm"));
const ready = computed(
  () => !!capability.value && capability.value.engine.ok && capability.value.template.ok,
);
/** 缺引擎或缺模板时说的那句话，直接用探针原文：它已经点名了变量名与获取方式。 */
const machineReason = computed(() => {
  const status = capability.value;
  if (!status) return "正在读取这台机器的引擎与模板状态…";
  return status.engine.ok ? status.template.detail : status.engine.detail;
});
const blocker = computed(() => {
  const status = capability.value;
  if (!writable.value) return "开始生成需要操作账号（operator）及以上权限。";
  if (!ready.value) return machineReason.value;
  if (status && status.inflight >= status.maxInflight) {
    return `本工作区同时最多 ${String(status.maxInflight)} 条在途，当前 ${String(status.inflight)} 条都在跑，稍后再提。`;
  }
  return "";
});

const createOpen = ref(false);
const form = reactive<CreateProtoBody>({
  title: "",
  requirement: "",
  target: "web",
  theme: "enterprise-light",
});
const formError = ref<string | null>(null);
const themeOptions = computed<ProtoTheme[]>(() =>
  PROTO_THEMES.filter((theme) =>
    form.target === "mobile" ? theme.startsWith("mobile-") : !theme.startsWith("mobile-"),
  ),
);
watch(
  () => form.target,
  () => {
    if (!themeOptions.value.includes(form.theme)) form.theme = themeOptions.value[0]!;
  },
);

function openCreate(): void {
  Object.assign(form, { title: "", requirement: "", target: "web", theme: "enterprise-light" });
  formError.value = null;
  createOpen.value = true;
}

async function create(): Promise<void> {
  const parsed = { ...form, requirement: form.requirement.trim() };
  if (!parsed.title.trim() || !parsed.requirement) {
    formError.value = "项目名与需求描述都不能为空。";
    return;
  }
  busy.value = true;
  formError.value = null;
  try {
    const created = await protoApi.create(parsed);
    createOpen.value = false;
    ElMessage.success("已排入生成队列，需求原文交给模型写 RP/1");
    detail.value = created;
    drawer.value = true;
    await Promise.all([list.load(), loadCapability()]);
  } catch (cause) {
    formError.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}

async function loadCapability(): Promise<void> {
  try {
    capability.value = await protoApi.capability();
  } catch (cause) {
    capability.value = null;
    list.error = errorMessage(cause);
  }
}

async function openDetail(row: ProtoProjectSummary): Promise<void> {
  busy.value = true;
  axureVerified.value = false;
  try {
    detail.value = await protoApi.detail(row.id);
    drawer.value = true;
  } catch (cause) {
    list.error = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}

const latest = computed<ProtoRevision | null>(() => detail.value?.revisions[0] ?? null);
const axureVerified = ref(false);
const regenBlocker = computed(() => {
  const stage = detail.value?.stage;
  if (stage && stage !== "delivered" && stage !== "failed") {
    return `只有在已交付或失败后才能重新生成，当前停在「${STAGE[stage].label}」。`;
  }
  return blocker.value;
});
const confirmBlocker = computed(() => {
  if (!confirmable.value) return "确认需要审片账号（reviewer）及以上权限。";
  if (detail.value?.stage !== "review") return "只有停在「待人工确认」的版本能被确认。";
  if (!latest.value?.previewSha256) return "这一版还没有预览图指纹，先刷新再看。";
  return "";
});

async function confirmRevision(): Promise<void> {
  const project = detail.value;
  const revision = latest.value;
  if (!project || !revision?.previewSha256) return;
  busy.value = true;
  list.error = null;
  try {
    detail.value = await protoApi.confirm(project.id, {
      revisionId: revision.id,
      previewSha256: revision.previewSha256,
      axureVerified: axureVerified.value,
    });
    axureVerified.value = false;
    ElMessage.success("已确认，交付排进队列");
    await Promise.all([list.load(), loadCapability()]);
  } catch (cause) {
    list.error = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}

async function regenerate(projectId: string): Promise<void> {
  busy.value = true;
  list.error = null;
  try {
    detail.value = await protoApi.generate(projectId);
    ElMessage.success("重跑已开始：已交付的旧版本不会被覆盖");
    await Promise.all([list.load(), loadCapability()]);
  } catch (cause) {
    list.error = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}

function sizeOf(revision: ProtoRevision): string {
  return revision.rpBytes === null ? "—" : `${String(Math.ceil(revision.rpBytes / 1024))} KB`;
}

onMounted(() => {
  void list.load();
  void loadCapability();
});
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Proto / workshop</span>
        <h1>原型工坊</h1>
        <p class="page-description">
          需求 → RP/1 源码 → 原生 .rp：每一版都留预览、审计与下载凭证。
        </p>
      </div>
      <div class="page-actions">
        <ElTag v-if="capability" :type="ready ? 'success' : 'danger'">
          {{ ready ? "引擎与模板就绪" : "引擎或模板缺失" }} · 在途
          {{ String(capability.inflight) }} / {{ String(capability.maxInflight) }}
        </ElTag>
        <ElButton type="primary" :icon="Plus" :disabled="!!blocker" @click="openCreate"
          >新建原型</ElButton
        >
      </div>
    </header>
    <ElAlert
      v-if="auth.readOnly"
      :title="VIEW_FENCE"
      type="info"
      show-icon
      :closable="false"
      class="page-alert"
    />
    <ElAlert
      v-else-if="capability && !ready"
      :title="`生成已置灰：${machineReason}`"
      type="warning"
      show-icon
      :closable="false"
      class="page-alert"
    />
    <p v-if="blocker && writable && ready" class="form-hint">{{ blocker }}</p>
    <ListPanel
      v-model:query="list.query"
      v-model:page="list.page"
      v-model:page-size="list.pageSize"
      :total="list.total"
      :loading="list.loading"
      :error="list.error"
      placeholder="搜索项目名称"
      empty-title="还没有原型项目"
      empty-hint="写下需求，模型生成 RP/1，引擎编出可在 Axure 打开的 .rp。"
      @search="list.search"
      @refresh="list.load"
    >
      <template #empty-action>
        <ElButton type="primary" :icon="Plus" :disabled="!!blocker" @click="openCreate"
          >新建原型</ElButton
        >
      </template>
      <ElTable :data="list.items" row-key="id">
        <ElTableColumn label="原型项目" min-width="300">
          <template #default="{ row }">
            <div class="cell-title">{{ row.title }}</div>
            <div class="cell-subtitle">
              {{ row.target === "mobile" ? "移动端" : "Web 管理后台" }} ·
              {{ THEME_LABEL[row.theme as ProtoTheme] }} · {{ row.id.slice(0, 8) }}
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="阶段" width="150">
          <template #default="{ row }">
            <ElTag :type="STAGE[row.stage as ProtoStage].type">
              {{ STAGE[row.stage as ProtoStage].label }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="版本" width="90" align="right">
          <template #default="{ row }">{{ row.latestVersion ?? "—" }}</template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" min-width="170">
          <template #default="{ row }">{{ dateTime(row.updatedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <ElButton
              v-if="row.errorReason"
              link
              type="danger"
              @click="openDetail(row as ProtoProjectSummary)"
              >失败原因</ElButton
            >
            <ElButton link type="primary" @click="openDetail(row as ProtoProjectSummary)"
              >详情</ElButton
            >
          </template>
        </ElTableColumn>
      </ElTable>
    </ListPanel>

    <ElDialog v-model="createOpen" title="新建原型项目" width="620px">
      <ElAlert
        v-if="formError"
        :title="formError"
        type="error"
        :closable="false"
        class="page-alert"
      />
      <ElFormItem label="项目名" for="proto-title" required>
        <ElInput
          id="proto-title"
          v-model="form.title"
          maxlength="100"
          placeholder="例如：客服工单后台"
        />
      </ElFormItem>
      <ElFormItem label="需求描述" for="proto-requirement" required>
        <ElInput
          id="proto-requirement"
          v-model="form.requirement"
          type="textarea"
          :rows="8"
          :maxlength="PROTO_REQUIREMENT_MAX"
          show-word-limit
          placeholder="要哪些页面、每页放什么、点哪里跳到哪里。写得越具体，一次生成的合格率越高。"
        />
      </ElFormItem>
      <ElFormItem label="画布">
        <ElRadioGroup v-model="form.target">
          <ElRadioButton value="web">Web 管理后台</ElRadioButton>
          <ElRadioButton value="mobile">移动端</ElRadioButton>
        </ElRadioGroup>
      </ElFormItem>
      <ElFormItem label="风格主题">
        <ElSelect v-model="form.theme">
          <ElOption
            v-for="theme in themeOptions"
            :key="theme"
            :label="THEME_LABEL[theme]"
            :value="theme"
          />
        </ElSelect>
      </ElFormItem>
      <p class="form-hint">同一份需求重复提交只会有一条在途；已交付的版本永不被重跑覆盖。</p>
      <template #footer>
        <ElButton :disabled="busy" @click="createOpen = false">取消</ElButton>
        <ElButton type="primary" :loading="busy" @click="create">开始生成</ElButton>
      </template>
    </ElDialog>

    <ElDrawer v-model="drawer" title="原型详情" size="680px">
      <template v-if="detail">
        <div class="detail-head">
          <h2>{{ detail.title }}</h2>
          <ElTag :type="STAGE[detail.stage].type">{{ STAGE[detail.stage].label }}</ElTag>
        </div>
        <pre v-if="detail.errorReason" class="preformatted-error">{{ detail.errorReason }}</pre>
        <ElDescriptions :column="1" :label-width="104" border>
          <ElDescriptionsItem label="需求原文">{{ detail.requirement }}</ElDescriptionsItem>
          <ElDescriptionsItem label="画布与主题">
            {{ detail.target === "mobile" ? "移动端" : "Web 管理后台" }} ·
            {{ THEME_LABEL[detail.theme] }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="视觉闸四项">
            <span v-if="!detail.gates.length" class="muted">还没有审计报告</span>
            <ElTag
              v-for="gate in detail.gates"
              :key="gate.key"
              :type="gate.passed ? 'success' : 'danger'"
              class="gate-tag"
              >{{ gate.label }}{{ gate.passed ? "通过" : "未过" }}</ElTag
            >
          </ElDescriptionsItem>
          <ElDescriptionsItem label="项目标识">
            <span class="mono">{{ detail.id }}</span>
          </ElDescriptionsItem>
        </ElDescriptions>

        <div class="drawer-actions">
          <ElButton
            type="primary"
            :disabled="!!regenBlocker || busy"
            :loading="busy"
            @click="regenerate(detail.id)"
            >重新生成</ElButton
          >
          <p class="form-hint">
            {{ regenBlocker || "重跑会开一个新版本，旧版本与它的产物都留着。" }}
          </p>
        </div>

        <h3 class="revision-title">版本（{{ detail.revisions.length }} 条）</h3>
        <article v-for="revision in detail.revisions" :key="revision.id" class="revision">
          <header>
            <strong>v{{ String(revision.version) }}</strong>
            <span class="muted">
              {{ revision.model ?? "未记录模型" }} ·
              {{ revision.engineVersion ?? "未记录引擎版本" }}
            </span>
            <ElTag v-if="!revision.axureVerified" type="warning">未在 Axure 验证</ElTag>
            <ElTag v-else type="success">已在 Axure 验证</ElTag>
          </header>
          <img
            v-if="revision.hasPreview"
            class="revision-preview"
            :src="protoArtifactUrl(detail.id, revision.version, 'preview')"
            :alt="`第 ${revision.version} 版预览图`"
          />
          <ElDescriptions :column="1" :label-width="104" border>
            <ElDescriptionsItem label="产物">
              <ElLink
                v-if="revision.hasRp"
                class="artifact"
                type="primary"
                underline="never"
                :href="protoArtifactUrl(detail.id, revision.version, 'rp')"
                >.rp 工程（{{ sizeOf(revision) }}）</ElLink
              >
              <ElLink
                v-if="revision.hasPreview"
                class="artifact"
                type="primary"
                underline="never"
                :href="protoArtifactUrl(detail.id, revision.version, 'preview')"
                >预览图</ElLink
              >
              <ElLink
                v-if="revision.hasAudit"
                class="artifact"
                type="primary"
                underline="never"
                :href="protoArtifactUrl(detail.id, revision.version, 'audit')"
                >审计报告</ElLink
              >
              <ElLink
                v-if="revision.hasSource"
                class="artifact"
                type="primary"
                underline="never"
                :href="protoArtifactUrl(detail.id, revision.version, 'source')"
                >RP/1 源码</ElLink
              >
              <span v-if="!revision.hasRp" class="muted"
                >这一版没装配出 .rp，原因见下方停机说明</span
              >
            </ElDescriptionsItem>
            <ElDescriptionsItem label="模型用量">
              {{ revision.tokensIn ?? "—" }} 入 / {{ revision.tokensOut ?? "—" }} 出
              token（当前只记账，不计费）
            </ElDescriptionsItem>
            <ElDescriptionsItem label="人工确认">
              {{
                revision.confirmedAt
                  ? `${dateTime(revision.confirmedAt)} · 确认人 ${revision.confirmedByName ?? revision.confirmedBy ?? "—"}`
                  : "尚未确认"
              }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="Axure 留痕">
              {{
                revision.axureVerifiedAt
                  ? `${dateTime(revision.axureVerifiedAt)} 勾选`
                  : "未勾选：交付后仍会显示「未在 Axure 验证」"
              }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="交付">
              {{ revision.deliveredAt ? dateTime(revision.deliveredAt) : "未交付" }}
            </ElDescriptionsItem>
            <ElDescriptionsItem v-if="revision.failureKind" label="失败分类">
              {{ FAILURE_LABEL[revision.failureKind] }}
            </ElDescriptionsItem>
          </ElDescriptions>
          <pre v-if="revision.failureReason" class="preformatted-error">{{
            revision.failureReason
          }}</pre>
        </article>

        <div class="drawer-actions">
          <ElCheckbox v-model="axureVerified" :disabled="!!confirmBlocker || busy">
            我已在 Axure 里打开这一版，页面与交互都对得上
          </ElCheckbox>
          <ElButton
            type="primary"
            :disabled="!!confirmBlocker || busy"
            :loading="busy"
            @click="confirmRevision"
            >确认这一版</ElButton
          >
          <p class="form-hint">
            {{
              confirmBlocker ||
              `记录确认人、确认时间与预览图指纹（${latest?.previewSha256?.slice(0, 12) ?? "—"}…）；不勾选也能确认，但这一版会永久带着「未在 Axure 验证」。`
            }}
          </p>
        </div>
      </template>
    </ElDrawer>
  </section>
</template>

<style scoped>
.detail-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.detail-head h2 {
  margin: 0;
  font-size: 18px;
}

.gate-tag {
  margin-right: 6px;
}

.drawer-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
}

.drawer-actions .form-hint {
  flex-basis: 100%;
  margin: 0;
}

.drawer-actions :deep(.el-checkbox) {
  height: auto;
  margin-right: 0;
}

.drawer-actions :deep(.el-checkbox__label) {
  white-space: normal;
  line-height: 1.6;
}

.revision-title {
  margin: 26px 0 12px;
  font-size: 15px;
}

.revision {
  padding: 14px 0 4px;
  border-top: 1px solid var(--heron-line);
}

.revision header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.revision-preview {
  display: block;
  width: 100%;
  margin-bottom: 12px;
  border: 1px solid var(--heron-line);
  border-radius: 8px;
}

.artifact {
  margin-right: 14px;
}
</style>
