<script setup lang="ts">
import { BarChart } from "echarts/charts";
import { GridComponent } from "echarts/components";
import { init, use, type ECharts } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useAppearanceStore } from "../stores/appearance";

use([BarChart, GridComponent, CanvasRenderer]);

const props = defineProps<{
  /** 同一单位的若干条用量，单位不同的不画在同一张图上。 */
  bars: readonly { label: string; value: number }[];
  /** 画布对读屏只剩一句话，完整数字放这里。 */
  summary: string;
}>();
const appearance = useAppearanceStore();
const host = ref<HTMLDivElement>();
const height = computed(() => Math.max(props.bars.length * 30 + 12, 96));
const format = (value: number) => value.toLocaleString("zh-CN");
let chart: ECharts | undefined;
let observer: ResizeObserver | undefined;

function draw(): void {
  const element = host.value;
  if (!element) return;
  // 画布读不到 CSS 变量，取一次当时的主题色；切主题时由下面的 watch 重画
  const style = getComputedStyle(element);
  const token = (name: string) => style.getPropertyValue(name).trim();
  chart ??= init(element);
  chart.setOption(
    {
      animationDuration: 400,
      grid: { left: 0, right: 56, top: 4, bottom: 4, containLabel: true },
      xAxis: { type: "value", show: false },
      yAxis: {
        type: "category",
        inverse: true,
        data: props.bars.map((bar) => bar.label),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: token("--heron-muted"), fontSize: 11 },
      },
      series: [
        {
          type: "bar",
          data: props.bars.map((bar) => bar.value),
          barMaxWidth: 14,
          itemStyle: { color: token("--heron-accent"), borderRadius: [0, 7, 7, 0] },
          label: {
            show: true,
            position: "right",
            color: token("--heron-ink"),
            fontFamily: token("--heron-mono"),
            fontSize: 11,
            formatter: (item: { value: number }) => format(item.value),
          },
        },
      ],
    },
    { notMerge: true },
  );
}

onMounted(() => {
  draw();
  observer = new ResizeObserver(() => chart?.resize());
  if (host.value) observer.observe(host.value);
});
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
// 主题类由 useColorMode 在 post flush 里才写到 <html>：等 DOM 更新完再读 token，否则读到上一套色
watch([() => props.bars, () => appearance.resolved], () => void nextTick(draw));
</script>
<template>
  <div
    ref="host"
    class="usage-bars"
    role="img"
    :aria-label="summary"
    :style="{ height: `${height}px` }"
  />
</template>
