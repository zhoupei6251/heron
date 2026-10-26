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
import { computed, type Component } from "vue";
import { RouterLink, useRoute } from "vue-router";

import type { ConsoleMenu } from "@heron/contracts";

import { roleLabel } from "../auth/roles";
import { useAuthStore } from "../stores/auth";
import CommandPalette from "./CommandPalette.vue";
import ThemeSwitcher from "./ThemeSwitcher.vue";

const props = defineProps<{ connected?: boolean; signingOut: boolean }>();
defineEmits<{ logout: [] }>();

const auth = useAuthStore();
const route = useRoute();

/** 图标是纯展示，不进契约：后端只管 id / 路径 / 文案 / 分组。 */
const ICONS: Record<ConsoleMenu["id"], Component> = {
  overview: House,
  candidates: Tickets,
  production: VideoCamera,
  review: View,
  publish: Promotion,
  library: FolderOpened,
  members: User,
  roles: Key,
  feeds: Connection,
  settings: Setting,
  usage: DataAnalysis,
  audit: DocumentChecked,
  profile: UserFilled,
  "platform-overview": House,
  tenants: OfficeBuilding,
  "platform-usage": DataAnalysis,
  "platform-audit": DocumentChecked,
  health: Monitor,
};

const platform = computed(() => auth.session?.kind === "platform");
const tenant = computed(() => (auth.session?.kind === "tenant" ? auth.session.user : null));
const workspace = computed(() => tenant.value?.tenantName ?? "heron 平台");
const name = computed(() => auth.session?.user.displayName ?? "");
const role = computed(() =>
  auth.session?.kind === "tenant" ? roleLabel(auth.session.user.role) : "平台管理员",
);
const home = computed(() => auth.menus[0]?.path ?? "/");
const profilePath = computed(() => auth.menus.find((menu) => menu.id === "profile")?.path);

/** 分组顺序跟后端下发顺序走，不在前端另排一套。 */
const groups = computed(() => {
  const result: { label: string; items: (ConsoleMenu & { icon: Component })[] }[] = [];
  for (const menu of auth.menus) {
    let group = result.find((item) => item.label === menu.group);
    if (!group) {
      group = { label: menu.group, items: [] };
      result.push(group);
    }
    group.items.push({ ...menu, icon: ICONS[menu.id] });
  }
  return result;
});
const commandItems = computed(() =>
  auth.menus.map((menu) => ({ to: menu.path, label: menu.label, group: menu.group })),
);
const currentTitle = computed(
  () => auth.menus.find((menu) => menu.path === route.path)?.label ?? "工作台",
);
</script>

<template>
  <div class="console" :class="{ 'console-platform': platform }">
    <a class="skip-link" href="#main-content">跳转到页面内容</a>
    <aside class="console-sidebar">
      <RouterLink :to="home" class="brand" aria-label="heron 首页">
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
          ><span>{{ platform ? "运营与内容权限相互隔离" : "你的内容生产空间" }}</span>
        </div>
      </div>
      <nav class="console-nav" :aria-label="platform ? '平台导航' : '工作空间导航'">
        <div v-for="group in groups" :key="group.label" class="nav-group">
          <p class="nav-label">{{ group.label }}</p>
          <RouterLink
            v-for="item in group.items"
            :key="item.id"
            :to="item.path"
            class="nav-link"
            :class="{ 'is-active': route.path === item.path }"
            :aria-current="route.path === item.path ? 'page' : undefined"
          >
            <ElIcon><component :is="item.icon" /></ElIcon><span>{{ item.label }}</span>
          </RouterLink>
        </div>
      </nav>
      <div class="sidebar-foot">
        <span class="status-dot"></span>
        {{ platform ? "平台账号看不到任何租户内容" : "每一次发布，都经过你的确认" }}
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
            ><span class="status-dot" :class="{ offline: !props.connected }"></span
            >{{ props.connected ? "实时同步" : "连接中断" }}</span
          >
          <ElTag size="small" effect="plain">{{ role }}</ElTag>
          <RouterLink
            v-if="profilePath"
            :to="profilePath"
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
