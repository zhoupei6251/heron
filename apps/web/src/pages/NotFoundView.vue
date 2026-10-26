<script setup lang="ts">
import { ElButton, ElResult } from "element-plus";
import { RouterLink, useRoute } from "vue-router";
const route = useRoute();
</script>
<template>
  <ElResult icon="info" title="找不到这个页面" sub-title="页面地址可能已变更，请返回工作台。"
    ><template #extra
      ><RouterLink :to="route.meta.platform ? '/platform' : '/overview'"
        ><ElButton type="primary">返回工作台</ElButton></RouterLink
      ></template
    ></ElResult
  >
</template>
