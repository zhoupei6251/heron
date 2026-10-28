<script setup lang="ts">
import { Check, Monitor, Moon, Sun } from "lucide-vue-next";
import { computed } from "vue";
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon } from "element-plus";
import { useAppearanceStore } from "../stores/appearance";

const MODES = [
  { value: "auto", label: "跟随系统", icon: Monitor },
  { value: "light", label: "明亮模式", icon: Sun },
  { value: "dark", label: "深色模式", icon: Moon },
] as const;

const appearance = useAppearanceStore();
const current = computed(() => MODES.find((mode) => mode.value === appearance.mode) ?? MODES[0]);

function setMode(mode: string | number | boolean | null | undefined) {
  if (mode === "auto" || mode === "light" || mode === "dark") appearance.mode = mode;
}
</script>

<template>
  <div class="theme-switcher">
    <ElDropdown trigger="click" @command="setMode">
      <button
        type="button"
        class="theme-trigger"
        :aria-label="`配色模式：${current.label}`"
        :title="`配色模式：${current.label}`"
      >
        <ElIcon><component :is="current.icon" /></ElIcon>
        <span>{{ current.label }}</span>
      </button>
      <template #dropdown>
        <ElDropdownMenu>
          <ElDropdownItem
            v-for="mode in MODES"
            :key="mode.value"
            :command="mode.value"
            :class="{ 'is-current': mode.value === appearance.mode }"
          >
            <ElIcon class="mode-icon"><component :is="mode.icon" /></ElIcon>
            <span>{{ mode.label }}</span>
            <ElIcon v-if="mode.value === appearance.mode" class="mode-check"><Check /></ElIcon>
          </ElDropdownItem>
        </ElDropdownMenu>
      </template>
    </ElDropdown>
    <span v-if="!appearance.persistent" role="status" class="theme-storage-warning"
      >仅本次生效</span
    >
  </div>
</template>

<style scoped>
.theme-switcher {
  display: inline-flex;
  align-items: center;
  color: var(--heron-muted);
  position: relative;
}
.theme-trigger {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 40px;
  padding: 0 12px;
  border: 1px solid var(--heron-line);
  border-radius: 9px;
  background: var(--heron-surface);
  color: var(--heron-ink);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.theme-trigger:hover {
  border-color: var(--heron-accent);
  background: var(--heron-surface-soft);
}
.theme-trigger .el-icon {
  color: var(--heron-accent);
}
.mode-icon {
  margin-right: 8px;
  color: var(--heron-muted);
}
.mode-check {
  margin-left: auto;
  color: var(--heron-accent);
}
.el-dropdown-menu__item.is-current {
  color: var(--heron-ink);
  font-weight: 550;
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
  .theme-trigger {
    padding: 0 10px;
  }
  .theme-trigger span {
    display: none;
  }
}
</style>
