<script setup lang="ts">
import {
  CreateMemberBodySchema,
  MIN_PASSWORD_LENGTH,
  UpdateMemberBodySchema,
  ROLES,
  type Member,
} from "@heron/contracts";
import { can } from "@heron/domain";
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
import { computed, onMounted, reactive, ref } from "vue";
import { request, errorMessage } from "../../api/http";
import { roleLabel, VIEW_FENCE } from "../../auth/roles";
import ListPanel from "../../components/ListPanel.vue";
import { usePagedList } from "../../composables/usePagedList";
import { dateTime } from "../../format";
import { useAuthStore } from "../../stores/auth";
const auth = useAuthStore();
const allowed = computed(() => !!auth.user && can(auth.user.role, "user.manage"));
/** 查看态读得到成员名单（读接口按 user.manage 设门槛），但增删改都要由该空间的成员执行。 */
const writable = computed(() => auth.canWrite("user.manage"));
const list = reactive(usePagedList<Member>("/manage/members", () => allowed.value));
const drawer = ref(false);
const target = ref<Member | null>(null);
const saving = ref(false);
const error = ref<string | null>(null);
const form = reactive({ email: "", displayName: "", password: "", role: "viewer" });
function open(member: Member | null) {
  target.value = member;
  Object.assign(form, {
    email: member?.email ?? "",
    displayName: member?.displayName ?? "",
    password: "",
    role: member?.role ?? "viewer",
  });
  error.value = null;
  drawer.value = true;
}
async function save() {
  const parsed = target.value
    ? UpdateMemberBodySchema.safeParse({ displayName: form.displayName, role: form.role })
    : CreateMemberBodySchema.safeParse(form);
  if (!parsed.success) {
    error.value = parsed.error.issues.map((issue) => issue.message).join("；");
    return;
  }
  saving.value = true;
  error.value = null;
  try {
    await request<Member>(target.value ? `/manage/members/${target.value.id}` : "/manage/members", {
      method: target.value ? "PATCH" : "POST",
      body: JSON.stringify(parsed.data),
    });
    ElMessage.success(target.value ? "成员已更新" : "成员已添加");
    drawer.value = false;
    form.password = "";
    await list.load();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    saving.value = false;
  }
}
async function toggle(member: Member) {
  const disabled = !member.disabledAt;
  try {
    await ElMessageBox.confirm(
      `${disabled ? "停用" : "恢复"}“${member.displayName}”？${disabled ? "其当前登录会话将失效。" : "恢复后需要重新登录。"}`,
      disabled ? "停用成员" : "恢复成员",
      {
        confirmButtonText: disabled ? "确认停用" : "确认恢复",
        cancelButtonText: "取消",
        type: "warning",
      },
    );
  } catch {
    return;
  }
  saving.value = true;
  try {
    await request(`/manage/members/${member.id}`, {
      method: "PATCH",
      body: JSON.stringify({ disabled }),
    });
    ElMessage.success(disabled ? "成员已停用" : "成员已恢复");
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
        <span class="eyebrow">Workspace / people</span>
        <h1>成员管理</h1>
        <p class="page-description">
          让合适的人，拥有恰当的权限。角色变更会使该成员的登录会话失效。
        </p>
      </div>
      <div class="page-actions">
        <ElButton type="primary" :icon="Plus" :disabled="!writable" @click="open(null)"
          >添加成员</ElButton
        >
      </div>
    </header>
    <ElAlert
      v-if="auth.readOnly"
      :title="VIEW_FENCE"
      type="info"
      show-icon
      :closable="false"
      class="page-alert"
    />
    <ElAlert
      v-if="!allowed"
      title="只有工作空间所有者可以管理成员；你仍可在角色权限页查看权限说明。"
      type="info"
      show-icon
      :closable="false"
    />
    <template v-else>
      <ListPanel
        v-model:query="list.query"
        v-model:page="list.page"
        v-model:page-size="list.pageSize"
        :total="list.total"
        :loading="list.loading"
        :error="list.error"
        placeholder="搜索姓名或邮箱"
        empty-title="还没有成员"
        empty-hint="邀请同事加入这个工作空间，按角色分配权限。"
        @search="list.search"
        @refresh="list.load"
      >
        <template #empty-action>
          <ElButton v-if="writable" type="primary" :icon="Plus" @click="open(null)"
            >添加成员</ElButton
          >
        </template>
        <ElTable :data="list.items" row-key="id">
          <ElTableColumn label="成员" min-width="240"
            ><template #default="{ row }"
              ><div class="cell-title">
                {{ row.displayName }}
                <ElTag v-if="row.id === auth.user?.id" size="small" type="info">你</ElTag>
              </div>
              <div class="cell-subtitle">{{ row.email }}</div></template
            ></ElTableColumn
          >
          <ElTableColumn label="角色" width="130"
            ><template #default="{ row }">{{ roleLabel(row.role) }}</template></ElTableColumn
          >
          <ElTableColumn label="状态" width="110"
            ><template #default="{ row }"
              ><ElTag :type="row.disabledAt ? 'info' : 'success'">{{
                row.disabledAt ? "已停用" : "正常"
              }}</ElTag></template
            ></ElTableColumn
          >
          <ElTableColumn label="加入时间" min-width="170"
            ><template #default="{ row }">{{ dateTime(row.createTime) }}</template></ElTableColumn
          >
          <ElTableColumn label="操作" width="180" fixed="right"
            ><template #default="{ row }"
              ><ElButton
                link
                type="primary"
                :disabled="!writable || saving"
                @click="open(row as Member)"
                >编辑</ElButton
              ><ElButton
                link
                :type="row.disabledAt ? 'primary' : 'danger'"
                :disabled="!writable || row.id === auth.user?.id || saving"
                @click="toggle(row as Member)"
                >{{ row.disabledAt ? "恢复" : "停用" }}</ElButton
              ></template
            ></ElTableColumn
          >
        </ElTable>
      </ListPanel>
      <p class="form-hint">
        不能停用或降级自己，工作空间至少保留一位有效所有者。成员权限不包含平台运营权限。
      </p>
    </template>
    <ElDrawer
      v-model="drawer"
      :title="target ? '编辑成员' : '添加成员'"
      size="440px"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      @closed="form.password = ''"
    >
      <ElAlert
        v-if="error"
        :title="error"
        type="error"
        show-icon
        :closable="false"
        class="page-alert"
      />
      <ElForm label-position="top" @submit.prevent="save">
        <ElFormItem label="姓名" for="member-name" required
          ><ElInput id="member-name" v-model="form.displayName" maxlength="80"
        /></ElFormItem>
        <ElFormItem label="邮箱" for="member-email" required
          ><ElInput
            id="member-email"
            v-model="form.email"
            type="email"
            :disabled="!!target"
            autocomplete="off"
            maxlength="254"
        /></ElFormItem>
        <ElFormItem v-if="!target" label="初始密码" for="member-password" required
          ><ElInput
            id="member-password"
            v-model="form.password"
            type="password"
            show-password
            autocomplete="new-password"
            maxlength="128"
          />
          <p class="form-hint">
            至少 {{ MIN_PASSWORD_LENGTH }} 位。请通过安全渠道交给成员，并提醒登录后修改。
          </p></ElFormItem
        >
        <ElFormItem label="角色" for="member-role"
          ><ElSelect
            id="member-role"
            v-model="form.role"
            :disabled="target?.id === auth.user?.id"
            style="width: 100%"
            ><ElOption
              v-for="role in ROLES"
              :key="role"
              :value="role"
              :label="roleLabel(role)" /></ElSelect
        ></ElFormItem>
        <div class="form-actions">
          <ElButton type="primary" native-type="submit" :loading="saving">{{
            target ? "保存成员" : "添加成员"
          }}</ElButton
          ><ElButton :disabled="saving" @click="drawer = false">取消</ElButton>
        </div>
      </ElForm>
    </ElDrawer>
  </section>
</template>
