<script setup lang="ts">
import { ElButton, ElResult } from "element-plus";
import { computed } from "vue";
import { RouterLink } from "vue-router";

import { useAuthStore } from "../stores/auth";

const auth = useAuthStore();
const home = computed(() => auth.menus[0]?.path ?? "/login");
</script>
<template>
  <ElResult
    icon="info"
    title="找不到这个页面"
    sub-title="页面地址可能已变更，或者你的身份没有这个页面。"
    ><template #extra
      ><RouterLink :to="home"><ElButton type="primary">返回工作台</ElButton></RouterLink></template
    ></ElResult
  >
</template>
