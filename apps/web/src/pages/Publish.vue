<script setup lang="ts">
import { ElButton, ElTag } from "element-plus";
import { onMounted } from "vue";

import { useGatesStore } from "../stores/gates";
import { useTasksStore } from "../stores/tasks";

const store = useTasksStore();
const gates = useGatesStore();

onMounted(() => {
  void store.refresh();
  void gates.refresh();
});
</script>

<template>
  <section class="max-w-4xl">
    <h2 class="mb-3 text-base font-semibold">发布台</h2>
    <p class="mb-4 text-sm text-neutral-600">
      重试前必须先查外部真实状态：回执丢失不等于没发出去，直接重试就是发两遍。
    </p>

    <ul class="space-y-2">
      <li
        v-for="task in store.tasks.filter((t) =>
          ['approved', 'publishing', 'published', 'failed'].includes(t.stage),
        )"
        :key="task.id"
        class="rounded border border-neutral-200 bg-white px-3 py-2"
      >
        <div class="flex items-center gap-3">
          <ElTag :type="task.stage === 'published' ? 'success' : 'warning'">{{ task.stage }}</ElTag>
          <span class="text-sm">{{ task.id.slice(0, 8) }}</span>
          <ElButton
            size="small"
            disabled
            :title="gates.state?.blockers.join(' / ') ?? '门禁位未知'"
          >
            发布
          </ElButton>
        </div>
        <pre
          v-if="task.errorReason"
          class="mt-2 whitespace-pre-wrap rounded bg-red-50 p-2 text-xs text-red-800"
          >{{ task.errorReason }}</pre>
      </li>
    </ul>
  </section>
</template>
