<script setup lang="ts">
import type { QuotaStatus, UsageSummary, PlatformUsageSummary } from "@heron/contracts";
import { PLAN_LABELS, excessMessage, exceeds } from "@heron/domain";
import { RefreshCw } from "lucide-vue-next";
import { ElAlert, ElButton, ElProgress, ElTable, ElTableColumn, ElTag } from "element-plus";
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { request, errorMessage } from "../../api/http";
import UsageBars from "../../components/UsageBars.vue";
const platform = useRoute().meta.platform === true;
const rows = ref<(UsageSummary | PlatformUsageSummary)[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
/** 平台台看的是各租户的累计用量，没有「我这一家的额度」可言。 */
const quota = ref<QuotaStatus | null>(null);
const quotaExcess = computed(() =>
  quota.value ? exceeds(quota.value.plan, quota.value.usage) : null,
);
/**
 * 上限和用量一起显示：只给用量的话，读的人得自己去别处找「100 条算多还是算少」。
 * 比例只是进度条的视觉，判定一律交给 exceeds —— 那条边界（>=）只写一次。
 */
const meters = computed(() => {
  const q = quota.value;
  if (!q) return [];
  return [
    { kind: "seats", label: "成员席位", used: q.usage.seats, limit: q.limits.seats },
    { kind: "renders", label: "本月渲染", used: q.usage.renders, limit: q.limits.rendersPerMonth },
    {
      kind: "publishes",
      label: "本月发布",
      used: q.usage.publishes,
      limit: q.limits.publishesPerMonth,
    },
  ].map((meter) => {
    const ratio = meter.used / meter.limit;
    return {
      ...meter,
      percentage: Math.min(100, Math.round(ratio * 100)),
      status: ratio >= 1 ? ("exception" as const) : ratio >= 0.8 ? ("warning" as const) : undefined,
    };
  });
});
/** 不同单位不合并：每种单位各画一张，条内按用量降序。 */
const groups = computed(() => {
  const byUnit = new Map<string, { label: string; value: number }[]>();
  for (const row of rows.value) {
    const label = "tenantName" in row ? `${row.tenantName} · ${row.kind}` : row.kind;
    const bars = byUnit.get(row.unit) ?? [];
    bars.push({ label, value: Number(row.quantity) });
    byUnit.set(row.unit, bars);
  }
  return [...byUnit].map(([unit, bars]) => ({
    unit,
    bars: bars.sort((a, b) => b.value - a.value),
    summary: `${unit} 累计用量：${bars.map((bar) => `${bar.label} ${bar.value}`).join("，")}`,
  }));
});
async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [usage, plan] = await Promise.all([
      request<(UsageSummary | PlatformUsageSummary)[]>(
        platform ? "/platform/usage" : "/manage/usage",
      ),
      platform ? Promise.resolve(null) : request<QuotaStatus>("/manage/quota"),
    ]);
    rows.value = usage;
    quota.value = plan;
  } catch (cause) {
    rows.value = [];
    quota.value = null;
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
        <ElButton :icon="RefreshCw" :loading="loading" @click="load">刷新统计</ElButton>
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
    <div v-if="quota" class="panel">
      <div class="panel-header">
        <h2>本月额度</h2>
        <ElTag :type="quotaExcess ? 'danger' : 'info'">{{ PLAN_LABELS[quota.plan] }}档</ElTag>
      </div>
      <div class="panel-body">
        <div class="quota-gauges">
          <section v-for="meter in meters" :key="meter.kind" class="quota-meter">
            <div class="quota-meter-head">
              <span>{{ meter.label }}</span>
              <span class="mono">{{ meter.used }}/{{ meter.limit }}</span>
            </div>
            <ElProgress
              :percentage="meter.percentage"
              :status="meter.status"
              :stroke-width="8"
              :show-text="false"
            />
          </section>
        </div>
        <ElAlert
          v-if="quotaExcess"
          :title="excessMessage(quota.plan, quotaExcess)"
          type="warning"
          show-icon
          :closable="false"
          class="quota-foot"
        />
        <p v-else class="form-hint">
          渲染与发布按 UTC 自然月计，每月 1 日归零；成员席位是当前值，不随月份清零。
        </p>
      </div>
    </div>
    <div v-if="groups.length" class="panel">
      <div class="panel-header">
        <h2>用量分布</h2>
        <ElTag type="info">按单位分组</ElTag>
      </div>
      <div class="panel-body usage-groups">
        <section v-for="group in groups" :key="group.unit" class="usage-group">
          <h3>单位：{{ group.unit }}</h3>
          <UsageBars :bars="group.bars" :summary="group.summary" />
        </section>
      </div>
    </div>
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
          不同单位不合并计算。此页不是计费账单，不显示金额或预计费用；上方额度是上限提示，不是扣费。
        </p>
      </div>
    </div>
  </section>
</template>
