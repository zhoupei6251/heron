import { ElDialog, ElDrawer } from "element-plus";
import { createApp, h, nextTick, ref, type Component } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import CommandPalette from "./CommandPalette.vue";

type Item = { to: string; label: string; group: string };
const commands: Item[] = [
  { to: "/overview", label: "工作概览", group: "内容工作室" },
  { to: "/review", label: "审片工作台", group: "内容工作室" },
  { to: "/members", label: "成员管理", group: "工作空间 Team" },
];
const cleanups: (() => void)[] = [];

async function flush() {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await nextTick();
}
async function mount(initial = commands) {
  const items = ref(initial.map((item) => ({ ...item })));
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div />" } }],
  });
  await router.push("/start");
  await router.isReady();
  const root = document.createElement("div");
  document.body.append(root);
  // Match session-races: the lint TS program cannot resolve SFC exports.
  const component = CommandPalette as Component;
  const app = createApp({ render: () => h(component, { items: items.value }) }).use(router);
  app.mount(root);
  let mounted = true;
  const unmount = () => {
    if (mounted) app.unmount();
    mounted = false;
    root.remove();
  };
  cleanups.push(unmount);
  return { root, items, router, unmount };
}
function key(target: EventTarget, value: string, init: KeyboardEventInit = {}) {
  const event = new KeyboardEvent("keydown", {
    key: value,
    bubbles: true,
    cancelable: true,
    ...init,
  });
  target.dispatchEvent(event);
  return event;
}
function trigger(root: HTMLElement) {
  const button = root.querySelector<HTMLButtonElement>("button[aria-label='快捷导航']");
  expect(button, "palette trigger").not.toBeNull();
  return button!;
}
function search() {
  const input = document.querySelector<HTMLInputElement>("input[role='combobox']");
  expect(input, "search combobox").not.toBeNull();
  return input!;
}
function options() {
  return [...document.querySelectorAll<HTMLElement>("[role='option']")];
}
function active() {
  const input = search();
  const id = input.getAttribute("aria-activedescendant");
  const element = id ? document.getElementById(id) : null;
  expect(element?.getAttribute("aria-selected")).toBe("true");
  expect(options().filter((option) => option.getAttribute("aria-selected") === "true")).toEqual([
    element,
  ]);
  return element!;
}
async function open(root: HTMLElement) {
  trigger(root).focus();
  trigger(root).click();
  await vi.waitFor(() => expect(document.activeElement).toBe(search()));
}
async function query(value: string) {
  const input = search();
  input.value = value;
  input.dispatchEvent(new Event("input", { bubbles: true }));
  await nextTick();
}
async function closed(root: HTMLElement) {
  await vi.waitFor(() => {
    expect(trigger(root).getAttribute("aria-expanded")).toBe("false");
    expect(document.querySelector("input[role='combobox']")).toBeNull();
  });
}

afterEach(async () => {
  cleanups
    .splice(0)
    .reverse()
    .forEach((cleanup) => cleanup());
  vi.useRealTimers();
  await flush();
  document.body.replaceChildren();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("scoped command palette", () => {
  it.each([{ ctrlKey: true }, { metaKey: true }])(
    "opens with %j and labels the focused search",
    async (modifier) => {
      const view = await mount();
      trigger(view.root).focus();
      const event = key(document, "K", modifier);
      expect(event.defaultPrevented).toBe(true);
      await vi.waitFor(() => expect(document.activeElement).toBe(search()));
      expect(document.querySelector("[role='dialog'][aria-label='快捷导航']")).not.toBeNull();
      expect(search().labels?.[0]?.textContent).toContain("搜索页面");
      expect(search().getAttribute("aria-expanded")).toBe("true");
      expect(
        document.getElementById(search().getAttribute("aria-controls")!)?.getAttribute("role"),
      ).toBe("listbox");
      expect(active().textContent).toContain("工作概览");
    },
  );

  it("opens from its trigger with a discoverable shortcut hint", async () => {
    const view = await mount();
    expect(trigger(view.root).textContent).toMatch(/Ctrl.*K/);
    expect(trigger(view.root).textContent).toMatch(/⌘|Cmd/);
    await open(view.root);
    expect(options()).toHaveLength(3);
  });

  it.each([
    { ctrlKey: true, repeat: true },
    { metaKey: true, isComposing: true },
    { ctrlKey: true, keyCode: 229 },
    { ctrlKey: true, altKey: true },
    {},
  ])("does not consume an ineligible shortcut %j", async (modifier) => {
    const view = await mount();
    expect(key(document, "k", modifier).defaultPrevented).toBe(false);
    await flush();
    expect(trigger(view.root).getAttribute("aria-expanded")).toBe("false");
  });

  it.each([
    ["  审片  ", ["审片工作台"]],
    [" 内容工作室 ", ["工作概览", "审片工作台"]],
    [" tEAm ", ["成员管理"]],
    [" /REVIEW ", ["审片工作台"]],
    ["   ", ["工作概览", "审片工作台", "成员管理"]],
  ])("searches only supplied labels, groups and paths: %s", async (value, labels) => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    const view = await mount();
    await open(view.root);
    await query(value);
    expect(options()).toHaveLength(labels.length);
    labels.forEach((label, index) => expect(options()[index]?.textContent).toContain(label));
    expect(active().textContent).toContain(labels[0]);
    expect(document.body.textContent).not.toContain("租户管理");
    expect(fetch).not.toHaveBeenCalled();
  });

  it("wraps arrows, supports Home/End, scrolls selection and navigates with Enter", async () => {
    const view = await mount();
    await open(view.root);
    const last = options()[2]!;
    const scroll = vi.fn();
    last.scrollIntoView = scroll; // jsdom has no layout/scroll implementation.
    key(search(), "ArrowUp");
    await nextTick();
    expect(active()).toBe(last);
    expect(scroll).toHaveBeenCalledWith({ block: "nearest" });
    key(search(), "ArrowDown");
    await nextTick();
    expect(active().textContent).toContain("工作概览");
    key(search(), "End");
    await nextTick();
    expect(active()).toBe(last);
    key(search(), "Home");
    await nextTick();
    expect(active().textContent).toContain("工作概览");
    key(search(), "ArrowDown");
    await nextTick();
    expect(active().textContent).toContain("审片工作台");
    key(search(), "Enter");
    await vi.waitFor(() => expect(view.router.currentRoute.value.path).toBe("/review"));
    await closed(view.root);
  });

  it.each([true, false])("safely handles no results (empty items: %s)", async (emptyItems) => {
    const view = await mount(emptyItems ? [] : commands);
    await open(view.root);
    if (!emptyItems) await query("不存在的页面");
    expect(options()).toHaveLength(0);
    expect(search().hasAttribute("aria-activedescendant")).toBe(false);
    expect(document.querySelector("[role='status']")?.textContent).toContain("没有匹配");
    for (const value of ["ArrowUp", "ArrowDown", "Home", "End", "Enter"]) key(search(), value);
    await flush();
    expect(view.router.currentRoute.value.path).toBe("/start");
    expect(document.activeElement).toBe(search());
  });

  it("never offers external destinations from items", async () => {
    const view = await mount([
      ...commands,
      { to: "https://example.com", label: "外部链接", group: "外部" },
      { to: "//example.com", label: "协议相对链接", group: "外部" },
      { to: "/\\example.com", label: "反斜杠链接", group: "外部" },
    ]);
    await open(view.root);
    expect(options()).toHaveLength(3);
    await query("外部");
    expect(options()).toHaveLength(0);
    key(search(), "Enter");
    await flush();
    expect(view.router.currentRoute.value.path).toBe("/start");
  });

  it("does not navigate or change selection while confirming IME composition", async () => {
    const view = await mount();
    await open(view.root);
    key(search(), "ArrowDown", { isComposing: true });
    key(search(), "Enter", { isComposing: true });
    key(search(), "Enter", { keyCode: 229 });
    await flush();
    expect(active().textContent).toContain("工作概览");
    expect(view.router.currentRoute.value.path).toBe("/start");
  });

  it.each([{ isComposing: true }, { keyCode: 229 }])(
    "keeps the palette open when Escape cancels IME composition %j",
    async (composition) => {
      const view = await mount();
      await open(view.root);
      await query("审片");
      const event = key(search(), "Escape", composition);
      await flush();
      expect(event.defaultPrevented).toBe(false);
      expect(trigger(view.root).getAttribute("aria-expanded")).toBe("true");
      expect(search().value).toBe("审片");
      expect(document.activeElement).toBe(search());
      key(search(), "Escape");
      await closed(view.root);
    },
  );

  it("navigates by pointer without interpreting labels as HTML", async () => {
    const view = await mount([{ to: "/review", label: "<b>审片</b>", group: "内容" }]);
    await open(view.root);
    expect(options()[0]?.textContent).toContain("<b>审片</b>");
    expect(options()[0]?.querySelector("b")).toBeNull();
    options()[0]!.click();
    await vi.waitFor(() => expect(view.router.currentRoute.value.path).toBe("/review"));
    await closed(view.root);
  });

  it("dismisses with Escape, returns focus, and clears query and selection on reopen", async () => {
    const view = await mount();
    await open(view.root);
    await query("内容");
    key(search(), "End");
    await nextTick();
    key(search(), "Escape");
    await closed(view.root);
    await vi.waitFor(() => expect(document.activeElement).toBe(trigger(view.root)));
    await open(view.root);
    expect(search().value).toBe("");
    expect(options()).toHaveLength(3);
    expect(active().textContent).toContain("工作概览");
  });

  it("restores a keyboard opener rather than always moving focus to the trigger", async () => {
    const view = await mount();
    const opener = document.createElement("input");
    view.root.append(opener);
    opener.focus();
    key(opener, "k", { ctrlKey: true });
    await vi.waitFor(() => expect(document.activeElement).toBe(search()));
    key(search(), "Escape");
    await closed(view.root);
    await vi.waitFor(() => expect(document.activeElement).toBe(opener));
  });

  it.each(["identity", "scope", "mutation", "route"])("closes when %s changes", async (change) => {
    const view = await mount();
    await open(view.root);
    await query("内容");
    if (change === "identity") view.items.value = view.items.value.map((item) => ({ ...item }));
    else if (change === "scope") {
      view.items.value = [{ to: "/platform", label: "运营概览", group: "平台运营" }];
    } else if (change === "mutation") view.items.value[0]!.label = "另一身份的概览";
    else await view.router.push("/start?scope=new");
    await closed(view.root);
    await open(view.root);
    expect(search().value).toBe("");
    if (change === "scope") {
      expect(options()).toHaveLength(1);
      expect(active().textContent).toContain("运营概览");
    }
  });

  it.each([ElDialog, ElDrawer])(
    "does not steal another Element Plus modal's focus",
    async (overlayComponent) => {
      const view = await mount();
      const root = document.createElement("div");
      document.body.append(root);
      const app = createApp({
        render: () =>
          h(
            overlayComponent,
            { modelValue: true, title: "确认操作", appendToBody: true },
            {
              default: () => h("button", { id: "confirm-action" }, "确认"),
            },
          ),
      });
      app.mount(root);
      cleanups.push(() => {
        app.unmount();
        root.remove();
      });
      await flush();
      const confirmation = document.getElementById("confirm-action")!;
      confirmation.focus();
      expect(key(confirmation, "k", { ctrlKey: true }).defaultPrevented).toBe(false);
      trigger(view.root).click();
      await flush();
      expect(trigger(view.root).getAttribute("aria-expanded")).toBe("false");
      expect(document.activeElement).toBe(confirmation);
      expect(document.querySelector("input[role='combobox']")).toBeNull();
    },
  );

  it("does not steal focus if a confirmation opens during its opening transition", async () => {
    const view = await mount();
    vi.useFakeTimers();
    trigger(view.root).click();
    await nextTick();
    await nextTick();
    const focusSearch = vi.spyOn(search(), "focus");
    const root = document.createElement("div");
    document.body.append(root);
    const app = createApp({
      render: () =>
        h(
          ElDialog,
          { modelValue: true, title: "确认操作", appendToBody: true },
          {
            default: () => h("button", { id: "pending-confirmation" }, "确认"),
          },
        ),
    });
    app.mount(root);
    cleanups.push(() => {
      app.unmount();
      root.remove();
    });
    await nextTick();
    await nextTick();
    await vi.advanceTimersByTimeAsync(100);
    const confirmation = document.getElementById("pending-confirmation")!;
    confirmation.focus();
    expect(key(confirmation, "k", { metaKey: true }).defaultPrevented).toBe(false);
    expect(focusSearch).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(confirmation);
  });

  it("ignores hidden modal ancestors rather than blocking navigation forever", async () => {
    const view = await mount();
    const hidden = document.createElement("div");
    hidden.style.display = "none";
    const modal = document.createElement("div");
    modal.setAttribute("role", "dialog");
    modal.setAttribute("aria-modal", "true");
    hidden.append(modal);
    view.root.append(hidden);
    await open(view.root);
    expect(options()).toHaveLength(3);
  });

  it("removes its document shortcut listener on unmount", async () => {
    const add = vi.spyOn(document, "addEventListener");
    const remove = vi.spyOn(document, "removeEventListener");
    const view = await mount();
    const keyListeners = add.mock.calls.filter(([type]) => type === "keydown");
    expect(keyListeners.length).toBeGreaterThan(0);
    view.unmount();
    expect(
      keyListeners.some(([, listener]) =>
        remove.mock.calls.some(([type, removed]) => type === "keydown" && removed === listener),
      ),
    ).toBe(true);
    expect(key(document, "k", { ctrlKey: true }).defaultPrevented).toBe(false);
    await flush();
    expect(document.querySelector("input[role='combobox']")).toBeNull();
  });
});
