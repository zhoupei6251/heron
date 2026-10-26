<script setup lang="ts">
import type { Candidate, CandidateStatus } from "@heron/contracts";
import { can } from "@heron/domain";
import { Plus } from "@element-plus/icons-vue";
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { RouterLink } from "vue-router";
import { tasksApi } from "../api";
import { errorMessage } from "../api/http";
import ListPanel from "../components/ListPanel.vue";
import { usePagedList } from "../composables/usePagedList";
import { dateTime } from "../format";
import { useAuthStore } from "../stores/auth";
const auth = useAuthStore();
const allowed = computed(() => !!auth.user && can(auth.user.role, "task.claim"));
const list = reactive(usePagedList<Candidate>("/manage/candidates"));
const dialog = ref(false);
const title = ref("");
const saving = ref(false);
const error = ref<string | null>(null);
const selected = ref<Candidate | null>(null);
const drawer = ref(false);
const statuses: Record<CandidateStatus, string> = {
  candidate: "待选题",
  selected: "已选题",
  drafted: "已成稿",
  rendered: "已成片",
  published: "已发布",
  rejected: "已退回",
};
async function createTask() {
  if (!title.value.trim()) {
    error.value = "请输入选题标题。";
    return;
  }
  saving.value = true;
  error.value = null;
  try {
    await tasksApi.create({ capability: "p0-fake", title: title.value.trim() });
    dialog.value = false;
    title.value = "";
    ElMessage.success("演示选题已创建，可前往生产任务认领");
    await list.load();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    saving.value = false;
  }
}
function inspect(row: Candidate) {
  selected.value = row;
  drawer.value = true;
}
onMounted(list.load);
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Content / discovery</span>
        <h1>候选管理</h1>
        <p class="page-description">先发现值得讲的新闻，再决定投入生产。</p>
      </div>
      <div class="page-actions">
        <ElButton
          :icon="Plus"
          :disabled="!allowed"
          @click="
            dialog = true;
            error = null;
          "
          >新建演示选题</ElButton
        >
      </div>
    </header>
    <ListPanel
      v-model:query="list.query"
      v-model:page="list.page"
      v-model:page-size="list.pageSize"
      :total="list.total"
      :loading="list.loading"
      :error="list.error"
      placeholder="搜索选题标题"
      @search="list.search"
      @refresh="list.load"
    >
      <ElTable
        :data="list.items"
        row-key="id"
        empty-text="候选库还是空的。可新建演示选题体验流程，自动采集尚未接通。"
      >
        <ElTableColumn label="选题" min-width="340"
          ><template #default="{ row }"
            ><ElButton
              link
              type="primary"
              style="white-space: normal; text-align: left; height: auto"
              @click="inspect(row as Candidate)"
              >{{ row.title }}</ElButton
            >
            <div class="cell-subtitle">{{ row.sourceName ?? "手动录入 / 演示" }}</div></template
          ></ElTableColumn
        >
        <ElTableColumn label="热度" width="100"
          ><template #default="{ row }"
            ><span class="mono">{{ row.heat }}</span></template
          ></ElTableColumn
        >
        <ElTableColumn label="状态" width="120"
          ><template #default="{ row }"
            ><ElTag type="info">{{ statuses[row.status as CandidateStatus] }}</ElTag></template
          ></ElTableColumn
        >
        <ElTableColumn label="最近出现" min-width="170"
          ><template #default="{ row }">{{ dateTime(row.lastSeenAt) }}</template></ElTableColumn
        >
        <ElTableColumn label="操作" width="120" fixed="right"
          ><template #default="{ row }"
            ><ElButton link type="primary" @click="inspect(row as Candidate)"
              >查看选题</ElButton
            ></template
          ></ElTableColumn
        >
      </ElTable>
    </ListPanel>
    <ElDialog
      v-model="dialog"
      title="新建演示选题"
      width="min(480px, 92vw)"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      ><ElAlert
        title="此入口使用 P0 演示能力，不调用真实生成模型或真实发布平台。"
        type="info"
        :closable="false"
        class="page-alert"
      /><ElAlert
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        class="page-alert"
      /><ElForm label-position="top" @submit.prevent="createTask"
        ><ElFormItem label="选题标题" for="candidate-title" required
          ><ElInput
            id="candidate-title"
            v-model="title"
            maxlength="200"
            placeholder="输入要演示的新闻标题"
        /></ElFormItem>
        <div class="form-actions">
          <ElButton type="primary" native-type="submit" :loading="saving">创建演示选题</ElButton
          ><ElButton :disabled="saving" @click="dialog = false">取消</ElButton>
        </div></ElForm
      ></ElDialog
    >
    <ElDrawer v-model="drawer" title="选题详情" size="560px"
      ><template v-if="selected"
        ><ElTag type="info">{{ statuses[selected.status] }}</ElTag>
        <h2 style="margin-top: 18px">{{ selected.title }}</h2>
        <p class="page-description" style="white-space: pre-wrap">
          {{ selected.summary || "暂无摘要" }}
        </p>
        <p class="form-hint" style="margin-top: 24px">
          来源：{{ selected.sourceName ?? "手动录入 / 演示" }}<br />首次出现：{{
            dateTime(selected.firstSeenAt)
          }}<br />出现次数：{{ selected.hits }}
        </p>
        <div class="form-actions">
          <RouterLink :to="{ path: '/production', query: { q: selected.title } }"
            ><ElButton type="primary">查看关联生产任务</ElButton></RouterLink
          >
        </div></template
      ></ElDrawer
    >
  </section>
</template>
