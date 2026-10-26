<script setup lang="ts">
import { CreateTenantBodySchema, type PlatformTenant, type Tenant } from "@heron/contracts";
import { Plus } from "@element-plus/icons-vue";
import {
  ElAlert,
  ElButton,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import { onMounted, reactive, ref } from "vue";
import { request, errorMessage } from "../api/http";
import ListPanel from "../components/ListPanel.vue";
import { usePagedList } from "../composables/usePagedList";
import { dateTime } from "../format";
const list = reactive(usePagedList<PlatformTenant>("/platform/tenants"));
const drawer = ref(false);
const saving = ref(false);
const error = ref<string | null>(null);
const form = reactive({
  name: "",
  slug: "",
  ownerEmail: "",
  ownerDisplayName: "",
  ownerPassword: "",
});
function open() {
  Object.assign(form, {
    name: "",
    slug: "",
    ownerEmail: "",
    ownerDisplayName: "",
    ownerPassword: "",
  });
  error.value = null;
  drawer.value = true;
}
async function create() {
  const parsed = CreateTenantBodySchema.safeParse(form);
  if (!parsed.success) {
    error.value =
      "请填写完整信息：登录标识为 3–63 位小写字母、数字或中划线，邮箱须有效，初始密码至少 12 位。";
    return;
  }
  saving.value = true;
  error.value = null;
  try {
    await request<Tenant>("/platform/tenants", {
      method: "POST",
      body: JSON.stringify(parsed.data),
    });
    ElMessage.success("工作空间及初始所有者已开通");
    form.ownerPassword = "";
    drawer.value = false;
    await list.load();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    saving.value = false;
  }
}
async function toggle(row: PlatformTenant) {
  const suspended = !row.suspendedAt;
  try {
    await ElMessageBox.confirm(
      suspended
        ? `停用“${row.name}”后，该空间所有成员将无法继续访问。数据会保留。`
        : `恢复“${row.name}”的访问？该空间成员可重新登录。`,
      suspended ? "停用工作空间" : "恢复工作空间",
      {
        type: "warning",
        confirmButtonText: suspended ? "确认停用" : "确认恢复",
        cancelButtonText: "取消",
      },
    );
  } catch {
    return;
  }
  saving.value = true;
  try {
    await request<Tenant>(`/platform/tenants/${row.id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ suspended }),
    });
    ElMessage.success(suspended ? "工作空间已停用" : "工作空间已恢复");
    await list.load();
  } catch (cause) {
    list.error = errorMessage(cause);
  } finally {
    saving.value = false;
  }
}
onMounted(list.load);
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Platform / workspaces</span>
        <h1>租户管理</h1>
        <p class="page-description">开通独立工作空间、配置初始所有者，管理租户访问状态。</p>
      </div>
      <div class="page-actions">
        <ElButton type="primary" :icon="Plus" @click="open">开通工作空间</ElButton>
      </div>
    </header>
    <ListPanel
      v-model:query="list.query"
      v-model:page="list.page"
      v-model:page-size="list.pageSize"
      :total="list.total"
      :loading="list.loading"
      :error="list.error"
      placeholder="搜索名称或登录标识"
      @search="list.search"
      @refresh="list.load"
    >
      <ElTable
        :data="list.items"
        row-key="id"
        empty-text="暂无工作空间，点击“开通工作空间”创建首个租户。"
      >
        <ElTableColumn label="工作空间" min-width="240"
          ><template #default="{ row }"
            ><div class="cell-title">{{ row.name }}</div>
            <div class="cell-subtitle mono">{{ row.slug }}</div></template
          ></ElTableColumn
        >
        <ElTableColumn label="状态" width="120"
          ><template #default="{ row }"
            ><ElTag :type="row.suspendedAt ? 'danger' : 'success'">{{
              row.suspendedAt ? "已停用" : "正常"
            }}</ElTag></template
          ></ElTableColumn
        >
        <ElTableColumn prop="userCount" label="成员" width="95" /><ElTableColumn
          prop="taskCount"
          label="任务"
          width="95"
        />
        <ElTableColumn label="开通时间" min-width="175"
          ><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></ElTableColumn
        >
        <ElTableColumn label="访问管理" width="130" fixed="right"
          ><template #default="{ row }"
            ><ElButton
              link
              :type="row.suspendedAt ? 'primary' : 'danger'"
              :disabled="saving"
              @click="toggle(row as PlatformTenant)"
              >{{ row.suspendedAt ? "恢复访问" : "停用空间" }}</ElButton
            ></template
          ></ElTableColumn
        >
      </ElTable>
    </ListPanel>
    <ElDrawer
      v-model="drawer"
      title="开通工作空间"
      size="490px"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      @closed="form.ownerPassword = ''"
    >
      <ElAlert
        v-if="error"
        :title="error"
        type="error"
        show-icon
        :closable="false"
        class="page-alert"
      />
      <ElForm label-position="top" @submit.prevent="create">
        <h3>工作空间</h3>
        <ElFormItem label="空间名称" for="tenant-name" required
          ><ElInput id="tenant-name" v-model="form.name" maxlength="100" /></ElFormItem
        ><ElFormItem label="登录标识" for="tenant-slug" required
          ><ElInput
            id="tenant-slug"
            v-model="form.slug"
            maxlength="63"
            placeholder="例如：daily-news"
          />
          <p class="form-hint">小写字母、数字、中划线，3–63 位。成员登录时需要填写。</p></ElFormItem
        >
        <h3 style="margin-top: 28px">初始所有者</h3>
        <ElFormItem label="姓名" for="tenant-owner-name" required
          ><ElInput
            id="tenant-owner-name"
            v-model="form.ownerDisplayName"
            maxlength="80" /></ElFormItem
        ><ElFormItem label="邮箱" for="tenant-owner-email" required
          ><ElInput
            id="tenant-owner-email"
            v-model="form.ownerEmail"
            type="email"
            maxlength="254"
            autocomplete="off" /></ElFormItem
        ><ElFormItem label="初始密码" for="tenant-owner-password" required
          ><ElInput
            id="tenant-owner-password"
            v-model="form.ownerPassword"
            type="password"
            show-password
            maxlength="128"
            autocomplete="new-password"
          />
          <p class="form-hint">至少 12 位，无默认密码。请通过安全渠道交给所有者。</p></ElFormItem
        >
        <ElAlert
          title="该账号仅拥有此工作空间的所有者权限，不具备平台运营权限。"
          type="info"
          :closable="false"
        />
        <div class="form-actions">
          <ElButton type="primary" native-type="submit" :loading="saving">确认开通</ElButton
          ><ElButton :disabled="saving" @click="drawer = false">取消</ElButton>
        </div>
      </ElForm>
    </ElDrawer>
  </section>
</template>
