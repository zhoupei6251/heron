<script setup lang="ts">
import {
  CreateTenantBodySchema,
  MIN_PASSWORD_LENGTH,
  PLAN_IDS,
  PLAN_LIMITS,
  type PlatformTenant,
  type PlanId,
  type Tenant,
} from "@heron/contracts";
import { PLAN_LABELS } from "@heron/domain";
import { Plus } from "lucide-vue-next";
import {
  ElAlert,
  ElButton,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import { onMounted, reactive, ref } from "vue";
import { request, errorMessage } from "../../api/http";
import ListPanel from "../../components/ListPanel.vue";
import { usePagedList } from "../../composables/usePagedList";
import { dateTime } from "../../format";
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
    error.value = `请填写完整信息：登录标识为 3–63 位小写字母、数字或中划线，邮箱须有效，初始密码至少 ${String(MIN_PASSWORD_LENGTH)} 位。`;
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
/**
 * 改档位只在这里做：额度是商务决定，租户台只能看到「还剩多少」，看不到「改」。
 * 确认框必须把新档位的三个上限写出来——只写档位名的话，改的人不知道自己在承诺多少条渲染。
 */
async function changePlan(row: PlatformTenant, plan: PlanId) {
  if (plan === row.plan) return;
  const limits = PLAN_LIMITS[plan];
  try {
    await ElMessageBox.confirm(
      `“${row.name}”改为${PLAN_LABELS[plan]}档：成员 ${String(limits.seats)}、每月渲染 ${String(limits.rendersPerMonth)}、每月发布 ${String(limits.publishesPerMonth)}。已经在用的不会因为降档被删，但超出后流水线会拒绝新任务。`,
      "变更档位",
      { confirmButtonText: "确认变更", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  saving.value = true;
  try {
    await request<Tenant>(`/platform/tenants/${row.id}/plan`, {
      method: "PATCH",
      body: JSON.stringify({ plan }),
    });
    ElMessage.success(`已改为${PLAN_LABELS[plan]}档`);
    await list.load();
  } catch (cause) {
    list.error = errorMessage(cause);
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
      empty-title="还没有开通工作空间"
      empty-hint="开通后，团队成员在登录页用邮箱直接进入自己的工作空间。"
      @search="list.search"
      @refresh="list.load"
    >
      <template #empty-action>
        <ElButton type="primary" :icon="Plus" @click="open">开通工作空间</ElButton>
      </template>
      <ElTable :data="list.items" row-key="id">
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
        <ElTableColumn label="档位" width="130"
          ><template #default="{ row }"
            ><ElSelect
              :model-value="row.plan"
              size="small"
              :disabled="saving"
              aria-label="变更档位"
              @change="changePlan(row as PlatformTenant, $event as PlanId)"
              ><ElOption
                v-for="id in PLAN_IDS"
                :key="id"
                :label="PLAN_LABELS[id]"
                :value="id" /></ElSelect></template
        ></ElTableColumn>
        <ElTableColumn prop="userCount" label="成员" width="95" /><ElTableColumn
          prop="taskCount"
          label="任务"
          width="95"
        />
        <ElTableColumn label="开通时间" min-width="175"
          ><template #default="{ row }">{{ dateTime(row.createTime) }}</template></ElTableColumn
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
          <p class="form-hint">
            至少 {{ MIN_PASSWORD_LENGTH }} 位，无默认密码。请通过安全渠道交给所有者。
          </p></ElFormItem
        >
        <ElAlert
          title="该账号仅拥有此工作空间的所有者权限，不具备平台运营权限。新空间默认免费档，额度可在此页改档。"
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
