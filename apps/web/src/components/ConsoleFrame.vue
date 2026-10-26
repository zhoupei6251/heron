<script setup lang="ts">
import {
  ArrowRight,
  Connection,
  DataAnalysis,
  DocumentChecked,
  FolderOpened,
  House,
  Key,
  Monitor,
  OfficeBuilding,
  Promotion,
  Setting,
  SwitchButton,
  Tickets,
  User,
  UserFilled,
  VideoCamera,
  View,
} from "@element-plus/icons-vue";
import { ElAvatar, ElButton, ElIcon, ElTag } from "element-plus";
import { computed } from "vue";
import { RouterLink, useRoute } from "vue-router";
import ThemeSwitcher from "./ThemeSwitcher.vue";
import CommandPalette from "./CommandPalette.vue";

const props = defineProps<{
  platform?: boolean;
  workspace: string;
  name: string;
  role: string;
  connected?: boolean;
  signingOut: boolean;
}>();
defineEmits<{ logout: [] }>();
const route = useRoute();
const groups = computed(() =>
  props.platform
    ? [
        {
          label: "平台运营",
          items: [
            { to: "/platform", label: "运营概览", icon: House },
            { to: "/platform/tenants", label: "租户管理", icon: OfficeBuilding },
            { to: "/platform/usage", label: "全局用量", icon: DataAnalysis },
            { to: "/platform/audit", label: "平台审计", icon: DocumentChecked },
            { to: "/platform/health", label: "服务健康", icon: Monitor },
          ],
        },
      ]
    : [
        {
          label: "内容工作室",
          items: [
            { to: "/overview", label: "工作概览", icon: House },
            { to: "/candidates", label: "候选管理", icon: Tickets },
            { to: "/production", label: "生产任务", icon: VideoCamera },
            { to: "/review", label: "审片工作台", icon: View },
            { to: "/publish", label: "发布记录", icon: Promotion },
            { to: "/library", label: "成片库", icon: FolderOpened },
          ],
        },
        {
          label: "工作空间",
          items: [
            { to: "/members", label: "成员管理", icon: User },
            { to: "/roles", label: "角色与权限", icon: Key },
            { to: "/feeds", label: "数据源配置", icon: Connection },
            { to: "/settings", label: "工作空间设置", icon: Setting },
          ],
        },
        {
          label: "安全与治理",
          items: [
            { to: "/usage", label: "用量统计", icon: DataAnalysis },
            { to: "/audit", label: "操作审计", icon: DocumentChecked },
            { to: "/profile", label: "个人与安全", icon: UserFilled },
          ],
        },
      ],
);
const commandItems = computed(() =>
  groups.value.flatMap((group) =>
    group.items.map((item) => ({ to: item.to, label: item.label, group: group.label })),
  ),
);
const currentTitle = computed(
  () =>
    groups.value.flatMap((group) => group.items).find((item) => item.to === route.path)?.label ??
    "工作台",
);
</script>

<template>
  <div class="console" :class="{ 'console-platform': platform }">
    <a class="skip-link" href="#main-content">跳转到页面内容</a>
    <aside class="console-sidebar">
      <RouterLink :to="platform ? '/platform' : '/overview'" class="brand" aria-label="heron 首页">
        <svg viewBox="0 0 32 36" fill="none" aria-hidden="true" class="brand-mark">
          <path
            d="M5 27 15 10 24 6 19 15 28 28M15 10 15 27M6 28h21M19 15l-4 12"
            stroke="currentColor"
            stroke-width="2.2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        <span
          >heron<span class="brand-caption">{{
            platform ? "PLATFORM" : "CONTENT STUDIO"
          }}</span></span
        >
      </RouterLink>
      <div class="workspace-badge">
        <span class="workspace-symbol">{{ platform ? "P" : workspace.slice(0, 1) }}</span>
        <div>
          <strong>{{ workspace }}</strong
          ><span>{{ platform ? "独立平台身份" : "你的内容生产空间" }}</span>
        </div>
      </div>
      <nav class="console-nav" :aria-label="platform ? '平台导航' : '工作空间导航'">
        <div v-for="group in groups" :key="group.label" class="nav-group">
          <p class="nav-label">{{ group.label }}</p>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            class="nav-link"
            :class="{ 'is-active': route.path === item.to }"
            :aria-current="route.path === item.to ? 'page' : undefined"
          >
            <ElIcon><component :is="item.icon" /></ElIcon><span>{{ item.label }}</span>
          </RouterLink>
        </div>
      </nav>
      <div class="sidebar-foot">
        <span class="status-dot"></span>
        {{ platform ? "运营与内容权限相互隔离" : "每一次发布，都经过你的确认" }}
      </div>
    </aside>
    <div class="console-body">
      <header class="console-topbar">
        <div class="breadcrumb">
          <span>{{ platform ? "平台控制台" : "工作空间" }}</span
          ><ElIcon><ArrowRight /></ElIcon><strong>{{ currentTitle }}</strong>
        </div>
        <div class="topbar-actions">
          <CommandPalette :items="commandItems" />
          <ThemeSwitcher />
          <span v-if="!platform" class="connection-status"
            ><span class="status-dot" :class="{ offline: !connected }"></span
            >{{ connected ? "实时同步" : "连接中断" }}</span
          >
          <ElTag size="small" effect="plain">{{ role }}</ElTag>
          <RouterLink
            v-if="!platform"
            to="/profile"
            class="identity-link"
            :aria-label="`${name}，个人设置`"
            ><ElAvatar :size="30">{{ name.slice(0, 1) }}</ElAvatar
            ><span>{{ name }}</span></RouterLink
          >
          <span v-else class="identity-link"
            ><ElAvatar :size="30">{{ name.slice(0, 1) }}</ElAvatar
            ><span>{{ name }}</span></span
          >
          <ElButton
            text
            :icon="SwitchButton"
            :loading="signingOut"
            aria-label="退出登录"
            title="退出登录"
            @click="$emit('logout')"
          />
        </div>
      </header>
      <main id="main-content" class="console-content" tabindex="-1"><slot /></main>
      <footer class="console-footer">
        <span>heron / {{ platform ? "平台运营" : "内容工作室" }}</span
        ><span>让创作流动，让发布有据。</span>
      </footer>
    </div>
  </div>
</template>
