<script setup lang="ts">
import { ArrowRight, Search } from "lucide-vue-next";
import { ElDialog } from "element-plus";
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from "vue";
import { useRouter } from "vue-router";

type CommandItem = { to: string; label: string; group: string };
const props = defineProps<{ items: CommandItem[] }>();
const router = useRouter();
const id = `command-${useId()}`;
const visible = ref(false);
const query = ref("");
const activeIndex = ref(0);
const trigger = ref<HTMLButtonElement>();
const input = ref<HTMLInputElement>();
const list = ref<HTMLElement>();
const matches = computed(() => {
  const term = query.value.trim().toLowerCase();
  return props.items.filter(
    (item) =>
      item.to.startsWith("/") &&
      !item.to.startsWith("//") &&
      !item.to.includes("\\") &&
      [item.label, item.group, item.to].some((value) => value.toLowerCase().includes(term)),
  );
});
const activeId = computed(() =>
  matches.value[activeIndex.value] ? `${id}-option-${activeIndex.value}` : undefined,
);

function anotherModalVisible() {
  // Include confirmations and drawers, but ignore hidden, retained dialog DOM.
  return [
    ...document.querySelectorAll<HTMLElement>(
      '[aria-modal="true"], [role="dialog"], [role="alertdialog"], dialog[open], .el-drawer',
    ),
  ].some((modal) => {
    if (input.value && modal.contains(input.value)) return false;
    for (let node: HTMLElement | null = modal; node; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (
        node.hidden ||
        style.display === "none" ||
        ["hidden", "collapse"].includes(style.visibility)
      ) {
        return false;
      }
    }
    return true;
  });
}
function scrollActive() {
  list.value
    ?.querySelector<HTMLElement>('[aria-selected="true"]')
    ?.scrollIntoView?.({ block: "nearest" });
}
function focusSearch() {
  // A confirmation may have opened while the palette's enter transition was running.
  if (!visible.value || anotherModalVisible()) return;
  input.value?.focus();
  scrollActive();
}
function open(fromTrigger = false) {
  if (anotherModalVisible()) return;
  if (visible.value) {
    focusSearch();
    return;
  }
  // Ensure pointer activation also gives Element Plus a concrete return-focus target.
  if (fromTrigger) trigger.value?.focus();
  query.value = "";
  activeIndex.value = 0;
  visible.value = true;
}
function close() {
  visible.value = false;
}
function composing(event: KeyboardEvent) {
  return event.isComposing || event.keyCode === 229;
}
function onShortcut(event: KeyboardEvent) {
  if (
    event.defaultPrevented ||
    event.repeat ||
    composing(event) ||
    event.altKey ||
    !(event.ctrlKey || event.metaKey) ||
    event.key.toLowerCase() !== "k" ||
    anotherModalVisible()
  ) {
    return;
  }
  event.preventDefault();
  open();
}
function navigate(item?: CommandItem) {
  if (!visible.value || !item || !matches.value.includes(item) || anotherModalVisible()) return;
  close();
  return router.push(item.to);
}
function onSearchKey(event: KeyboardEvent) {
  if (composing(event)) {
    // Escape must cancel composition without reaching the dialog's document listener.
    if (event.key === "Escape") event.stopPropagation();
    return;
  }
  if (event.altKey || event.ctrlKey || event.metaKey) return;
  const count = matches.value.length;
  if (event.key === "Enter") {
    event.preventDefault();
    return navigate(matches.value[activeIndex.value]);
  }
  if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
  event.preventDefault();
  if (!count) return;
  if (event.key === "Home") activeIndex.value = 0;
  else if (event.key === "End") activeIndex.value = count - 1;
  else {
    activeIndex.value = (activeIndex.value + (event.key === "ArrowDown" ? 1 : -1) + count) % count;
  }
}

watch(
  query,
  () => {
    activeIndex.value = 0;
  },
  { flush: "sync" },
);
// Identity replacement matters even when a new scope has identical navigation labels.
watch(() => props.items, close, { deep: true, flush: "sync" });
watch(() => router.currentRoute.value.fullPath, close, { flush: "sync" });
watch([activeIndex, matches], scrollActive, { flush: "post" });
onMounted(() => document.addEventListener("keydown", onShortcut));
onBeforeUnmount(() => document.removeEventListener("keydown", onShortcut));
</script>

<template>
  <button
    ref="trigger"
    type="button"
    class="command-trigger"
    aria-label="快捷导航"
    aria-haspopup="dialog"
    :aria-expanded="visible"
    :aria-controls="`${id}-dialog`"
    aria-keyshortcuts="Control+K Meta+K"
    title="快捷导航（Ctrl / Cmd + K）"
    @click="open(true)"
  >
    <Search class="command-icon" aria-hidden="true" />
    <span class="command-trigger-label">快捷导航</span>
    <kbd aria-hidden="true">Ctrl / ⌘ K</kbd>
  </button>
  <ElDialog
    :id="`${id}-dialog`"
    v-model="visible"
    title="快捷导航"
    width="min(600px, calc(100vw - 32px))"
    top="12vh"
    class="command-dialog"
    :style="{
      '--el-dialog-bg-color': 'var(--heron-surface)',
      '--el-text-color-primary': 'var(--heron-ink)',
      '--el-text-color-regular': 'var(--heron-ink)',
      '--el-text-color-secondary': 'var(--heron-muted)',
      '--el-color-primary': 'var(--heron-accent)',
      borderRadius: '16px',
    }"
    append-to-body
    destroy-on-close
    @opened="focusSearch"
    @close="close"
  >
    <div class="command-content">
      <label :for="`${id}-search`" class="command-label">搜索页面</label>
      <div class="command-search">
        <Search class="command-icon" aria-hidden="true" />
        <input
          :id="`${id}-search`"
          ref="input"
          v-model="query"
          type="text"
          role="combobox"
          autocomplete="off"
          :spellcheck="false"
          aria-autocomplete="list"
          :aria-expanded="visible"
          :aria-controls="`${id}-list`"
          :aria-activedescendant="activeId"
          :aria-describedby="`${id}-help`"
          placeholder="输入页面名称、分组或路径…"
          @keydown="onSearchKey"
        />
      </div>
      <div class="command-meta" role="status" aria-live="polite">
        {{
          matches.length ? `当前导航 · ${matches.length} 个页面` : "没有匹配的页面，请换个关键词"
        }}
      </div>
      <ul :id="`${id}-list`" ref="list" class="command-list" role="listbox" aria-label="可访问页面">
        <li
          v-for="(item, index) in matches"
          :id="`${id}-option-${index}`"
          :key="item.to"
          role="option"
          :aria-selected="index === activeIndex"
          class="command-option"
          @mouseenter="activeIndex = index"
          @mousedown.prevent
          @click="navigate(item)"
        >
          <span class="command-option-text">
            <span class="command-option-label">{{ item.label }}</span>
            <span class="command-path">{{ item.to }}</span>
          </span>
          <span class="command-group">{{ item.group }}</span>
          <ArrowRight class="command-icon command-arrow" aria-hidden="true" />
        </li>
      </ul>
      <div :id="`${id}-help`" class="command-help">
        <span><kbd>↑</kbd><kbd>↓</kbd> 选择 <kbd>Enter</kbd> 前往</span>
        <span><kbd>Esc</kbd> 关闭</span>
      </div>
    </div>
  </ElDialog>
</template>

<style scoped>
.command-trigger {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-height: 40px;
  min-width: 40px;
  padding: 0 11px;
  border: 1px solid var(--heron-line);
  border-radius: 9px;
  background: var(--heron-surface);
  color: var(--heron-muted);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.command-trigger:hover {
  border-color: var(--heron-accent);
  color: var(--heron-ink);
  background: var(--heron-surface-soft);
}
.command-trigger:focus-visible {
  outline: 2px solid var(--heron-accent);
  outline-offset: 3px;
}
.command-icon {
  flex: 0 0 17px;
  width: 17px;
  height: 17px;
}
.command-content {
  color: var(--heron-ink);
}
.command-label {
  display: block;
  margin-bottom: 8px;
  color: var(--heron-muted);
  font-size: 12px;
}
.command-search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 13px;
  border: 1px solid var(--heron-line);
  border-radius: 10px;
  background: var(--heron-surface-soft);
  color: var(--heron-muted);
}
.command-search:focus-within {
  outline: 2px solid var(--heron-accent);
  outline-offset: 2px;
}
.command-search input {
  width: 100%;
  min-width: 0;
  height: 46px;
  padding: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--heron-ink);
  font: inherit;
  font-size: 16px;
}
.command-search input::placeholder {
  color: var(--heron-muted);
}
.command-meta {
  padding: 16px 3px 8px;
  color: var(--heron-muted);
  font-size: 12px;
}
.command-list {
  max-height: min(360px, 45dvh);
  overflow-y: auto;
  overscroll-behavior: contain;
  margin: 0;
  padding: 3px;
  list-style: none;
}
.command-option {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 58px;
  margin-bottom: 3px;
  padding: 8px 12px;
  border: 1px solid transparent;
  border-radius: 9px;
  cursor: pointer;
}
.command-option[aria-selected="true"] {
  border-color: var(--heron-accent);
  background: var(--heron-surface-soft);
  box-shadow: inset 3px 0 var(--heron-accent);
}
.command-option-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.command-option-label {
  font-weight: 600;
}
.command-path {
  overflow: hidden;
  color: var(--heron-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.command-group {
  color: var(--heron-muted);
  font-size: 11px;
}
.command-arrow {
  color: var(--heron-accent);
  opacity: 0;
}
.command-option[aria-selected="true"] .command-arrow {
  opacity: 1;
}
.command-help {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--heron-line);
  color: var(--heron-muted);
  font-size: 11px;
}
kbd {
  display: inline-block;
  padding: 1px 4px;
  border: 1px solid var(--heron-line);
  border-radius: 4px;
  background: var(--heron-surface-soft);
  color: var(--heron-muted);
  font: inherit;
  font-size: 10px;
}
@media (max-width: 640px) {
  .command-trigger {
    justify-content: center;
    padding: 0;
  }
  .command-trigger-label,
  .command-trigger kbd {
    display: none;
  }
  .command-option {
    gap: 8px;
    padding-inline: 9px;
  }
  .command-group {
    max-width: 90px;
    text-align: right;
  }
}
</style>
