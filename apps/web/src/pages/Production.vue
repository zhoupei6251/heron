<script setup lang="ts">
import { ElTag } from "element-plus";

import { useTasksStore } from "../stores/tasks";

const store = useTasksStore();
</script>

<template>
  <section class="max-w-4xl">
    <h2 class="mb-3 text-base font-semibold">生产台</h2>
    <p class="mb-4 text-sm text-neutral-600">
      进度走 SSE。稿件与分镜编辑在 P1 落地；失败原因必须展开全文，不只显示“失败”。
    </p>

    <ul class="space-y-3">
      <li
        v-for="task in store.tasks.filter((t) => t.stage !== 'candidate')"
        :key="task.id"
        class="rounded border border-neutral-200 bg-white px-3 py-2"
      >
        <div class="flex items-center gap-3">
          <ElTag>{{ task.stage }}</ElTag>
          <span class="text-sm">{{ task.id.slice(0, 8) }}</span>
          <span class="ml-auto text-xs text-neutral-500">attempts {{ task.attempts }}</span>
        </div>
        <pre
          v-if="task.errorReason"
          class="mt-2 whitespace-pre-wrap rounded bg-red-50 p-2 text-xs text-red-800"
          >{{ task.errorReason }}</pre>
      </li>
    </ul>
  </section>
</template>
