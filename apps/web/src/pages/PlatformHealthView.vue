<script setup lang="ts">
import { Refresh } from "@element-plus/icons-vue";
import {
  ElAlert,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import { computed, onMounted, ref } from "vue";
import { errorMessage } from "../api/http";
import { loadHealth, type HealthReport } from "../api/health";
import { dateTime } from "../format";
const health = ref<HealthReport | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);
const probes = computed(() =>
  health.value
    ? [
        { name: "数据库连接", ...health.value.db },
        { name: "前端构建产物", ...health.value.webDist },
      ]
    : [],
);
async function load() {
  loading.value = true;
  error.value = null;
  try {
    health.value = await loadHealth();
  } catch (cause) {
    health.value = null;
    error.value = errorMessage(cause);
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
        <span class="eyebrow">Platform / service health</span>
        <h1>服务健康</h1>
        <p class="page-description">
          运行探针与生产许可分别展示。服务在线，不代表已经满足发布条件。
        </p>
      </div>
      <div class="page-actions">
        <ElButton :icon="Refresh" :loading="loading" @click="load">重新检查</ElButton>
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
        <h2>运行探针</h2>
        <ElTag :type="health?.ok ? 'success' : 'warning'">{{
          health ? (health.ok ? "运行正常" : "需要检查") : "尚未取得状态"
        }}</ElTag>
      </div>
      <ElTable :data="probes" :empty-text="loading ? '正在检查…' : '未取得探针结果，请重新检查。'"
        ><ElTableColumn prop="name" label="检查项" width="200" /><ElTableColumn
          label="状态"
          width="130"
          ><template #default="{ row }"
            ><ElTag :type="row.ok ? 'success' : 'danger'">{{
              row.ok ? "正常" : "异常"
            }}</ElTag></template
          ></ElTableColumn
        ><ElTableColumn prop="detail" label="详情" min-width="260"
      /></ElTable>
      <div class="panel-body">
        <ElDescriptions :column="2"
          ><ElDescriptionsItem label="检查时间">{{
            dateTime(health?.at ?? null)
          }}</ElDescriptionsItem
          ><ElDescriptionsItem label="Node.js">{{
            health?.node ?? "—"
          }}</ElDescriptionsItem></ElDescriptions
        >
      </div>
    </div>
    <div class="panel">
      <div class="panel-header">
        <h2>发布门禁</h2>
        <ElTag :type="health?.publish.open ? 'success' : 'warning'">{{
          health?.publish.open ? "满足条件" : "未开放"
        }}</ElTag>
      </div>
      <div class="panel-body">
        <ul v-if="health?.publish.blockers.length" class="health-blockers">
          <li v-for="blocker in health.publish.blockers" :key="blocker">{{ blocker }}</li>
        </ul>
        <p v-else class="form-hint">
          {{
            health
              ? "当前没有报告阻断项，实际发布仍须验证授权、合规文件与人工确认。"
              : "门禁状态未知，不能视为允许发布。"
          }}
        </p>
        <p class="form-hint">
          此页不监控外部发布平台、模型服务或后台任务心跳，也不会把未检测的能力标记为健康。
        </p>
      </div>
    </div>
  </section>
</template>
<style scoped>
.health-blockers {
  padding-left: 20px;
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 2;
}
</style>
