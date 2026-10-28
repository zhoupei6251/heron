<script setup lang="ts">
import type { GateName, Stage } from "@heron/contracts";
import { computed } from "vue";

/**
 * 生产轨道：九个在轨状态 + 五道闸。闸画在「要进入那一格之前」——
 * 与 domain 的 gateForTransition 同一套判据（去重在上游、契约挡在渲染前、
 * 视觉挡在审片前、人工挡在确认前、合规挡在发布前）。
 * failed 是脱轨终点，不占轨道位置：轨道不知道它停在哪一步，就不假装知道。
 */
const TRACK = [
  { stage: "candidate", label: "待认领" },
  { stage: "claimed", label: "已认领" },
  { stage: "scripting", label: "写稿中" },
  { stage: "factcheck", label: "事实核查" },
  { stage: "rendering", label: "渲染中" },
  { stage: "review", label: "待审片" },
  { stage: "approved", label: "已确认" },
  { stage: "publishing", label: "发布中" },
  { stage: "published", label: "已发布" },
] as const satisfies readonly { stage: Stage; label: string }[];

const GATE_BEFORE: Partial<Record<Stage, GateName>> = {
  candidate: "dedupe",
  rendering: "contract",
  review: "visual",
  approved: "human",
  publishing: "compliance",
};
const GATE_LABEL: Record<GateName, string> = {
  dedupe: "去重",
  contract: "契约",
  visual: "视觉",
  compliance: "合规",
  human: "人工",
};

const props = defineProps<{ stage: Stage; dense?: boolean }>();

const current = computed(() => TRACK.findIndex((node) => node.stage === props.stage));
const label = computed(() =>
  props.stage === "failed" ? "失败" : (TRACK[current.value]?.label ?? props.stage),
);
const summary = computed(() =>
  props.stage === "failed"
    ? "生产阶段：失败（已脱轨，失败原因见任务详情）"
    : `生产阶段：${label.value}（第 ${current.value + 1} / ${TRACK.length} 步）`,
);
</script>

<template>
  <div
    class="stage-track"
    :class="{ 'is-dense': dense, 'is-failed': stage === 'failed' }"
    role="img"
    :aria-label="summary"
  >
    <ol aria-hidden="true">
      <li
        v-for="(node, i) in TRACK"
        :key="node.stage"
        :class="{
          done: stage !== 'failed' && i < current,
          current: stage !== 'failed' && i === current,
        }"
      >
        <span
          v-if="GATE_BEFORE[node.stage]"
          class="gate"
          :title="`${GATE_LABEL[GATE_BEFORE[node.stage]!]}闸`"
          >{{ dense ? "" : GATE_LABEL[GATE_BEFORE[node.stage]!] }}</span
        >
        <span class="dot" />
        <span class="node-label">{{ node.label }}</span>
      </li>
    </ol>
    <p
      v-if="dense || stage === 'failed'"
      class="track-label"
      :class="{ 'is-danger': stage === 'failed' }"
    >
      {{ label }}
    </p>
  </div>
</template>

<style scoped>
.stage-track ol {
  display: flex;
  list-style: none;
  margin: 0;
  padding: 0;
}
.stage-track li {
  flex: 1;
  min-width: 0;
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
}
/* 上一颗点到这一颗点的连线：两心相距一整格，所以从 -50% 画满 100% */
.stage-track li:not(:first-child)::before {
  content: "";
  position: absolute;
  top: 4px;
  left: -50%;
  width: 100%;
  height: 2px;
  background: var(--heron-line);
}
.stage-track li.done::before,
.stage-track li.current::before {
  background: var(--heron-accent);
}
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  border: 2px solid var(--heron-line);
  background: var(--heron-surface);
  position: relative;
  z-index: 1;
}
li.done .dot {
  border-color: var(--heron-accent);
  background: var(--heron-accent);
}
li.current .dot {
  border-color: var(--heron-accent);
  background: var(--heron-grad);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--heron-accent) 18%, transparent);
}
.is-failed .dot {
  border-color: var(--el-color-danger);
  background: var(--heron-surface);
}
/* 闸标在连线中点：小菱形，不是状态点，读屏靠整块的 aria-label */
.gate {
  position: absolute;
  top: -12px;
  left: -50%;
  transform: translateX(-50%);
  padding: 0 3px;
  font-size: 9px;
  line-height: 12px;
  color: var(--heron-muted);
  background: var(--heron-surface);
  border: 1px solid var(--heron-line);
  border-radius: 3px;
  white-space: nowrap;
}
li:first-child .gate {
  left: 50%;
}
.node-label {
  margin-top: 7px;
  font-size: 11px;
  line-height: 1.3;
  color: var(--heron-muted);
  white-space: nowrap;
}
li.current .node-label {
  color: var(--heron-ink);
  font-weight: 600;
}
/* 表格里的窄格：只留当前那一段的字，九个点仍然读得出位置 */
.is-dense ol {
  padding-top: 10px;
}
.is-dense .node-label {
  display: none;
}
.is-dense .gate {
  top: -8px;
  width: 5px;
  height: 5px;
  padding: 0;
  border-radius: 1px;
  border-color: var(--heron-accent);
  transform: translateX(-50%) rotate(45deg);
}
.track-label {
  margin: 7px 0 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--heron-ink);
}
.is-danger {
  color: var(--el-color-danger);
}
@media (prefers-reduced-motion: no-preference) {
  li.current .dot {
    animation: track-pulse 2.4s ease-in-out infinite;
  }
  @keyframes track-pulse {
    0%,
    100% {
      box-shadow: 0 0 0 3px color-mix(in srgb, var(--heron-accent) 18%, transparent);
    }
    50% {
      box-shadow: 0 0 0 6px color-mix(in srgb, var(--heron-accent) 8%, transparent);
    }
  }
}
</style>
