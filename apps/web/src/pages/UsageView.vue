<script setup lang="ts">
import type { UsageSummary, PlatformUsageSummary } from "@heron/contracts";
import { Refresh } from "@element-plus/icons-vue";
import { ElAlert, ElButton, ElTable, ElTableColumn, ElTag } from "element-plus";
import { onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { request, errorMessage } from "../api/http";
const platform = useRoute().meta.platform === true;
const rows = ref<(UsageSummary | PlatformUsageSummary)[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
async function load() {
  loading.value = true;
  error.value = null;
  try {
    rows.value = await request<(UsageSummary | PlatformUsageSummary)[]>(
      platform ? "/platform/usage" : "/manage/usage",
    );
  } catch (cause) {
    rows.value = [];
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
        <span class="eyebrow">{{ platform ? "Platform" : "Workspace" }} / resource usage</span>
        <h1>{{ platform ? "全局用量" : "用量统计" }}</h1>
        <p class="page-description">按实际用量账本汇总，保留各资源的原始计量单位。</p>
      </div>
      <div class="page-actions">
        <ElButton :icon="Refresh" :loading="loading" @click="load">刷新统计</ElButton>
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
    <div class="panel" :aria-busy="loading">
      <div class="panel-header">
        <h2>累计资源消耗</h2>
        <ElTag type="info">全时间范围</ElTag>
      </div>
      <ElTable
        :data="rows"
        :empty-text="loading ? '正在读取用量…' : '尚无用量记录，实际资源消耗入账后会在这里显示。'"
        ><ElTableColumn
          v-if="platform"
          prop="tenantName"
          label="工作空间"
          min-width="200" /><ElTableColumn
          prop="kind"
          label="资源类型"
          min-width="180" /><ElTableColumn
          prop="quantity"
          label="累计用量"
          min-width="170"
          align="right"
          ><template #default="{ row }"
            ><span class="mono">{{ row.quantity }}</span></template
          ></ElTableColumn
        ><ElTableColumn prop="unit" label="单位" min-width="120"
      /></ElTable>
      <div class="panel-body">
        <p class="form-hint">
          不同单位不合并计算。此页不是计费账单，不显示未经定价的金额、额度或预计费用。
        </p>
      </div>
    </div>
  </section>
</template>
