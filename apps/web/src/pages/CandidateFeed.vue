<script setup lang="ts">
import { ElButton, ElInput, ElMessage, ElTag } from "element-plus";
import { ref } from "vue";

import type { Stage, Task } from "@heron/contracts";

import { tasksApi } from "../api";
import { useTasksStore } from "../stores/tasks";

const store = useTasksStore();
const title = ref("台风“巴威”路径更新");
const busy = ref(false);

function stageType(stage: Stage): "success" | "danger" | "info" | "warning" {
  if (stage === "published") return "success";
  if (stage === "failed") return "danger";
  if (stage === "candidate") return "info";
  return "warning";
}

async function createTask(): Promise<void> {
  busy.value = true;
  try {
    await tasksApi.create({ capability: "p0-fake", title: title.value });
    await store.refresh();
    ElMessage.success("候选已建立，未花渲染配额");
  } catch (cause) {
    ElMessage.error(String(cause));
  } finally {
    busy.value = false;
  }
}

async function claim(task: Task): Promise<void> {
  busy.value = true;
  try {
    await tasksApi.claim(task.id);
    await store.refresh();
  } catch (cause) {
    ElMessage.error(String(cause));
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="max-w-4xl">
    <h2 class="mb-3 text-base font-semibold">候选流</h2>
    <p class="mb-4 text-sm text-neutral-600">此页不花钱：无稿、无渲染。认领才进生产。</p>

    <div class="mb-5 flex gap-2">
      <ElInput v-model="title" placeholder="候选标题" class="w-96" />
      <ElButton type="primary" :loading="busy" @click="createTask">建一条假候选（P0）</ElButton>
    </div>

    <ul class="space-y-2">
      <li
        v-for="task in store.tasks"
        :key="task.id"
        class="flex items-center gap-3 rounded border border-neutral-200 bg-white px-3 py-2"
      >
        <ElTag :type="stageType(task.stage)">{{ task.stage }}</ElTag>
        <span class="truncate">{{ task.capability }} · {{ task.id.slice(0, 8) }}</span>
        <ElButton
          v-if="task.stage === 'candidate'"
          size="small"
          :disabled="busy"
          @click="claim(task)"
        >
          认领
        </ElButton>
      </li>
    </ul>
    <p v-if="store.tasks.length === 0" class="text-sm text-neutral-500">暂无候选。</p>
  </section>
</template>
