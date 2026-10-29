<script setup lang="ts">
import type { TaskSummary } from "@heron/contracts";
import {
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElMessage,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { tasksApi } from "../../api";
import { errorMessage } from "../../api/http";
import ListPanel from "../../components/ListPanel.vue";
import StageTrack from "../../components/StageTrack.vue";
import { usePagedList } from "../../composables/usePagedList";
import { dateTime } from "../../format";
import { useAuthStore } from "../../stores/auth";
import { useTasksStore } from "../../stores/tasks";
const auth = useAuthStore();
const tasks = useTasksStore();
const allowed = computed(() => auth.canWrite("task.claim"));
const list = reactive(usePagedList<TaskSummary>("/manage/tasks"));
const saving = ref(false);
const drawer = ref(false);
const selected = ref<TaskSummary | null>(null);
function inspect(row: TaskSummary) {
  selected.value = row;
  drawer.value = true;
}
async function claim(row: TaskSummary) {
  saving.value = true;
  try {
    await tasksApi.claim(row.id);
    ElMessage.success("已认领，任务进入生产队列");
    await tasks.refresh();
  } catch (cause) {
    list.error = errorMessage(cause);
  } finally {
    saving.value = false;
  }
}
watch(
  () => tasks.tasks,
  () => {
    void list.load();
  },
  { deep: true },
);
onMounted(list.load);
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Content / production</span>
        <h1>生产任务</h1>
        <p class="page-description">从认领到发布，查看真实进度与每一次停下来的原因。</p>
      </div>
      <div class="page-actions">
        <ElTag :type="tasks.connected ? 'success' : 'warning'">{{
          tasks.connected ? "进度实时更新" : "实时连接中断，可手动刷新"
        }}</ElTag>
      </div>
    </header>
    <ListPanel
      v-model:query="list.query"
      v-model:page="list.page"
      v-model:page-size="list.pageSize"
      :total="list.total"
      :loading="list.loading"
      :error="list.error"
      placeholder="搜索任务标题"
      empty-title="还没有生产任务"
      empty-hint="从候选管理挑一条选题开始生产，任务会在这里按阶段推进。"
      @search="list.search"
      @refresh="list.load"
    >
      <template #empty-action>
        <RouterLink to="/candidates"><ElButton type="primary">去挑选题</ElButton></RouterLink>
      </template>
      <ElTable :data="list.items" row-key="id">
        <ElTableColumn label="生产内容" min-width="310"
          ><template #default="{ row }"
            ><div class="cell-title">{{ row.title ?? "未命名任务" }}</div>
            <div class="cell-subtitle">
              {{
                row.capability === "p0-fake"
                  ? "P0 演示 · 非真实发布"
                  : row.capability === "news"
                    ? "新闻短视频"
                    : row.capability
              }}
              ·
              {{ row.id.slice(0, 8) }}
            </div></template
          ></ElTableColumn
        >
        <ElTableColumn label="阶段" width="150"
          ><template #default="{ row }"><StageTrack :stage="row.stage" dense /></template
        ></ElTableColumn>
        <ElTableColumn prop="attempts" label="尝试次数" width="110" />
        <ElTableColumn label="更新时间" min-width="170"
          ><template #default="{ row }">{{ dateTime(row.updateTime) }}</template></ElTableColumn
        >
        <ElTableColumn label="操作" width="175" fixed="right"
          ><template #default="{ row }"
            ><ElButton
              v-if="row.stage === 'candidate'"
              link
              type="primary"
              :disabled="!allowed || saving"
              @click="claim(row as TaskSummary)"
              >认领</ElButton
            ><RouterLink
              v-if="row.stage === 'review'"
              :to="{ path: '/review', query: { task: row.id } }"
              ><ElButton link type="primary">审片</ElButton></RouterLink
            ><ElButton
              link
              :type="row.errorReason ? 'danger' : 'primary'"
              @click="inspect(row as TaskSummary)"
              >{{ row.errorReason ? "失败原因" : "详情" }}</ElButton
            ></template
          ></ElTableColumn
        >
      </ElTable>
    </ListPanel>
    <ElDrawer v-model="drawer" title="任务详情" size="580px"
      ><template v-if="selected"
        ><StageTrack :stage="selected.stage" />
        <h2 style="margin-top: 18px">{{ selected.title ?? "未命名任务" }}</h2>
        <ElDescriptions :column="1" border
          ><ElDescriptionsItem label="任务标识"
            ><span class="mono">{{ selected.id }}</span></ElDescriptionsItem
          ><ElDescriptionsItem label="生产能力">{{ selected.capability }}</ElDescriptionsItem
          ><ElDescriptionsItem label="认领人标识"
            ><span class="mono">{{ selected.claimedBy ?? "尚未认领" }}</span></ElDescriptionsItem
          ><ElDescriptionsItem label="认领时间">{{
            dateTime(selected.claimedAt)
          }}</ElDescriptionsItem
          ><ElDescriptionsItem label="更新时间">{{
            dateTime(selected.updateTime)
          }}</ElDescriptionsItem></ElDescriptions
        ><template v-if="selected.errorReason"
          ><h3 style="margin-top: 24px">完整失败原因</h3>
          <pre class="preformatted-error">{{ selected.errorReason }}</pre>
        </template>
        <p class="form-hint" style="margin-top: 20px">
          发布失败不等于外部未发布。重试能力开放前，必须先核实外部状态；此页不提供未经核实的重试。
        </p></template
      ></ElDrawer
    >
  </section>
</template>
