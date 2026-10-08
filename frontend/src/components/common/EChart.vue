<template>
  <div ref="el" class="echart" :style="{ height }" role="img" :aria-label="ariaLabel || '图表'" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useMedia } from '@/composables/useMedia'

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent, CanvasRenderer])

const props = withDefaults(defineProps<{ option: Record<string, unknown>; height?: string; ariaLabel?: string }>(), {
  height: '260px',
})

const el = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
const { width } = useMedia()

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  chart.setOption(props.option as never, true)
}

function resize() {
  chart?.resize()
}

onMounted(async () => {
  await nextTick()
  render()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})

watch(() => props.option, render, { deep: true })
watch(width, resize)
</script>

<style scoped>
.echart {
  width: 100%;
}
</style>
