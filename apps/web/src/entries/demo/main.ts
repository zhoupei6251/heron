import { createApp } from "vue";

import DemoPack from "./DemoPack.vue";

export interface MountOptions {
  vars?: Record<string, unknown>;
}

export function mount(target: HTMLElement, compId: string, options: MountOptions = {}): void {
  createApp(DemoPack, { compId, vars: options.vars ?? {} }).mount(target);
}

const previewRoot = document.getElementById("stage");
if (previewRoot?.dataset.preview === "1") {
  mount(previewRoot, "demo");
}
