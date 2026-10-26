<script setup lang="ts">
import { CircleCheck, Minus } from "@element-plus/icons-vue";
import { ROLES } from "@heron/contracts";
import { ACTIONS, can, type Action } from "@heron/domain";
import { ElAlert, ElIcon, ElTable, ElTableColumn, ElTag } from "element-plus";

import { roleLabel } from "../../auth/roles";
import { useAuthStore } from "../../stores/auth";

const auth = useAuthStore();
const labels: Record<Action, string> = {
  "task.claim": "认领生产任务",
  "review.confirm": "确认审核成片",
  "settings.write": "修改工作空间设置与数据源",
  "aigc.set": "管理 AI 内容声明",
  "user.manage": "创建成员与分配角色",
  "usage.read": "查看内容与用量",
};
const rows = ACTIONS.map((action) => ({ action, label: labels[action] }));
</script>

<template>
  <section>
    <div class="page-header">
      <div>
        <span class="eyebrow">WORKSPACE / ACCESS</span>
        <h1>角色与权限</h1>
        <p class="page-description">明确分工，让每个人拥有恰当的操作权限。</p>
      </div>
      <ElTag v-if="auth.user" effect="plain">当前角色 · {{ roleLabel(auth.user.role) }}</ElTag>
    </div>
    <ElAlert
      class="page-alert"
      type="info"
      :closable="false"
      show-icon
      title="权限由服务端校验；工作空间所有者也不能访问其他租户或平台管理权限。"
    />
    <div class="panel">
      <div class="panel-header">
        <h2>工作空间权限矩阵</h2>
        <span class="muted">5 种固定角色</span>
      </div>
      <ElTable :data="rows">
        <ElTableColumn prop="label" label="权限" min-width="210" />
        <ElTableColumn
          v-for="role in ROLES"
          :key="role"
          :label="roleLabel(role)"
          min-width="110"
          align="center"
        >
          <template #default="{ row }">
            <span
              :aria-label="can(role, row.action) ? '允许' : '不允许'"
              :class="can(role, row.action) ? 'permission-yes' : 'muted'"
            >
              <ElIcon><CircleCheck v-if="can(role, row.action)" /><Minus v-else /></ElIcon>
            </span>
          </template>
        </ElTableColumn>
      </ElTable>
    </div>
    <div class="panel panel-body">
      <h3>如何调整成员权限</h3>
      <p class="page-description">
        所有者可在成员管理中调整角色。不能停用或降级自己，也不能移除最后一位有效所有者；所有成员变更都会写入审计记录。
      </p>
    </div>
  </section>
</template>

<style scoped>
.permission-yes {
  color: var(--heron-accent);
  font-size: 17px;
}
</style>
