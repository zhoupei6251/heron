<script setup lang="ts">
import { DECLARATION_TEXT } from "@heron/domain";
import { gsap } from "gsap";
import { computed, onMounted, useTemplateRef } from "vue";

/**
 * 竖版成片版式（1080×1920）。pack 契约只有两个输入：compId 与 vars。
 * 版式自带全部样式与字体栈——packs 构建不引 app.css，界面 token 不会漏进成片。
 */
const props = defineProps<{ compId: string; vars: Record<string, unknown> }>();
const root = useTemplateRef<HTMLElement>("root");

function text(value: unknown, fallback: string): string {
  return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

const kicker = computed(() => text(props.vars.kicker, "新闻"));
const rank = computed(() => text(props.vars.rank, "01"));
const count = computed(() => text(props.vars.count, "01 / 01"));
const title = computed(() => text(props.vars.title, "这一版式用于验证成片产物"));
const onscreen = computed(() => text(props.vars.onscreen, ""));
const body = computed(() =>
  text(props.vars.body, "导出 mount、GSAP timeline 与内容声明，都在这张 1080×1920 的帧里验完。"),
);
const source = computed(() => text(props.vars.source, "heron 演示数据"));

onMounted(() => {
  if (!root.value) return;
  // 静帧也要能读：系统要求减少动态时直接给终态，不留半透明的中间帧
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  gsap
    .timeline({ paused: true })
    .from(".line", { y: 36, opacity: 0, duration: 0.5, stagger: 0.12, ease: "power3.out" })
    .from(".watermark", { scale: 1.06, opacity: 0, duration: 1.2, ease: "power2.out" }, 0)
    .play();
});
</script>

<template>
  <div ref="root" class="pack">
    <div class="watermark" aria-hidden="true">{{ rank }}</div>
    <header class="line pack-top">
      <span class="kicker">{{ kicker }}</span>
      <span class="count">{{ count }}</span>
    </header>
    <main class="pack-body">
      <h1 class="line title">{{ title }}</h1>
      <div class="line rule" aria-hidden="true"></div>
      <p v-if="onscreen" class="line onscreen">{{ onscreen }}</p>
      <p class="line body">{{ body }}</p>
    </main>
    <footer class="line pack-foot">
      <span class="source">{{ source }}</span>
      <span class="declaration">{{ DECLARATION_TEXT }}</span>
    </footer>
  </div>
</template>

<style scoped>
.pack {
  position: relative;
  width: 1080px;
  height: 1920px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 132px 96px 116px;
  color: #f2f7f8;
  font-family:
    "Space Grotesk Variable", "PingFang SC", "Noto Sans SC", "Microsoft YaHei", sans-serif;
  background:
    radial-gradient(120% 62% at 92% 0%, rgba(90, 169, 191, 0.24), transparent 62%),
    radial-gradient(90% 52% at 0% 100%, rgba(27, 108, 128, 0.2), transparent 60%), #0a1417;
}
.watermark {
  position: absolute;
  right: -24px;
  top: 150px;
  font-size: 620px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.06em;
  color: transparent;
  -webkit-text-stroke: 2px rgba(226, 240, 242, 0.07);
  pointer-events: none;
}
.pack-top {
  position: relative;
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-family: "JetBrains Mono Variable", Consolas, monospace;
  font-size: 28px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: #97abb0;
}
.pack-top .kicker {
  color: #8ad2e0;
}
.pack-body {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  padding-bottom: 92px;
}
.title {
  font-size: 120px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.02em;
  text-wrap: balance;
  margin: 0;
}
.rule {
  width: 132px;
  height: 8px;
  border-radius: 4px;
  margin: 46px 0 40px;
  background: linear-gradient(90deg, #5aa9bf, #1b6c80);
}
.onscreen {
  font-size: 52px;
  font-weight: 500;
  line-height: 1.4;
  color: #8ad2e0;
  margin: 0 0 30px;
}
.body {
  font-size: 46px;
  line-height: 1.62;
  color: #c3d2d5;
  margin: 0;
}
.pack-foot {
  position: relative;
  margin-top: auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 28px;
  padding-top: 38px;
  border-top: 1px solid rgba(226, 240, 242, 0.14);
  font-size: 26px;
}
.source {
  color: #83999e;
}
.declaration {
  flex-shrink: 0;
  padding: 12px 24px;
  border: 1px solid rgba(226, 240, 242, 0.24);
  border-radius: 999px;
  color: #eaf3f4;
  font-size: 24px;
  letter-spacing: 0.02em;
}
</style>
