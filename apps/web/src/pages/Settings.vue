<script setup lang="ts">
import { ElButton, ElInputNumber, ElMessage, ElTable, ElTableColumn } from "element-plus";
import { onMounted, ref } from "vue";

import { settingsApi } from "../api";
import { useGatesStore } from "../stores/gates";

const gates = useGatesStore();
const days = ref(30);
const busy = ref(false);

async function loadRetention(): Promise<void> {
  try {
    days.value = (await settingsApi.get()).retentionDays;
  } catch (cause) {
    ElMessage.error(`读取保留策略失败：${String(cause)}`);
  }
}

async function saveRetention(): Promise<void> {
  busy.value = true;
  try {
    days.value = (await settingsApi.setRetention(days.value)).retentionDays;
    ElMessage.success("保留策略已更新");
  } catch (cause) {
    ElMessage.error(String(cause));
  } finally {
    busy.value = false;
  }
}

onMounted(() => {
  void gates.refresh();
  void loadRetention();
});
</script>

<template>
  <section class="max-w-3xl">
    <h2 class="mb-3 text-base font-semibold">设置</h2>

    <div class="mb-6 flex items-center gap-3">
      <span class="text-sm">成片保留天数</span>
      <ElInputNumber v-model="days" :min="1" :max="3650" />
      <ElButton :loading="busy" @click="saveRetention">保存</ElButton>
      <span class="text-xs text-neutral-500">不配就是三个月后先炸磁盘。</span>
    </div>

    <h3 class="mb-2 text-sm font-semibold">门禁位</h3>
    <ElTable :data="gates.state?.locations ?? []" size="small">
      <ElTableColumn prop="gate" label="闸" width="140" />
      <ElTableColumn prop="satisfied" label="已启用" width="100">
        <template #default="{ row }">{{ row.satisfied ? "是" : "否" }}</template>
      </ElTableColumn>
      <ElTableColumn prop="detail" label="位置说明" />
    </ElTable>
  </section>
</template>
