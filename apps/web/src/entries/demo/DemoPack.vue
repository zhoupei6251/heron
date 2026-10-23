<script setup lang="ts">
import { gsap } from "gsap";
import { onMounted, useTemplateRef } from "vue";

defineProps<{ compId: string; vars: Record<string, unknown> }>();
const root = useTemplateRef<HTMLElement>("root");

onMounted(() => {
  if (!root.value) return;
  gsap
    .timeline({ paused: true })
    .from(".heron-line", { y: 40, opacity: 0, duration: 0.4, stagger: 0.15 })
    .play();
});
</script>

<template>
  <div ref="root" class="heron-pack">
    <p class="heron-line heron-kicker">{{ compId }}</p>
    <h1 class="heron-line heron-title">{{ vars.title ?? "heron 版式占位" }}</h1>
    <p class="heron-line heron-body">
      {{
        vars.body ?? "P5 重做版式；此 pack 只用于验证产物契约：导出 mount、GSAP timeline 可注册。"
      }}
    </p>
  </div>
</template>

<style scoped>
.heron-pack {
  width: 1080px;
  height: 1920px;
  padding: 120px 96px;
  background: #0b0b0d;
  color: #f5f5f7;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 40px;
}
.heron-kicker {
  font-size: 40px;
  letter-spacing: 4px;
  color: #7fd1c8;
  margin: 0;
}
.heron-title {
  font-size: 96px;
  line-height: 1.15;
  margin: 0;
}
.heron-body {
  font-size: 48px;
  line-height: 1.5;
  margin: 0;
  color: #d8d8de;
}
</style>
