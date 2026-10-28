<script setup lang="ts">
import { Inbox, RefreshCw, Search, TriangleAlert } from "lucide-vue-next";
import { ElButton, ElIcon, ElInput, ElPagination, ElSkeleton } from "element-plus";
defineProps<{
  total: number;
  loading: boolean;
  error: string | null;
  placeholder?: string;
  emptyTitle?: string;
  emptyHint?: string;
}>();
const query = defineModel<string>("query", { required: true });
const page = defineModel<number>("page", { required: true });
const pageSize = defineModel<number>("pageSize", { required: true });
defineEmits<{ search: []; refresh: [] }>();
</script>
<template>
  <div v-if="error" class="page-error" role="alert">
    <ElIcon><TriangleAlert /></ElIcon>
    <div class="page-error-body">
      <strong>读取失败</strong>
      <p>{{ error }}</p>
    </div>
    <ElButton :loading="loading" @click="$emit('refresh')">重新读取</ElButton>
  </div>
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
      <ElButton :icon="RefreshCw" :loading="loading" @click="$emit('refresh')">刷新</ElButton>
    </div>
    <ElSkeleton v-if="loading && !total" class="table-skeleton" :rows="5" animated />
    <div v-else-if="!loading && !total" class="panel-state">
      <span class="state-mark"
        ><ElIcon><Inbox /></ElIcon
      ></span>
      <p class="empty-title">{{ query ? "没有匹配的记录" : (emptyTitle ?? "这里还没有内容") }}</p>
      <p class="empty-hint">
        {{
          query
            ? "换个关键词，或清空搜索条件再看一次。"
            : (emptyHint ?? "数据进来后会自动出现在这张表里。")
        }}
      </p>
      <slot name="empty-action" />
    </div>
    <slot v-else />
    <div class="table-footer">
      <span>{{ loading ? "正在读取…" : `共 ${total} 条记录` }}</span>
      <ElPagination
        v-if="total"
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
