<script setup lang="ts">
import type { Page, PlatformTenant, PlatformAuditLog } from "@heron/contracts";
import {
  ArrowRight,
  OfficeBuilding,
  Tickets,
  DataAnalysis,
  Monitor,
} from "@element-plus/icons-vue";
import { ElAlert, ElButton, ElIcon, ElTable, ElTableColumn, ElTag } from "element-plus";
import { onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { request, errorMessage } from "../api/http";
import { dateTime } from "../format";
const tenants = ref<Page<PlatformTenant> | null>(null);
const audit = ref<PlatformAuditLog[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
async function load() {
  loading.value = true;
  error.value = null;
  try {
    const [spaces, entries] = await Promise.all([
      request<Page<PlatformTenant>>("/platform/tenants?page=1&pageSize=5"),
      request<Page<PlatformAuditLog>>("/platform/audit?page=1&pageSize=5"),
    ]);
    tenants.value = spaces;
    audit.value = entries.items;
  } catch (cause) {
    tenants.value = null;
    audit.value = [];
    error.value = errorMessage(cause);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Heron / platform operations</span>
        <h1>平台运营总览</h1>
        <p class="page-description">管理租户与运行状态，不代替任何工作空间的内容审核。</p>
      </div>
      <div class="page-actions"><ElButton :loading="loading" @click="load">刷新概览</ElButton></div>
    </header>
    <ElAlert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="page-alert"
    />
    <div class="platform-intro">
      <div>
        <span class="eyebrow">Independent workspaces</span>
        <h2>每个团队，一个独立的内容空间。</h2>
        <p>租户数据隔离，平台身份独立。开通、停用和恢复操作均记录审计。</p>
        <RouterLink to="/platform/tenants"
          ><ElButton type="primary"
            >管理工作空间 <ElIcon><ArrowRight /></ElIcon></ElButton
        ></RouterLink>
      </div>
      <div class="workspace-total">
        <span>已开通工作空间</span><strong>{{ tenants?.total ?? "—" }}</strong
        ><small>包含已停用空间</small>
      </div>
    </div>
    <div class="platform-shortcuts">
      <RouterLink to="/platform/tenants"
        ><ElIcon><OfficeBuilding /></ElIcon>
        <div><strong>租户管理</strong><span>开通与访问控制</span></div>
        <ElIcon><ArrowRight /></ElIcon></RouterLink
      ><RouterLink to="/platform/usage"
        ><ElIcon><DataAnalysis /></ElIcon>
        <div><strong>全局用量</strong><span>查看实际资源账本</span></div>
        <ElIcon><ArrowRight /></ElIcon></RouterLink
      ><RouterLink to="/platform/audit"
        ><ElIcon><Tickets /></ElIcon>
        <div><strong>操作审计</strong><span>追溯平台管理变更</span></div>
        <ElIcon><ArrowRight /></ElIcon></RouterLink
      ><RouterLink to="/platform/health"
        ><ElIcon><Monitor /></ElIcon>
        <div><strong>服务健康</strong><span>运行状态与生产门禁</span></div>
        <ElIcon><ArrowRight /></ElIcon
      ></RouterLink>
    </div>
    <div class="split-grid">
      <div class="panel">
        <div class="panel-header">
          <h2>最近开通</h2>
          <RouterLink to="/platform/tenants"
            ><ElButton link type="primary">全部租户</ElButton></RouterLink
          >
        </div>
        <ElTable
          :data="tenants?.items ?? []"
          :empty-text="loading ? '正在读取…' : '尚未开通工作空间'"
          ><ElTableColumn label="工作空间" min-width="180"
            ><template #default="{ row }"
              ><div class="cell-title">{{ row.name }}</div>
              <div class="cell-subtitle mono">{{ row.slug }}</div></template
            ></ElTableColumn
          ><ElTableColumn label="状态" width="110"
            ><template #default="{ row }"
              ><ElTag :type="row.suspendedAt ? 'danger' : 'success'">{{
                row.suspendedAt ? "已停用" : "正常"
              }}</ElTag></template
            ></ElTableColumn
          ><ElTableColumn label="开通时间" min-width="170"
            ><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></ElTableColumn
          ></ElTable
        >
      </div>
      <div class="panel">
        <div class="panel-header">
          <h2>最近操作</h2>
          <RouterLink to="/platform/audit"
            ><ElButton link type="primary">查看审计</ElButton></RouterLink
          >
        </div>
        <div class="panel-body">
          <div v-for="entry in audit" :key="entry.id" class="audit-item">
            <strong>{{ entry.operatorName ?? "已删除的账号" }}</strong>
            <p class="mono">{{ entry.action }}</p>
            <span>{{ dateTime(entry.createdAt) }}</span>
          </div>
          <p v-if="!audit.length" class="form-hint">
            {{ loading ? "正在读取…" : "还没有平台管理操作记录。" }}
          </p>
        </div>
      </div>
    </div>
  </section>
</template>
<style scoped>
.platform-intro {
  display: flex;
  justify-content: space-between;
  gap: 25px;
  background: radial-gradient(ellipse at 90% 10%, #47818a40, transparent 65%), #183c40;
  color: #f0fbf8;
  border: 1px solid #477578;
  border-radius: 16px;
  padding: 34px;
  margin-bottom: 24px;
}
.platform-intro .eyebrow {
  color: #acd4d1;
}
.platform-intro h2 {
  font-family: var(--heron-display);
  font-size: clamp(22px, 2vw, 30px);
  font-weight: 550;
  max-width: 540px;
}
.platform-intro p {
  font-size: 12px;
  line-height: 1.8;
  color: #bedbd5;
}
.workspace-total {
  min-width: 155px;
  border-left: 1px solid #628b8766;
  padding-left: 30px;
  display: flex;
  flex-direction: column;
  color: #bedbd5;
  font-size: 11px;
}
.workspace-total strong {
  font:
    46px/1.7 Bahnschrift,
    sans-serif;
  color: #e7fff2;
  font-size: 64px;
}
.workspace-total small {
  font-size: 10px;
}
.platform-shortcuts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 25px;
}
.platform-shortcuts > a {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--heron-surface);
  border: 1px solid var(--heron-line);
  border-radius: 12px;
  padding: 21px 17px;
  box-shadow: var(--heron-shadow);
  transition:
    border-color 160ms,
    transform 160ms;
}
.platform-shortcuts > a:hover {
  border-color: var(--heron-accent);
  transform: translateY(-3px);
}
.platform-shortcuts .el-icon {
  font-size: 21px;
  color: var(--heron-accent);
  flex-shrink: 0;
}
.platform-shortcuts .el-icon:last-child {
  margin-left: auto;
  font-size: 12px;
}
.platform-shortcuts strong {
  display: block;
  font-size: 13px;
  font-weight: 550;
}
.platform-shortcuts span {
  display: block;
  font-size: 10px;
  color: var(--heron-muted);
  margin-top: 6px;
}
.audit-item + .audit-item {
  border-top: 1px solid var(--heron-line);
  margin-top: 15px;
  padding-top: 15px;
}
.audit-item strong {
  font-size: 12px;
  font-weight: 550;
}
.audit-item p {
  color: var(--heron-accent);
  margin: 7px 0;
}
.audit-item span {
  font-size: 10px;
  color: var(--heron-muted);
}
@media (max-width: 1150px) {
  .platform-shortcuts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 760px) {
  .platform-intro {
    padding: 22px;
    flex-direction: column;
  }
  .workspace-total {
    padding: 15px 0 0;
    border-left: 0;
    border-top: 1px solid #628b8766;
  }
  .platform-shortcuts {
    gap: 9px;
  }
  .platform-shortcuts > a {
    padding: 15px 10px;
  }
  .platform-shortcuts .el-icon:last-child {
    display: none;
  }
}
</style>
