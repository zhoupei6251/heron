<script setup lang="ts">
import { FeedSourceBodySchema, type FeedSource } from "@heron/contracts";
import { can } from "@heron/domain";
import { Plus } from "@element-plus/icons-vue";
import {
  ElAlert,
  ElButton,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { request, errorMessage } from "../../api/http";
import ListPanel from "../../components/ListPanel.vue";
import { usePagedList } from "../../composables/usePagedList";
import { dateTime } from "../../format";
import { useAuthStore } from "../../stores/auth";
const auth = useAuthStore();
const allowed = computed(() => !!auth.user && can(auth.user.role, "settings.write"));
const list = reactive(usePagedList<FeedSource>("/manage/feeds", () => allowed.value));
const drawer = ref(false);
const saving = ref(false);
const error = ref<string | null>(null);
const fetching = ref<string | null>(null);
const target = ref<FeedSource | null>(null);
const form = reactive({ name: "", url: "", frequencySec: 900, enabled: true });
function open(feed: FeedSource | null) {
  target.value = feed;
  Object.assign(form, {
    name: feed?.name ?? "",
    url: feed?.url ?? "",
    frequencySec: feed?.frequencySec ?? 900,
    enabled: feed?.enabled ?? true,
  });
  error.value = null;
  drawer.value = true;
}
async function save() {
  const parsed = FeedSourceBodySchema.safeParse(form);
  if (!parsed.success) {
    error.value = "请检查名称、HTTP(S) 地址和采集间隔（60–86400 秒），地址不能包含账号密码。";
    return;
  }
  saving.value = true;
  error.value = null;
  try {
    await request(target.value ? `/manage/feeds/${target.value.id}` : "/manage/feeds", {
      method: target.value ? "PUT" : "POST",
      body: JSON.stringify(parsed.data),
    });
    ElMessage.success("数据源配置已保存");
    drawer.value = false;
    await list.load();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    saving.value = false;
  }
}
async function fetchNow(feed: FeedSource) {
  fetching.value = feed.id;
  try {
    const result = await request<{ imported: number }>(`/manage/feeds/${feed.id}/fetch`, {
      method: "POST",
    });
    ElMessage.success(`采集完成，合并 ${result.imported} 条候选`);
  } catch (cause) {
    ElMessage.error(errorMessage(cause));
  } finally {
    fetching.value = null;
    await list.load();
  }
}
onMounted(list.load);
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Workspace / sources</span>
        <h1>数据源配置</h1>
        <p class="page-description">统一管理选题来源，保留清晰的采集配置。</p>
      </div>
      <div class="page-actions">
        <ElButton type="primary" :icon="Plus" :disabled="!allowed" @click="open(null)"
          >添加数据源</ElButton
        >
      </div>
    </header>
    <ElAlert
      title="启用的数据源由后台按各自间隔自动采集（worker 每 30 秒检查一次），也可以在这里立即抓取一次。"
      type="info"
      show-icon
      :closable="false"
      class="page-alert"
    />
    <ElAlert
      v-if="!allowed"
      title="需要管理员或所有者权限才能查看和配置数据源。"
      type="warning"
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
      placeholder="搜索名称或地址"
      @search="list.search"
      @refresh="list.load"
    >
      <ElTable :data="list.items" row-key="id" empty-text="还没有数据源，添加团队关注的新闻来源。">
        <ElTableColumn label="来源" min-width="290"
          ><template #default="{ row }"
            ><div class="cell-title">{{ row.name }}</div>
            <div class="cell-subtitle" style="overflow-wrap: anywhere">{{ row.url }}</div></template
          ></ElTableColumn
        >
        <ElTableColumn label="采集间隔" width="130"
          ><template #default="{ row }">{{ row.frequencySec }} 秒</template></ElTableColumn
        >
        <ElTableColumn label="配置状态" width="120"
          ><template #default="{ row }"
            ><ElTag :type="row.enabled ? 'success' : 'info'">{{
              row.enabled ? "已启用" : "已停用"
            }}</ElTag></template
          ></ElTableColumn
        >
        <ElTableColumn label="最近成功采集" min-width="170"
          ><template #default="{ row }">{{
            row.lastFetchAt ? dateTime(row.lastFetchAt) : "尚未成功采集"
          }}</template></ElTableColumn
        >
        <ElTableColumn label="上次尝试" min-width="230"
          ><template #default="{ row }">
            <div>{{ row.lastAttemptAt ? dateTime(row.lastAttemptAt) : "尚未尝试" }}</div>
            <div
              v-if="row.lastError"
              class="cell-subtitle"
              style="overflow-wrap: anywhere; color: var(--el-color-danger)"
            >
              {{ row.lastError }}
            </div>
          </template></ElTableColumn
        >
        <ElTableColumn label="操作" width="180" fixed="right"
          ><template #default="{ row }"
            ><ElButton link type="primary" @click="open(row as FeedSource)">配置</ElButton>
            <ElButton
              link
              type="primary"
              :loading="fetching === row.id"
              @click="fetchNow(row as FeedSource)"
              >立即采集</ElButton
            ></template
          ></ElTableColumn
        >
      </ElTable>
    </ListPanel>
    <ElDrawer
      v-model="drawer"
      :title="target ? '编辑数据源' : '添加数据源'"
      size="460px"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <ElAlert v-if="error" :title="error" type="error" :closable="false" class="page-alert" />
      <ElForm label-position="top" @submit.prevent="save">
        <ElFormItem label="来源名称" for="feed-name" required
          ><ElInput id="feed-name" v-model="form.name" maxlength="100" placeholder="例如：科技新闻"
        /></ElFormItem>
        <ElFormItem label="订阅地址" for="feed-url" required
          ><ElInput
            id="feed-url"
            v-model="form.url"
            type="url"
            maxlength="2048"
            placeholder="HTTP(S) 订阅地址"
        /></ElFormItem>
        <ElFormItem label="采集间隔（秒）" for="feed-frequency" required
          ><ElInputNumber
            id="feed-frequency"
            v-model="form.frequencySec"
            :min="60"
            :max="86400"
            :precision="0"
        /></ElFormItem>
        <ElFormItem label="启用配置" for="feed-enabled"
          ><ElSwitch
            id="feed-enabled"
            v-model="form.enabled"
            active-text="启用"
            inactive-text="停用"
        /></ElFormItem>
        <div class="form-actions">
          <ElButton type="primary" native-type="submit" :loading="saving">保存数据源</ElButton
          ><ElButton :disabled="saving" @click="drawer = false">取消</ElButton>
        </div>
      </ElForm>
    </ElDrawer>
  </section>
</template>
