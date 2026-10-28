<script setup lang="ts">
import { ElMessage, ElOption, ElSelect } from "element-plus";
import { computed, ref } from "vue";
import { useRouter } from "vue-router";

import { authApi, type WorkspaceRef } from "../api/auth";
import { useAuthStore } from "../stores/auth";

/** 平台台那一格不能用空串：Element Plus 把空值当「还没选」，会显示占位符而不是标签。 */
const PLATFORM = "platform";

const auth = useAuthStore();
const router = useRouter();
const workspaces = ref<WorkspaceRef[]>([]);
const loading = ref(false);
const busy = ref(false);

/** 只属于平台身份：在平台台（还没进任何家）和正在查看某家，都看得到这个下拉。 */
const show = computed(
  () => auth.session?.kind === "platform" || auth.user?.scope === "platform-view",
);
const current = computed(() =>
  auth.user?.scope === "platform-view" ? auth.user.tenantId : PLATFORM,
);
/** 列表还没拉到也要能显示当前这家：名字在会话里带着，不必等这一次请求。 */
const options = computed(() => {
  const viewing = auth.user;
  if (viewing?.scope !== "platform-view") return workspaces.value;
  return workspaces.value.some((w) => w.id === viewing.tenantId)
    ? workspaces.value
    : [{ id: viewing.tenantId, name: viewing.tenantName }, ...workspaces.value];
});

async function refresh(open: boolean): Promise<void> {
  if (!open) return;
  loading.value = true;
  try {
    workspaces.value = await authApi.workspaces();
  } catch {
    workspaces.value = [];
  } finally {
    loading.value = false;
  }
}

async function change(value: string): Promise<void> {
  if (busy.value || value === current.value) return;
  busy.value = true;
  const ok = await auth.switchWorkspace(value === PLATFORM ? null : value);
  busy.value = false;
  if (!ok) {
    ElMessage.error(auth.error ?? "切换失败，请稍后重试");
    return;
  }
  // 换票等于换身份，菜单整份变了，落点也必须是新身份的第一个菜单：
  // 停在 /platform/tenants 上看租户页只会撞进 404。
  await router.replace(auth.menus[0]?.path ?? "/");
}
</script>

<template>
  <ElSelect
    v-if="show"
    class="workspace-switch"
    :model-value="current"
    :loading="loading || busy"
    :disabled="busy"
    filterable
    aria-label="切换工作空间"
    title="切换工作空间"
    placeholder="选择工作空间"
    :no-data-text="loading ? '读取中…' : '没有可用的工作空间'"
    @visible-change="refresh"
    @change="change"
  >
    <ElOption label="平台运营" :value="PLATFORM" />
    <ElOption v-for="space in options" :key="space.id" :label="space.name" :value="space.id" />
  </ElSelect>
</template>

<style scoped>
.workspace-switch {
  width: 178px;
}
.workspace-switch :deep(.el-select__wrapper) {
  background: var(--heron-surface);
  box-shadow: 0 0 0 1px var(--heron-line) inset;
}
@media (max-width: 760px) {
  .workspace-switch {
    width: 132px;
  }
}
</style>
