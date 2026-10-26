<script setup lang="ts">
import type { GateState, TaskSummary } from "@heron/contracts";
import {
  ArrowRight,
  Refresh,
  Tickets,
  VideoCamera,
  View,
  Promotion,
  Warning,
  CircleCheck,
} from "@element-plus/icons-vue";
import { ElAlert, ElButton, ElEmpty, ElIcon, ElTable, ElTableColumn, ElTag } from "element-plus";
import { onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { request, errorMessage } from "../api/http";
import StageTag from "../components/StageTag.vue";
import { dateTime } from "../format";
import { useAuthStore } from "../stores/auth";
const auth = useAuthStore();
const overview = ref<{
  candidates: number;
  tasks: number;
  review: number;
  published: number;
  failed: number;
  members: number;
  recentTasks: TaskSummary[];
} | null>(null);
const gates = ref<GateState | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);
async function load() {
  loading.value = true;
  error.value = null;
  try {
    [overview.value, gates.value] = await Promise.all([
      request<NonNullable<typeof overview.value>>("/manage/overview"),
      request<GateState>("/gates"),
    ]);
  } catch (cause) {
    error.value = errorMessage(cause);
    overview.value = null;
    gates.value = null;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">The editorial workspace</span>
        <h1>把值得关注的事，做成好内容。</h1>
        <p class="page-description">
          {{ auth.user?.tenantName }} · 从选题到成片，每一次确认都有迹可循。
        </p>
      </div>
      <div class="page-actions">
        <ElButton :icon="Refresh" :loading="loading" @click="load">刷新概览</ElButton>
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
    <div class="editorial-desk">
      <div>
        <span class="eyebrow">Human in the loop</span>
        <h2>
          {{
            overview
              ? `有 ${overview.review} 条内容，等待你的目光。`
              : "选题进入生产，成片交由人来确认。"
          }}
        </h2>
        <p>核对事实、画面与声明，再确认这一版成片。发布前的最后一步，始终留给人。</p>
        <RouterLink to="/review" class="desk-link"
          >进入审片工作台 <ElIcon><ArrowRight /></ElIcon
        ></RouterLink>
      </div>
      <nav class="desk-flow" aria-label="生产流程快捷入口">
        <RouterLink to="/candidates"
          ><span class="flow-index">01 / DISCOVER</span><ElIcon><Tickets /></ElIcon
          ><strong>发现选题</strong><span>整理值得关注的线索</span></RouterLink
        >
        <RouterLink to="/production"
          ><span class="flow-index">02 / PRODUCE</span><ElIcon><VideoCamera /></ElIcon
          ><strong>内容生产</strong><span>跟进任务与失败原因</span></RouterLink
        >
        <RouterLink to="/review" class="flow-review"
          ><span class="flow-index">03 / REVIEW</span><ElIcon><View /></ElIcon
          ><strong>人工审片</strong><span>确认事实、画面与版本</span></RouterLink
        >
        <RouterLink to="/publish"
          ><span class="flow-index">04 / DELIVER</span><ElIcon><Promotion /></ElIcon
          ><strong>发布追踪</strong><span>检查回执与发布状态</span></RouterLink
        >
      </nav>
    </div>
    <div class="stats-grid" :aria-busy="loading">
      <div class="stat-card">
        <div class="stat-label">
          <span>候选选题</span><ElIcon><Tickets /></ElIcon>
        </div>
        <div class="stat-value">{{ overview?.candidates ?? "—" }}</div>
        <div class="stat-note">工作空间候选库</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">
          <span>生产任务</span><ElIcon><VideoCamera /></ElIcon>
        </div>
        <div class="stat-value">{{ overview?.tasks ?? "—" }}</div>
        <div class="stat-note">全部生产阶段</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">
          <span>任务已完成发布阶段</span><ElIcon><CircleCheck /></ElIcon>
        </div>
        <div class="stat-value">{{ overview?.published ?? "—" }}</div>
        <div class="stat-note">含演示任务，不等于平台真实发布</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">
          <span>需要处理的失败</span><ElIcon><Warning /></ElIcon>
        </div>
        <div class="stat-value">{{ overview?.failed ?? "—" }}</div>
        <div class="stat-note">在生产任务中查看完整原因</div>
      </div>
    </div>
    <div class="split-grid">
      <div class="panel">
        <div class="panel-header">
          <h2>最近生产</h2>
          <RouterLink to="/production"
            ><ElButton link type="primary"
              >全部任务 <ElIcon><ArrowRight /></ElIcon></ElButton
          ></RouterLink>
        </div>
        <ElTable
          :data="overview?.recentTasks ?? []"
          :empty-text="loading ? '正在读取…' : '暂无生产任务，从候选管理开始。'"
          ><ElTableColumn label="内容" min-width="200"
            ><template #default="{ row }"
              ><div class="cell-title">{{ row.title ?? "未命名任务" }}</div>
              <div class="cell-subtitle">
                {{ row.capability === "p0-fake" ? "演示流程" : row.capability }}
              </div></template
            ></ElTableColumn
          ><ElTableColumn label="状态" width="120"
            ><template #default="{ row }"><StageTag :stage="row.stage" /></template></ElTableColumn
          ><ElTableColumn label="更新" min-width="160"
            ><template #default="{ row }">{{ dateTime(row.updatedAt) }}</template></ElTableColumn
          ></ElTable
        >
      </div>
      <div class="panel">
        <div class="panel-header">
          <h2>生产门禁</h2>
          <div class="gate-summary" aria-label="生产门禁状态" aria-live="polite">
            <span v-if="gates" class="mono"
              >{{ gates.locations.filter((location) => location.satisfied).length }} /
              {{ gates.locations.length }} 项满足</span
            >
            <ElTag :type="!gates ? 'info' : gates.open ? 'success' : 'warning'">{{
              !gates ? "尚未读取" : gates.open ? "满足门禁" : "发布受限"
            }}</ElTag>
          </div>
        </div>
        <div class="panel-body">
          <template v-if="gates"
            ><ul class="gate-checklist">
              <li v-for="location in gates.locations" :key="location.gate">
                <ElIcon
                  :class="['gate-indicator', { passed: location.satisfied }]"
                  :aria-label="location.satisfied ? '已满足' : '未满足'"
                  ><CircleCheck v-if="location.satisfied" /><Warning v-else
                /></ElIcon>
                <div>
                  <strong>{{ location.gate }}</strong>
                  <p>{{ location.detail }}</p>
                </div>
              </li>
            </ul>
            <p v-if="gates.blockers.length" class="form-hint">
              {{ gates.blockers.join("；") }}
            </p></template
          ><ElEmpty v-else description="门禁状态尚未读取" :image-size="60" />
        </div>
      </div>
    </div>
  </section>
</template>
<style scoped>
.editorial-desk {
  position: relative;
  overflow: hidden;
  background: radial-gradient(ellipse at 90% 0%, #3a866859, transparent 65%), var(--heron-hero);
  color: var(--heron-hero-ink);
  border: 1px solid #42745d;
  border-radius: 16px;
  padding: 30px;
  margin-bottom: 24px;
}
.editorial-desk::before {
  content: "";
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(#ffffff04 1px, transparent 1px),
    linear-gradient(90deg, #ffffff04 1px, transparent 1px);
  background-size: 32px 32px;
  pointer-events: none;
}
.editorial-desk > * {
  position: relative;
}
.editorial-desk .eyebrow {
  color: #aed2be;
}
.editorial-desk h2 {
  font-family: var(--heron-display);
  font-size: clamp(22px, 2vw, 30px);
  font-weight: 550;
  margin-bottom: 10px;
}
.editorial-desk p {
  font-size: 12px;
  line-height: 1.9;
  color: var(--heron-hero-muted);
  max-width: 620px;
}
.desk-link {
  display: inline-flex;
  align-items: center;
  gap: 16px;
  color: #d5f6e0;
  font-weight: 600;
  font-size: 12px;
  min-height: 40px;
}
.desk-link:hover {
  text-decoration: underline;
  text-underline-offset: 5px;
}
.desk-flow {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 25px;
}
.desk-flow > a {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 9px 5px;
  min-width: 0;
  padding: 17px;
  border: 1px solid #67988166;
  border-radius: 10px;
  background: #0b2d2455;
  color: var(--heron-hero-muted);
  transition:
    background 160ms,
    transform 160ms;
}
.desk-flow > a:hover {
  background: #ffffff12;
  transform: translateY(-3px);
}
.desk-flow .flow-review {
  border-color: #9ad8b5;
  background: #ffffff0c;
}
.flow-index {
  font:
    10px/1.6 Consolas,
    monospace;
  letter-spacing: 1px;
}
.desk-flow .el-icon {
  font-size: 20px;
  color: #b5e7c7;
}
.desk-flow strong {
  grid-column: 1 / -1;
  font-size: 14px;
  color: var(--heron-hero-ink);
}
.desk-flow a > span:last-child {
  grid-column: 1 / -1;
  font-size: 11px;
  line-height: 1.7;
}
.gate-summary {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  color: var(--heron-muted);
}
.gate-checklist {
  list-style: none;
  padding: 0;
  margin: 0;
}
.gate-checklist li {
  display: flex;
  align-items: start;
  gap: 13px;
  padding-bottom: 20px;
}
.gate-checklist li:last-child {
  padding-bottom: 0;
}
.gate-checklist strong {
  font-size: 12px;
  font-weight: 550;
}
.gate-checklist p {
  font-size: 11px;
  color: var(--heron-muted);
  line-height: 1.7;
  margin: 4px 0 0;
}
.gate-indicator {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
  border-radius: 8px;
}
.gate-indicator.passed {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
@media (max-width: 760px) {
  .editorial-desk {
    padding: 22px 18px;
  }
  .desk-flow {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .desk-flow > a {
    padding: 14px 10px;
  }
  .flow-index {
    font-size: 9px;
    letter-spacing: 0;
  }
}
</style>
