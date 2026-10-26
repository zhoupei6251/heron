<script setup lang="ts">
import type { Stage } from "@heron/contracts";
import { ElTag } from "element-plus";
defineProps<{ stage: Stage }>();
const labels: Record<Stage, string> = {
  candidate: "待认领",
  claimed: "已认领",
  scripting: "写稿中",
  factcheck: "事实核查",
  rendering: "渲染中",
  review: "待审片",
  approved: "已确认",
  publishing: "发布中",
  published: "已发布",
  failed: "失败",
};
</script>
<template>
  <ElTag
    :type="
      stage === 'failed'
        ? 'danger'
        : stage === 'published'
          ? 'success'
          : stage === 'review'
            ? 'warning'
            : 'info'
    "
    effect="light"
    >{{ labels[stage] }}</ElTag
  >
</template>
