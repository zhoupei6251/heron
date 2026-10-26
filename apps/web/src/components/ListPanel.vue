<script setup lang="ts">
import { Search, Refresh } from "@element-plus/icons-vue";
import { ElAlert, ElButton, ElInput, ElPagination } from "element-plus";
defineProps<{ total: number; loading: boolean; error: string | null; placeholder?: string }>();
const query = defineModel<string>("query", { required: true });
const page = defineModel<number>("page", { required: true });
const pageSize = defineModel<number>("pageSize", { required: true });
defineEmits<{ search: []; refresh: [] }>();
</script>
<template>
  <ElAlert
    v-if="error"
    :title="error"
    type="error"
    show-icon
    :closable="false"
    class="page-alert"
  />
  <div class="panel" :aria-busy="loading">
    <div class="table-toolbar">
      <form class="toolbar-filters" role="search" @submit.prevent="$emit('search')">
        <ElInput
          v-model="query"
          :prefix-icon="Search"
          :placeholder="placeholder ?? '搜索'"
          :aria-label="placeholder ?? '搜索'"
          maxlength="200"
          clearable
          @clear="$emit('search')"
        />
        <ElButton native-type="submit" :loading="loading">搜索</ElButton>
      </form>
      <ElButton :icon="Refresh" :loading="loading" @click="$emit('refresh')">刷新</ElButton>
    </div>
    <slot />
    <div class="table-footer">
      <span>{{ loading ? "正在读取…" : `共 ${total} 条记录` }}</span>
      <ElPagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="prev, pager, next, sizes"
        background
        :disabled="loading"
      />
    </div>
  </div>
</template>
