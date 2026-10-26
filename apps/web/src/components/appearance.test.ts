import { createPinia, disposePinia } from "pinia";
import { createApp, nextTick, type Component } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ConsoleFrame from "./ConsoleFrame.vue";
import { consoleAccess } from "@heron/domain";
import type { AuthUser, ConsoleSession } from "@heron/contracts";
import { useAuthStore } from "../stores/auth";

const USER: AuthUser = {
  id: "11111111-1111-4111-8111-111111111111",
  tenantId: "00000000-0000-4000-8000-000000000001",
  tenantName: "Workspace",
  tenantSlug: "acme",
  email: "owner@example.com",
  displayName: "Owner",
  role: "owner",
};
const tenantSession = (): ConsoleSession => ({
  kind: "tenant",
  user: USER,
  ...consoleAccess(USER.role),
});
const platformSession = (): ConsoleSession => ({
  kind: "platform",
  user: { id: USER.id, email: USER.email, displayName: USER.displayName },
  ...consoleAccess("platform"),
});

const cleanups: (() => void)[] = [];
let media: EventTarget & { matches: boolean };
async function mount(platform = false) {
  const pinia = createPinia();
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/:pathMatch(.*)*", component: { template: "<div />" } }],
  });
  await router.push("/overview");
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(ConsoleFrame as Component, { signingOut: false })
    .use(pinia)
    .use(router);
  useAuthStore(pinia).session = platform ? platformSession() : tenantSession();
  app.mount(root);
  const dispose = () => {
    app.unmount();
    disposePinia(pinia);
    root.remove();
  };
  cleanups.push(dispose);
  await nextTick();
  return root;
}
async function select(root: HTMLElement, mode: string) {
  const control = root.querySelector<HTMLSelectElement>("select[aria-label='配色模式']");
  expect(control, "the shared console exposes a labelled appearance selector").not.toBeNull();
  control!.value = mode;
  control!.dispatchEvent(new Event("change", { bubbles: true }));
  await nextTick();
}
beforeEach(() => {
  localStorage.clear();
  document.documentElement.className = "";
  media = Object.assign(new EventTarget(), {
    matches: false,
    media: "(prefers-color-scheme: dark)",
  });
  vi.stubGlobal("matchMedia", () => media);
});
afterEach(() => {
  cleanups
    .splice(0)
    .reverse()
    .forEach((dispose) => dispose());
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
  document.documentElement.className = "";
});

describe("console appearance", () => {
  it("switches the whole document and persists the user's choice", async () => {
    const root = await mount();
    await select(root, "dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(localStorage.getItem("heron:appearance")).toBe("dark");
    await select(root, "light");
    expect(document.documentElement.classList.contains("dark")).toBe(false);
  });
  it("restores a saved choice before console interaction", async () => {
    localStorage.setItem("heron:appearance", "dark");
    await mount();
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });
  it("follows system changes only when the user selects system mode", async () => {
    const root = await mount();
    await select(root, "auto");
    media.matches = true;
    media.dispatchEvent(Object.assign(new Event("change"), { matches: media.matches }));
    await nextTick();
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    await select(root, "light");
    media.matches = false;
    media.dispatchEvent(Object.assign(new Event("change"), { matches: media.matches }));
    media.matches = true;
    media.dispatchEvent(Object.assign(new Event("change"), { matches: media.matches }));
    await nextTick();
    expect(document.documentElement.classList.contains("dark")).toBe(false);
  });
  it("treats an invalid stored value as a system preference, not a CSS class", async () => {
    localStorage.setItem("heron:appearance", "unexpected-value");
    media.matches = true;
    const root = await mount();
    expect(root.querySelector<HTMLSelectElement>("select[aria-label='配色模式']")?.value).toBe(
      "auto",
    );
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(document.documentElement.classList.contains("unexpected-value")).toBe(false);
  });
  it("syncs a changed preference from another tab", async () => {
    await mount();
    localStorage.setItem("heron:appearance", "dark");
    window.dispatchEvent(
      new StorageEvent("storage", {
        key: "heron:appearance",
        newValue: "dark",
        storageArea: localStorage,
      }),
    );
    await nextTick();
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });
  it("still allows switching when browser storage is unavailable", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("Denied", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("Denied", "SecurityError");
    });
    const root = await mount();
    await select(root, "dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });
});

describe("console command scope", () => {
  it.each([false, true])(
    "uses only the current console's real navigation (platform: %s)",
    async (platform) => {
      const root = await mount(platform);
      const trigger = root.querySelector<HTMLButtonElement>("button[aria-label='快捷导航']");
      expect(trigger, "shared console exposes command navigation").not.toBeNull();
      trigger!.click();
      await vi.waitFor(() => {
        expect(document.querySelectorAll("[role='option']")).toHaveLength(platform ? 5 : 13);
      });
      const offeredPaths = [...document.querySelectorAll("[role='option'] .command-path")].map(
        (element) => element.textContent,
      );
      const menuPaths = [...root.querySelectorAll(".console-nav a")].map((element) =>
        element.getAttribute("href"),
      );
      expect(offeredPaths).toEqual(menuPaths);
      expect(offeredPaths.every((path) => path?.startsWith("/platform") === platform)).toBe(true);
      const input = document.querySelector<HTMLInputElement>("input[role='combobox']")!;
      input.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
      await vi.waitFor(() => expect(document.querySelector("input[role='combobox']")).toBeNull());
    },
  );
});
