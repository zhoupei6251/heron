<script setup lang="ts">
import type { Session } from "@heron/contracts";
import { can } from "@heron/domain";
import { ElButton, ElMessage, ElMessageBox, ElTable, ElTableColumn, ElTag } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { errorMessage, request } from "../../api/http";
import { roleLabel } from "../../auth/roles";
import ListPanel from "../../components/ListPanel.vue";
import { usePagedList } from "../../composables/usePagedList";
import { dateTime } from "../../format";
import { useAuthStore } from "../../stores/auth";

const auth = useAuthStore();
/** 强退别人的登录要 session.manage；自己的那条不需要——那等于退出登录。 */
const manageable = computed(() => !!auth.user && can(auth.user.role, "session.manage"));
const list = reactive(usePagedList<Session>("/manage/sessions"));
const busy = ref<string | null>(null);

/** 强退本机之后票就成了废纸：清掉本地会话，守卫会把页面送回登录。 */
async function revoke(row: Session) {
  if (!row.self || !row.current) {
    try {
      await ElMessageBox.confirm(
        `「${row.userName}」在这台设备上的登录会立刻失效，对方需要重新输入口令。`,
        "强退这条会话？",
        { confirmButtonText: "强退", cancelButtonText: "取消", type: "warning" },
      );
    } catch {
      return;
    }
  }
  busy.value = row.id;
  try {
    await request(`/manage/sessions/${row.id}`, { method: "DELETE" });
    if (row.current) {
      auth.clear();
      return;
    }
    ElMessage.success(row.self ? "已退出那台设备" : "已强退该会话");
    await list.load();
  } catch (cause) {
    ElMessage.error(errorMessage(cause));
  } finally {
    busy.value = null;
  }
}

/** 设备那格只放得下第一段：整条 UA 有 100 多个字符，完整存着给人悬停看。 */
function device(userAgent: string | null): string {
  if (!userAgent) return "未上报设备";
  const [head] = userAgent.split(" ").filter((part) => part.startsWith("("));
  const platform = head?.replace(/[()]/g, "").split(";")[0]?.trim();
  const browser = /Firefox\//.test(userAgent)
    ? "Firefox"
    : /Edg\//.test(userAgent)
      ? "Edge"
      : /Chrome\//.test(userAgent)
        ? "Chrome"
        : /Safari\//.test(userAgent)
          ? "Safari"
          : userAgent.split(/[ /]/)[0] || "未知浏览器";
  return platform ? `${browser} · ${platform}` : browser;
}

onMounted(list.load);
</script>

<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Workspace / sessions</span>
        <h1>在线会话</h1>
        <p class="page-description">
          每一处登录都是一条独立的会话。退出登录只关掉当前这一条，其他设备上的照常在线。
        </p>
      </div>
      <div class="page-actions">
        <ElTag v-if="manageable" effect="plain">可强退本空间所有会话</ElTag>
        <ElTag v-else type="info" effect="plain">只显示你自己的会话</ElTag>
      </div>
    </header>
    <ListPanel
      v-model:query="list.query"
      v-model:page="list.page"
      v-model:page-size="list.pageSize"
      :total="list.total"
      :loading="list.loading"
      :error="list.error"
      placeholder="按成员姓名、邮箱或设备搜索"
      empty-title="没有在线会话"
      empty-hint="登录产生的会话会列在这里；退出登录或到期后从这里消失。"
      @search="list.search"
      @refresh="list.load"
    >
      <ElTable :data="list.items" row-key="id">
        <ElTableColumn label="成员" min-width="210">
          <template #default="{ row }">
            <div class="cell-title">
              {{ row.userName }}
              <ElTag v-if="row.current" size="small" type="success">本机</ElTag>
            </div>
            <div class="cell-subtitle">{{ row.userEmail }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="角色" width="110">
          <template #default="{ row }">{{ roleLabel(row.userRole) }}</template>
        </ElTableColumn>
        <ElTableColumn label="登入设备" min-width="190">
          <template #default="{ row }">
            <span :title="row.userAgent ?? ''">{{ device(row.userAgent) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="登录时间" min-width="170">
          <template #default="{ row }">{{ dateTime(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="最近活动" min-width="170">
          <template #default="{ row }">{{ dateTime(row.lastSeenAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <ElButton
              link
              type="danger"
              :loading="busy === row.id"
              :disabled="!row.self && !manageable"
              @click="revoke(row as Session)"
            >
              {{ row.current ? "退出登录" : row.self ? "退出" : "强退" }}
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ListPanel>
  </section>
</template>
