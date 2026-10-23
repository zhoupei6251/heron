import type { CapabilityManifest } from "@heron/contracts";
import { NEWS_CAPABILITY, newsManifest } from "@heron/capability-news";

export const P0_FAKE = "p0-fake";

const FULL_PIPELINE = [
  "candidate",
  "claimed",
  "scripting",
  "factcheck",
  "rendering",
  "review",
  "approved",
  "publishing",
  "published",
] as const;

const REGISTRY: Record<string, CapabilityManifest> = {
  [P0_FAKE]: {
    name: P0_FAKE,
    pipeline: [...FULL_PIPELINE],
    gates: [],
    layouts: [],
    promptKeys: [],
  },
  [NEWS_CAPABILITY]: newsManifest,
};

export function getManifest(capability: string): CapabilityManifest {
  const manifest = REGISTRY[capability];
  if (!manifest) {
    throw new Error(
      `未注册的 capability：${capability}。已注册：${Object.keys(REGISTRY).join(", ") || "无"}`,
    );
  }
  return manifest;
}
