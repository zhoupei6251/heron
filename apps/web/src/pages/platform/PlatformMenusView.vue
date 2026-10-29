<script setup lang="ts">
import {
  DEFAULT_CONSOLE_MENU_CONFIG,
  MENU_GROUP_ICONS,
  MENU_PAGES,
  TENANT_MENU_IDS,
  type ConsoleMenuConfig,
  type ConsoleMenuConfigView,
  type ConsoleMenuGroupConfig,
  type MenuGroupIcon,
  type TenantMenuId,
} from "@heron/contracts";
import { allowedMenuRoles, MENU_ACTIONS, requiredRoleFor } from "@heron/domain";
import { ArrowDown, ArrowUp, Plus, RotateCcw, Trash2 } from "lucide-vue-next";
import {
  ElAlert,
  ElButton,
  ElInput,
  ElMessage,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTag,
} from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";

import { errorMessage, request } from "../../api/http";
import { roleLabel } from "../../auth/roles";

const ICON_LABELS: Record<MenuGroupIcon, string> = {
  newspaper: "新闻 / 内容",
  layout: "版式 / 原型",
  settings: "设置",
  monitor: "监控",
  operations: "运营",
  configuration: "配置",
  folder: "文件夹",
};

const draft = ref<ConsoleMenuConfig>(structuredClone(DEFAULT_CONSOLE_MENU_CONFIG));
const saved = ref<ConsoleMenuConfig>(structuredClone(DEFAULT_CONSOLE_MENU_CONFIG));
const isDefault = ref(true);
const loading = ref(false);
const saving = ref(false);
const error = ref<string | null>(null);
const pending = reactive<Record<string, TenantMenuId | "">>({});

const dirty = computed(() => JSON.stringify(draft.value) !== JSON.stringify(saved.value));
const assigned = computed(
  () => new Set(draft.value.groups.flatMap((group) => group.items.map((item) => item.id))),
);
const available = computed(() =>
  TENANT_MENU_IDS.filter((id) => !assigned.value.has(id)).map((id) => ({
    id,
    label: MENU_PAGES[id].label,
  })),
);

function apply(view: ConsoleMenuConfigView): void {
  draft.value = structuredClone(view.config);
  saved.value = structuredClone(view.config);
  isDefault.value = view.isDefault;
  for (const key of Object.keys(pending)) delete pending[key];
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    apply(await request<ConsoleMenuConfigView>("/platform/menus"));
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    loading.value = false;
  }
}

async function save(): Promise<void> {
  saving.value = true;
  error.value = null;
  try {
    const view = await request<ConsoleMenuConfigView>("/platform/menus", {
      method: "PUT",
      body: JSON.stringify(draft.value),
    });
    apply(view);
    ElMessage.success("菜单配置已保存，成员下次刷新即可看到");
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    saving.value = false;
  }
}

function resetToDefault(): void {
  draft.value = structuredClone(DEFAULT_CONSOLE_MENU_CONFIG);
  ElMessage.info("已载入默认菜单，点击保存后生效");
}

function addModule(): void {
  let index = draft.value.groups.length + 1;
  while (draft.value.groups.some((group) => group.key === `module-${index}`)) index += 1;
  draft.value.groups.push({
    key: `module-${index}`,
    label: "新模块",
    icon: "folder",
    items: [],
  });
}

function removeModule(index: number): void {
  draft.value.groups.splice(index, 1);
}

function addItem(group: ConsoleMenuGroupConfig): void {
  const id = pending[group.key];
  if (!id) return;
  group.items.push({ id, label: MENU_PAGES[id].label, hidden: false, roles: [] });
  pending[group.key] = "";
}

function removeItem(group: ConsoleMenuGroupConfig, index: number): void {
  group.items.splice(index, 1);
}

/** 上移/下移只换相邻两项：没有拖拽依赖，键盘也能操作。 */
function move<T>(list: T[], index: number, delta: -1 | 1): void {
  const target = index + delta;
  if (target < 0 || target >= list.length) return;
  [list[index], list[target]] = [list[target]!, list[index]!];
}

function floorHint(id: TenantMenuId): string {
  const action = MENU_ACTIONS[id];
  return action ? `留空 = ${roleLabel(requiredRoleFor(action))}及以上` : "留空 = 所有角色";
}

onMounted(load);
</script>

<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Platform / navigation</span>
        <h1>菜单配置</h1>
        <p class="page-description">
          大模块负责分组，子菜单对应代码里已有的页面。配置改动对所有工作空间生效，只管「放哪、叫什么、给谁看」。
        </p>
      </div>
      <div class="page-actions">
        <ElButton :icon="RotateCcw" :disabled="saving" @click="resetToDefault">恢复默认</ElButton>
        <ElButton type="primary" :loading="saving" :disabled="!dirty" @click="save">保存</ElButton>
      </div>
    </header>
    <ElAlert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="page-alert"
    />
    <ElAlert
      type="info"
      :closable="false"
      show-icon
      title="页面来自代码白名单：这里新增不了任意路径，只能从已有页面里挑。可见角色只做收窄，不能越过页面的动作权限。"
      class="page-alert"
    />

    <div class="panel">
      <div class="panel-header">
        <h2>侧栏结构</h2>
        <ElTag :type="isDefault ? 'info' : 'success'">
          {{ loading ? "读取中" : isDefault ? "产品默认" : "已自定义" }}
        </ElTag>
      </div>
      <div class="panel-body menu-editor">
        <article v-for="(group, groupIndex) in draft.groups" :key="group.key" class="menu-module">
          <header class="module-head">
            <ElSelect v-model="group.icon" class="icon-select" aria-label="模块图标">
              <ElOption
                v-for="icon in MENU_GROUP_ICONS"
                :key="icon"
                :label="ICON_LABELS[icon]"
                :value="icon"
              />
            </ElSelect>
            <ElInput v-model="group.label" maxlength="24" aria-label="模块名称" />
            <span class="module-key mono">{{ group.key }}</span>
            <div class="row-actions">
              <ElButton
                text
                :icon="ArrowUp"
                :disabled="groupIndex === 0"
                aria-label="模块上移"
                @click="move(draft.groups, groupIndex, -1)"
              />
              <ElButton
                text
                :icon="ArrowDown"
                :disabled="groupIndex === draft.groups.length - 1"
                aria-label="模块下移"
                @click="move(draft.groups, groupIndex, 1)"
              />
              <ElButton
                text
                type="danger"
                :icon="Trash2"
                aria-label="删除模块"
                @click="removeModule(groupIndex)"
              />
            </div>
          </header>

          <div v-if="group.items.length" class="module-items">
            <div v-for="(item, itemIndex) in group.items" :key="item.id" class="menu-item">
              <ElInput v-model="item.label" maxlength="24" aria-label="菜单名称" />
              <ElSelect
                v-model="item.roles"
                multiple
                collapse-tags
                class="role-select"
                :placeholder="floorHint(item.id)"
                aria-label="可见角色"
              >
                <ElOption
                  v-for="role in allowedMenuRoles(item.id)"
                  :key="role"
                  :label="roleLabel(role)"
                  :value="role"
                />
              </ElSelect>
              <ElSwitch
                v-model="item.hidden"
                class="hidden-switch"
                active-text="导航中隐藏"
                aria-label="在导航中隐藏"
              />
              <div class="row-actions">
                <ElButton
                  text
                  :icon="ArrowUp"
                  :disabled="itemIndex === 0"
                  aria-label="菜单上移"
                  @click="move(group.items, itemIndex, -1)"
                />
                <ElButton
                  text
                  :icon="ArrowDown"
                  :disabled="itemIndex === group.items.length - 1"
                  aria-label="菜单下移"
                  @click="move(group.items, itemIndex, 1)"
                />
                <ElButton
                  text
                  type="danger"
                  :icon="Trash2"
                  aria-label="移出模块"
                  @click="removeItem(group, itemIndex)"
                />
              </div>
            </div>
          </div>
          <p v-else class="module-empty">这个模块还没有菜单，从下面挑一个页面进来。</p>

          <footer class="module-add">
            <ElSelect
              v-model="pending[group.key]"
              placeholder="选择要加入的页面"
              class="page-select"
              aria-label="选择要加入的页面"
            >
              <ElOption
                v-for="page in available"
                :key="page.id"
                :label="page.label"
                :value="page.id"
              />
            </ElSelect>
            <ElButton :icon="Plus" :disabled="!pending[group.key]" @click="addItem(group)">
              添加页面
            </ElButton>
          </footer>
        </article>

        <p v-if="draft.groups.length === 0" class="module-empty">
          还没有模块。点下面的「新增模块」从空白开始，或恢复默认菜单。
        </p>
        <ElButton class="add-module" :icon="Plus" @click="addModule">新增模块</ElButton>
      </div>
    </div>

    <div class="panel panel-body">
      <h3>配置边界</h3>
      <ul class="menu-rules">
        <li>模块与菜单的顺序就是侧栏顺序；页面路径与组件不开放配置。</li>
        <li>「在导航中隐藏」只收起入口，路由和权限仍然保留；个人设置默认隐藏，走右上角头像。</li>
        <li>每档角色至少要保留一个可见页面，否则保存会被拒绝——不能让某一档角色登录后无处可去。</li>
        <li>
          可见角色留空表示所有通过该页动作门槛的角色；改动写平台审计（console_menu.updated）。
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.menu-editor {
  display: grid;
  gap: 14px;
}

.menu-module {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  padding: 12px;
  background: var(--el-bg-color);
}

.module-head,
.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
}

.module-head {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.icon-select {
  width: 140px;
  flex-shrink: 0;
}

.module-key {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  flex-shrink: 0;
}

.module-items {
  display: grid;
  gap: 8px;
  padding: 10px 0 4px 18px;
  border-left: 1px solid var(--el-border-color-lighter);
  margin: 10px 0 0 10px;
}

.menu-item :deep(.el-input) {
  flex: 1;
}

.role-select {
  width: 230px;
  flex-shrink: 0;
}

.hidden-switch {
  flex-shrink: 0;
  font-size: 12px;
}

.row-actions {
  display: flex;
  flex-shrink: 0;
}

.row-actions :deep(.el-button) {
  margin-left: 2px;
}

.module-add {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}

.page-select {
  width: 240px;
}

.module-empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 10px 0 0;
}

.add-module {
  justify-self: start;
}

.menu-rules {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.8;
}

@media (max-width: 760px) {
  .module-head,
  .menu-item,
  .module-add {
    flex-wrap: wrap;
  }

  .icon-select,
  .role-select,
  .page-select {
    width: 100%;
  }

  .module-items {
    padding-left: 10px;
    margin-left: 0;
  }
}
</style>
