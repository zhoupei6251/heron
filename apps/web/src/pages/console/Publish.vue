<script setup lang="ts">
import type { PublishSummary, PublishStatus } from "@heron/contracts";
import {
  ElAlert,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import { onMounted, reactive, ref } from "vue";
import ListPanel from "../../components/ListPanel.vue";
import { usePagedList } from "../../composables/usePagedList";
import { dateTime } from "../../format";
const list = reactive(usePagedList<PublishSummary>("/manage/publishes"));
const selected = ref<PublishSummary | null>(null);
const drawer = ref(false);
const statuses: Record<PublishStatus, string> = {
  pending: "等待发送",
  sent: "已发送，待核实",
  confirmed: "回执已确认",
  failed: "失败",
};
function inspect(row: PublishSummary) {
  selected.value = row;
  drawer.value = true;
}
onMounted(list.load);
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Content / publishing</span>
        <h1>发布记录</h1>
        <p class="page-description">记录发送与回执状态，始终与对应的成片版本绑定。</p>
      </div>
    </header>
    <ElAlert
      title="演示流程的回执不代表真实平台发布。正式授权发布链路尚未接通；发生失败时，必须先核实外部状态，不能直接重试。"
      type="info"
      show-icon
      :closable="false"
      class="page-alert"
    />
    <ListPanel
      v-model:query="list.query"
      v-model:page="list.page"
      v-model:page-size="list.pageSize"
      :total="list.total"
      :loading="list.loading"
      :error="list.error"
      placeholder="搜索内容标题"
      @search="list.search"
      @refresh="list.load"
    >
      <ElTable
        :data="list.items"
        row-key="id"
        empty-text="暂无发布记录，已创建的发布单会在这里显示。"
      >
        <ElTableColumn label="内容 / 版本" min-width="290"
          ><template #default="{ row }"
            ><div class="cell-title">{{ row.title ?? "未命名内容" }}</div>
            <div class="cell-subtitle">
              版本 {{ row.renderVersion }} · {{ row.taskId.slice(0, 8) }}
            </div></template
          ></ElTableColumn
        >
        <ElTableColumn prop="platform" label="发布通道" min-width="130" />
        <ElTableColumn label="状态" min-width="155"
          ><template #default="{ row }"
            ><ElTag
              :type="
                row.status === 'failed'
                  ? 'danger'
                  : row.status === 'confirmed'
                    ? 'success'
                    : 'warning'
              "
              >{{ statuses[row.status as PublishStatus] }}</ElTag
            ></template
          ></ElTableColumn
        >
        <ElTableColumn label="更新时间" min-width="170"
          ><template #default="{ row }">{{ dateTime(row.updatedAt) }}</template></ElTableColumn
        >
        <ElTableColumn label="操作" width="110" fixed="right"
          ><template #default="{ row }"
            ><ElButton link type="primary" @click="inspect(row as PublishSummary)"
              >查看回执</ElButton
            ></template
          ></ElTableColumn
        >
      </ElTable>
    </ListPanel>
    <ElDrawer v-model="drawer" title="发布单详情" size="560px"
      ><template v-if="selected"
        ><h2>{{ selected.title ?? "未命名内容" }}</h2>
        <ElDescriptions :column="1" border
          ><ElDescriptionsItem label="发布通道">{{ selected.platform }}</ElDescriptionsItem
          ><ElDescriptionsItem label="状态">{{ statuses[selected.status] }}</ElDescriptionsItem
          ><ElDescriptionsItem label="成片版本">{{ selected.renderVersion }}</ElDescriptionsItem
          ><ElDescriptionsItem label="内容声明">{{ selected.declaration }}</ElDescriptionsItem
          ><ElDescriptionsItem label="核实时间">{{
            dateTime(selected.verifiedAt)
          }}</ElDescriptionsItem
          ><ElDescriptionsItem label="记录地址"
            ><span style="overflow-wrap: anywhere">{{
              selected.postUrl ?? "尚无地址"
            }}</span></ElDescriptionsItem
          ><ElDescriptionsItem label="发布单标识"
            ><span class="mono">{{ selected.id }}</span></ElDescriptionsItem
          ></ElDescriptions
        >
        <p class="form-hint" style="margin-top: 20px">
          回执与地址按数据库原样展示，演示地址不能作为外部真实发布的证明。
        </p></template
      ></ElDrawer
    >
  </section>
</template>
