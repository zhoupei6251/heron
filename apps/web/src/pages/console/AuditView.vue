<script setup lang="ts">
import type { AuditLog, PlatformAuditLog } from "@heron/contracts";
import { can } from "@heron/domain";
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
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import ListPanel from "../../components/ListPanel.vue";
import { usePagedList } from "../../composables/usePagedList";
import { dateTime } from "../../format";
import { useAuthStore } from "../../stores/auth";
const platform = useRoute().meta.platform === true;
const auth = useAuthStore();
const allowed = computed(() => platform || (!!auth.user && can(auth.user.role, "settings.write")));
type Entry = AuditLog | PlatformAuditLog;
const list = reactive(
  usePagedList<Entry>(platform ? "/platform/audit" : "/manage/audit", () => allowed.value),
);
const selected = ref<Entry | null>(null);
const drawer = ref(false);
const actions: Record<string, string> = {
  "member.created": "添加成员",
  "member.updated": "更新成员",
  "workspace.updated": "更新工作空间",
  "profile.updated": "更新个人资料",
  "password.changed": "修改密码",
  "sessions.revoked": "注销会话",
  "session.revoked": "强退会话",
  "feed.created": "添加数据源",
  "feed.updated": "更新数据源",
  "tenant.provisioned": "开通租户",
  "tenant.status_changed": "变更租户状态",
  "tenant.plan_changed": "变更租户档位",
  "platform_setting.updated": "修改平台设置",
  "retention.updated": "更新保留策略",
};
function actor(row: Entry): string {
  return ("operatorName" in row ? row.operatorName : row.actorName) ?? "已删除的账号";
}
function inspect(row: Entry) {
  selected.value = row;
  drawer.value = true;
}
onMounted(list.load);
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">{{ platform ? "Platform" : "Workspace" }} / audit trail</span>
        <h1>操作审计</h1>
        <p class="page-description">
          追溯关键管理操作的时间、执行人和变更内容。日志只读，不记录密码。
        </p>
      </div>
      <div class="page-actions">
        <ElTag type="info">{{ platform ? "平台管理操作" : "当前工作空间" }}</ElTag>
      </div>
    </header>
    <ElAlert
      v-if="!allowed"
      title="需要管理员或所有者权限才能查看操作审计。"
      type="info"
      :closable="false"
    />
    <ListPanel
      v-else
      v-model:query="list.query"
      v-model:page="list.page"
      v-model:page-size="list.pageSize"
      :total="list.total"
      :loading="list.loading"
      :error="list.error"
      placeholder="按操作代码搜索，例如 member"
      empty-title="还没有审计记录"
      empty-hint="成员、权限、设置与发布这些写入操作发生后会记在这里。"
      @search="list.search"
      @refresh="list.load"
    >
      <ElTable :data="list.items" row-key="id">
        <ElTableColumn label="时间" min-width="170"
          ><template #default="{ row }">{{ dateTime(row.createTime) }}</template></ElTableColumn
        >
        <ElTableColumn label="执行人" min-width="150"
          ><template #default="{ row }">{{ actor(row as Entry) }}</template></ElTableColumn
        >
        <ElTableColumn label="操作" min-width="210"
          ><template #default="{ row }"
            ><div class="cell-title">{{ actions[row.action] ?? row.action }}</div>
            <div class="cell-subtitle mono">{{ row.action }}</div></template
          ></ElTableColumn
        >
        <ElTableColumn label="目标标识" min-width="170"
          ><template #default="{ row }"
            ><span class="mono">{{ row.targetId ?? "—" }}</span></template
          ></ElTableColumn
        >
        <ElTableColumn label="详情" width="100" fixed="right"
          ><template #default="{ row }"
            ><ElButton link type="primary" @click="inspect(row as Entry)">查看</ElButton></template
          ></ElTableColumn
        >
      </ElTable>
    </ListPanel>
    <ElDrawer v-model="drawer" title="审计详情" size="540px"
      ><template v-if="selected"
        ><ElDescriptions :column="1" border
          ><ElDescriptionsItem label="操作">{{
            actions[selected.action] ?? selected.action
          }}</ElDescriptionsItem
          ><ElDescriptionsItem label="执行人">{{ actor(selected) }}</ElDescriptionsItem
          ><ElDescriptionsItem label="时间">{{ dateTime(selected.createTime) }}</ElDescriptionsItem
          ><ElDescriptionsItem label="目标"
            ><span class="mono">{{ selected.targetId ?? "—" }}</span></ElDescriptionsItem
          ></ElDescriptions
        >
        <h3 style="margin-top: 24px">记录内容</h3>
        <pre class="audit-detail">{{ JSON.stringify(selected.detail, null, 2) }}</pre>
      </template></ElDrawer
    >
  </section>
</template>
<style scoped>
.audit-detail {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--heron-surface-soft);
  border: 1px solid var(--heron-line);
  border-radius: 6px;
  padding: 18px;
  font: 12px/1.8 var(--heron-mono);
}
</style>
