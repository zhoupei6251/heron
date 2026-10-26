<script setup lang="ts">
import { CircleCheck, Refresh, VideoCamera } from "@element-plus/icons-vue";
import type { GateState, ReviewItem } from "@heron/contracts";
import {
  ElAlert,
  ElButton,
  ElCheckbox,
  ElEmpty,
  ElIcon,
  ElMessage,
  ElSkeleton,
  ElTag,
} from "element-plus";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";

import { gatesApi } from "../api";
import { errorMessage, sessionGeneration } from "../api/http";
import { artifactUrl, reviewsApi } from "../api/reviews";
import { reviewBlocker } from "../auth/review";
import { useAuthStore } from "../stores/auth";

const auth = useAuthStore();
const route = useRoute();
const requestedTask = computed(() =>
  typeof route.query.task === "string" ? route.query.task : "",
);
const items = ref<ReviewItem[]>([]);
const gates = ref<GateState | null>(null);
const selectedId = ref(requestedTask.value);
const loading = ref(true);
const saving = ref(false);
const error = ref("");
const notice = ref("");
const sheetElement = ref<HTMLImageElement | null>(null);
const loadedSheet = ref("");
const queueGeneration = ref(0);
let mounted = true;
let querySelection = true;
const mediaError = ref("");
const acknowledged = ref(false);
const selected = computed(
  () => items.value.find((item) => item.taskId === selectedId.value) ?? null,
);
const sheetKey = computed(() =>
  JSON.stringify([
    selected.value?.taskId,
    selected.value?.renderVersion,
    selected.value?.contactSheetFingerprint,
    selected.value?.contactSheetPath,
    queueGeneration.value,
  ]),
);
const sheetLoaded = computed(
  () => !loading.value && !!sheetElement.value && loadedSheet.value === sheetKey.value,
);
const blocker = computed(() =>
  reviewBlocker(
    auth.user?.role ?? null,
    gates.value,
    selected.value,
    sheetLoaded.value,
    acknowledged.value,
  ),
);
watch(
  sheetKey,
  () => {
    acknowledged.value = false;
    loadedSheet.value = "";
    mediaError.value = "";
  },
  { flush: "sync" },
);

function currentSheetEvent(event: Event): boolean {
  const element = sheetElement.value;
  return (
    mounted &&
    !loading.value &&
    !!element &&
    element.isConnected &&
    event.target === element &&
    event.currentTarget === element &&
    element.dataset.artifact === sheetKey.value
  );
}
function sheetLoad(event: Event): void {
  if (!currentSheetEvent(event)) return;
  loadedSheet.value = sheetKey.value;
}
function sheetError(event: Event): void {
  if (!currentSheetEvent(event)) return;
  loadedSheet.value = "";
  acknowledged.value = false;
  mediaError.value = "联络表无法加载，不能确认；请检查产物或刷新队列";
}
function reconcileSelection(): void {
  notice.value = "";
  if (querySelection && requestedTask.value) {
    selectedId.value = requestedTask.value;
    if (!selected.value)
      notice.value = `任务 ${requestedTask.value} 不在当前待审队列中，可能已完成审核或尚未进入待审。请在左侧选择其他任务。`;
  } else if (!selected.value) {
    selectedId.value = items.value[0]?.taskId ?? "";
  }
}
function selectTask(taskId: string): void {
  querySelection = false;
  selectedId.value = taskId;
  notice.value = "";
}
watch(
  requestedTask,
  () => {
    querySelection = true;
    selectedId.value = requestedTask.value;
    notice.value = "";
    if (!loading.value) reconcileSelection();
  },
  { flush: "sync" },
);
onBeforeUnmount(() => {
  mounted = false;
  queueGeneration.value++;
});

async function refresh(): Promise<void> {
  const current = ++queueGeneration.value;
  const session = sessionGeneration("tenant");
  const ownsRequest = () =>
    mounted && current === queueGeneration.value && session === sessionGeneration("tenant");
  loading.value = true;
  error.value = "";
  gates.value = null;
  try {
    const [queue, state] = await Promise.all([reviewsApi.pending(), gatesApi.state()]);
    if (!ownsRequest()) return;
    items.value = queue.reviews;
    gates.value = state;
    reconcileSelection();
  } catch (cause) {
    if (ownsRequest()) error.value = errorMessage(cause);
  } finally {
    if (ownsRequest()) loading.value = false;
  }
}

async function confirm(): Promise<void> {
  const item = selected.value;
  if (blocker.value || loading.value || saving.value || !item?.contactSheetFingerprint) return;
  saving.value = true;
  error.value = "";
  try {
    await reviewsApi.confirm(item.taskId, item.contactSheetFingerprint);
    ElMessage.success("当前版本已确认，操作已记录");
    await refresh();
  } catch (cause) {
    error.value = errorMessage(cause);
    acknowledged.value = false;
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  void refresh();
});
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <span class="eyebrow">CONTENT / REVIEW</span>
        <h1>审片工作台</h1>
        <p class="page-description">看清内容，核对版本。你的确认，只对眼前这版成片生效。</p>
      </div>
      <div class="page-actions">
        <ElButton :icon="Refresh" :loading="loading" @click="refresh">刷新队列</ElButton>
      </div>
    </div>
    <ElAlert
      v-if="error"
      class="page-alert"
      type="error"
      :title="error"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="notice"
      class="page-alert"
      type="info"
      :title="notice"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="gates && !gates.open"
      class="page-alert"
      type="warning"
      :closable="false"
      show-icon
      title="当前仅可审阅，发布闸门尚未全部开放"
    >
      <p v-for="reason in gates.blockers" :key="reason" class="gate-reason">{{ reason }}</p>
    </ElAlert>
    <ElSkeleton v-if="loading" :rows="8" animated />
    <div v-else-if="items.length" class="review-layout">
      <div class="panel review-queue">
        <div class="panel-header">
          <h3>待审队列</h3>
          <ElTag effect="plain">{{ items.length }}</ElTag>
        </div>
        <button
          v-for="item in items"
          :key="item.taskId"
          type="button"
          class="queue-item"
          :class="{ selected: selectedId === item.taskId }"
          :aria-pressed="selectedId === item.taskId"
          @click="selectTask(item.taskId)"
        >
          <span class="queue-icon"
            ><ElIcon><VideoCamera /></ElIcon></span
          ><span
            ><strong>{{ item.title || "未命名任务" }}</strong
            ><small>{{
              item.renderVersion ? `成片版本 v${item.renderVersion}` : "尚无渲染版本"
            }}</small></span
          >
        </button>
      </div>
      <div v-if="selected" class="panel review-detail">
        <div class="panel-header">
          <div>
            <h2>{{ selected.title || "未命名任务" }}</h2>
            <p class="cell-subtitle">
              人工审核 · {{ selected.renderVersion ? `v${selected.renderVersion}` : "暂无版本" }}
            </p>
          </div>
          <ElTag type="warning" effect="plain">待人工确认</ElTag>
        </div>
        <div class="review-media">
          <div class="video-stage">
            <video
              v-if="selected.mp4Path && selected.renderVersion"
              :key="`${selected.taskId}:${selected.renderVersion}:video`"
              controls
              preload="metadata"
              :src="artifactUrl(selected.taskId, 'mp4', selected.renderVersion)"
              aria-label="当前版本成片预览"
              @error="mediaError = '视频无法加载，请检查产物是否存在或刷新队列'"
            />
            <div v-else class="no-video">
              <ElIcon :size="34"><VideoCamera /></ElIcon>
              <p>当前版本没有视频文件</p>
            </div>
          </div>
          <div class="sheet-panel">
            <h3>联络表</h3>
            <p class="form-hint">逐帧检查标题、正文与 AI 内容声明。</p>
            <img
              v-if="selected.contactSheetPath && selected.renderVersion"
              :key="sheetKey"
              ref="sheetElement"
              :data-artifact="sheetKey"
              :src="artifactUrl(selected.taskId, 'contact-sheet', selected.renderVersion)"
              alt="当前成片版本的逐帧联络表"
              @load="sheetLoad"
              @error="sheetError"
            />
            <ElEmpty v-else :image-size="65" description="当前版本没有联络表" />
          </div>
        </div>
        <div class="review-confirm">
          <ElAlert
            v-if="mediaError"
            class="page-alert"
            type="error"
            :closable="false"
            :title="mediaError"
          />
          <div class="fingerprint">
            <span>版本指纹</span><code>{{ selected.contactSheetFingerprint || "尚未生成" }}</code>
          </div>
          <ElCheckbox v-model="acknowledged" :disabled="!sheetLoaded || saving"
            >我已检查当前版本的内容、画面与 AI 声明</ElCheckbox
          >
          <div class="confirm-actions">
            <ElButton
              type="primary"
              :icon="CircleCheck"
              :disabled="!!blocker || loading || !!error"
              :loading="saving"
              @click="confirm"
              >确认当前版本</ElButton
            >
            <p class="form-hint">{{ blocker || "将记录确认人、确认时间与当前版本指纹。" }}</p>
          </div>
        </div>
      </div>
    </div>
    <div v-else class="panel">
      <ElEmpty
        :description="
          error
            ? '队列暂时不可用，请点击刷新重试'
            : '暂时没有待审成片。任务完成渲染后，会出现在这里。'
        "
      />
    </div>
  </section>
</template>

<style scoped>
.review-layout {
  display: grid;
  grid-template-columns: 235px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
.review-layout > .panel {
  margin-top: 0;
}
.queue-item {
  width: 100%;
  display: flex;
  align-items: center;
  text-align: left;
  gap: 10px;
  border: none;
  border-bottom: 1px solid var(--heron-line);
  background: var(--heron-surface);
  cursor: pointer;
  color: var(--heron-ink);
  padding: 17px 15px;
}
.queue-item.selected {
  background: var(--el-color-primary-light-9);
  box-shadow: inset 3px 0 var(--heron-accent);
}
.queue-icon {
  padding: 9px;
  border-radius: 5px;
  color: var(--heron-accent);
  background: var(--el-color-primary-light-9);
}
.queue-item strong {
  font-size: 12px;
  line-height: 1.7;
  display: block;
  font-weight: 500;
}
.queue-item small {
  font-size: 10px;
  display: block;
  margin-top: 5px;
  color: var(--heron-muted);
}
.review-media {
  padding: 22px;
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(150px, 1fr);
  gap: 22px;
}
.video-stage {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 330px;
  background: #10251f;
  border-radius: 8px;
  overflow: hidden;
}
.video-stage video {
  width: 100%;
  max-height: 510px;
}
.no-video {
  text-align: center;
  color: #8aa897;
  font-size: 12px;
}
.no-video p {
  margin-top: 16px;
}
.sheet-panel img {
  width: 100%;
  max-height: 510px;
  object-fit: contain;
  border: 1px solid var(--heron-line);
  margin-top: 18px;
}
.review-confirm {
  padding: 22px;
  border-top: 1px solid var(--heron-line);
}
.fingerprint {
  display: flex;
  gap: 18px;
  align-items: baseline;
  font-size: 11px;
  margin-bottom: 14px;
  color: var(--heron-muted);
}
.fingerprint code {
  overflow-wrap: anywhere;
  font-size: 10px;
}
.confirm-actions {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-top: 18px;
}
.gate-reason {
  font-size: 11px;
  margin: 3px 0;
}
@media (max-width: 1000px) {
  .review-layout {
    grid-template-columns: 1fr;
  }
  .review-queue {
    display: flex;
    overflow-x: auto;
  }
  .review-queue .panel-header {
    flex-shrink: 0;
  }
  .queue-item {
    min-width: 200px;
  }
}
@media (max-width: 600px) {
  .review-media {
    grid-template-columns: 1fr;
  }
  .confirm-actions {
    align-items: flex-start;
    flex-direction: column;
  }
  .review-confirm .el-checkbox {
    white-space: normal;
    height: auto;
  }
}
</style>
