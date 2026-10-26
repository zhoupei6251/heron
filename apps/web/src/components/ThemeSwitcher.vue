<script setup lang="ts">
import { Monitor, Moon, Sunny } from "@element-plus/icons-vue";
import { ElIcon } from "element-plus";
import { useAppearanceStore } from "../stores/appearance";

const appearance = useAppearanceStore();
</script>

<template>
  <div class="theme-switcher">
    <ElIcon aria-hidden="true"
      ><component
        :is="appearance.mode === 'auto' ? Monitor : appearance.mode === 'dark' ? Moon : Sunny"
    /></ElIcon>
    <select v-model="appearance.mode" aria-label="配色模式">
      <option value="auto">跟随系统</option>
      <option value="light">明亮模式</option>
      <option value="dark">深色模式</option>
    </select>
    <span v-if="!appearance.persistent" role="status" class="theme-storage-warning"
      >仅本次生效</span
    >
  </div>
</template>

<style scoped>
.theme-switcher {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--heron-muted);
  position: relative;
}
.theme-switcher select {
  cursor: pointer;
  background: var(--heron-surface);
  color: var(--heron-ink);
  border: 1px solid var(--heron-line);
  border-radius: 8px;
  min-height: 38px;
  padding: 0 5px;
  font-size: 12px;
}
.theme-storage-warning {
  position: absolute;
  top: 100%;
  right: 0;
  white-space: nowrap;
  font-size: 10px;
  color: var(--el-color-warning);
}
@media (max-width: 760px) {
  .theme-switcher > .el-icon {
    display: none;
  }
  .theme-switcher select {
    min-height: 40px;
    max-width: 90px;
  }
}
</style>
