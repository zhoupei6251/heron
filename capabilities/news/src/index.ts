import type { CapabilityManifest } from "@heron/contracts";

export const NEWS_CAPABILITY = "news";

export const newsManifest: CapabilityManifest = {
  name: NEWS_CAPABILITY,
  pipeline: [
    "candidate",
    "claimed",
    "scripting",
    "factcheck",
    "rendering",
    "review",
    "approved",
    "publishing",
    "published",
  ],
  gates: ["dedupe", "contract", "visual", "compliance", "human"],
  layouts: [],
  promptKeys: [],
};
