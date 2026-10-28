import type { ProtoProjectDetail, ProtoStage } from "@heron/contracts";
import { consoleAccess } from "@heron/domain";
import { createPinia, disposePinia } from "pinia";
import { createApp, type Component } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useAuthStore } from "../../stores/auth";
import ProtoWorkshop from "./ProtoWorkshop.vue";

const project: ProtoProjectDetail = {
  id: "de000000-0000-4000-8000-000000000001",
  title: "工单后台",
  requirement: "查询工单并查看处理记录",
  target: "web",
  theme: "enterprise-light",
  stage: "review",
  errorReason: null,
  latestVersion: null,
  createdAt: "2026-10-28T00:00:00.000Z",
  updatedAt: "2026-10-28T00:00:00.000Z",
  revisions: [],
  gates: [],
};
const cleanups: (() => void)[] = [];

function button(text: string): HTMLButtonElement {
  const result = [...document.querySelectorAll("button")].find(
    (element) => element.textContent?.trim() === text,
  );
  expect(result, text).toBeDefined();
  return result!;
}

async function mount(stage: ProtoStage, engineReady = true, readOnly = false) {
  vi.stubGlobal("fetch", (path: string) => {
    const data = path.endsWith("/capability")
      ? {
          engine: { ok: engineReady, detail: engineReady ? "已就绪" : "未配置 ZEE_RP_HOME" },
          template: { ok: true, detail: "已就绪" },
          inflight: 0,
          maxInflight: 3,
        }
      : path.includes(project.id)
        ? { ...project, stage, errorReason: stage === "failed" ? "模型调用前租约已失效" : null }
        : { items: [{ ...project, stage }], total: 1, page: 1, pageSize: 20 };
    return Promise.resolve(new Response(JSON.stringify(data)));
  });
  const pinia = createPinia();
  useAuthStore(pinia).session = {
    kind: "tenant",
    user: {
      id: "11111111-1111-4111-8111-111111111111",
      tenantId: "00000000-0000-4000-8000-000000000001",
      tenantName: "测试空间",
      tenantSlug: "test",
      email: "owner@example.com",
      displayName: "测试所有者",
      role: "owner",
      ...(readOnly ? { scope: "platform-view" as const } : {}),
    },
    ...consoleAccess("owner"),
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/proto", component: { template: "<div />" } }],
  });
  await router.push("/proto");
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(ProtoWorkshop as Component)
    .use(pinia)
    .use(router);
  app.mount(root);
  cleanups.push(() => {
    app.unmount();
    disposePinia(pinia);
    root.remove();
  });
  await vi.waitFor(() => expect(root.textContent).toContain("工单后台"));
  button("详情").click();
  await vi.waitFor(() => expect(button("重新生成")).toBeDefined());
}

beforeEach(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
});
afterEach(() => {
  cleanups
    .splice(0)
    .reverse()
    .forEach((cleanup) => cleanup());
  vi.unstubAllGlobals();
});

describe("原型重跑的界面边界", () => {
  it.each<ProtoStage>(["draft", "scripting", "compiling", "review", "approved"])(
    "%s 不能重跑，即使机器已就绪",
    async (stage) => {
      await mount(stage);
      expect(button("重新生成").disabled).toBe(true);
      expect(document.body.textContent).toContain("只有在已交付或失败后才能重新生成");
    },
  );
  it.each<ProtoStage>(["failed", "delivered"])("%s 可以开始新版本", async (stage) => {
    await mount(stage);
    expect(button("重新生成").disabled).toBe(false);
  });
  it("终态仍受机器能力和只读身份限制", async () => {
    await mount("failed", false);
    expect(button("重新生成").disabled).toBe(true);
    expect(document.body.textContent).toContain("未配置 ZEE_RP_HOME");
  });
  it("版本尚未产生时也显示项目失败原因", async () => {
    await mount("failed");
    expect(button("重新生成").closest('[role="dialog"]')?.textContent).toContain(
      "模型调用前租约已失效",
    );
  });
  it("平台查看态不能生成或确认", async () => {
    await mount("delivered", true, true);
    expect(button("重新生成").disabled).toBe(true);
    expect(button("新建原型").disabled).toBe(true);
    expect(button("确认这一版").disabled).toBe(true);
  });
});
