<template>
  <div class="pipeline" role="group" aria-label="多智能体执行管道">
    <template v-for="(node, i) in nodes" :key="node.key">
      <div class="pipeline__node" :class="`is-${defaultStates[node.key].status}`">
        <span class="pipeline__dot" aria-hidden="true">
          <el-icon :size="14"><component :is="node.icon" /></el-icon>
        </span>
        <span class="pipeline__name">{{ node.label }}</span>
        <span class="pipeline__sr" aria-live="polite">{{ statusText[node.key] }}</span>
      </div>
      <span v-if="i < nodes.length - 1" class="pipeline__arrow" aria-hidden="true">›</span>
    </template>
    <span v-if="progressText" class="pipeline__progress">{{ progressText }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { AgentStatus, AgentStep } from '@/types/api'

const props = defineProps<{
  states: Record<AgentStep, { status: AgentStatus; detail?: string }>
  progressText?: string
}>()

const nodes: { key: AgentStep; label: string; icon: string }[] = [
  { key: 'intent', label: '意图识别', icon: 'Aim' },
  { key: 'retrieval', label: '检索规划', icon: 'Search' },
  { key: 'synthesis', label: '知识合成', icon: 'Cpu' },
  { key: 'validation', label: '质量验证', icon: 'CircleCheck' },
]

const defaultStates = computed(() => {
  const r = {} as Record<AgentStep, { status: AgentStatus; detail?: string }>
  for (const n of nodes) r[n.key] = props.states?.[n.key] ?? { status: 'pending' }
  return r
})

const labelMap: Record<AgentStatus, string> = {
  pending: '待执行', running: '执行中', done: '已完成', failed: '失败', skipped: '已跳过',
}
const statusText = computed(() => {
  const r = {} as Record<AgentStep, string>
  for (const n of nodes) {
    const s = defaultStates.value[n.key]
    r[n.key] = `${n.label}${labelMap[s.status]}${s.detail ? '，' + s.detail : ''}`
  }
  return r
})
</script>

<style scoped>
.pipeline {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  background: #14221d;
  color: #cfe3db;
  border-radius: var(--radius-card);
  padding: 8px 14px;
}
.pipeline__node {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-aux);
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  transition: all 0.25s;
}
.pipeline__node.is-pending { opacity: 0.55; }
.pipeline__node.is-running {
  background: rgba(34, 139, 230, 0.25);
  color: #9fcdff;
  animation: breathe 1.3s ease-in-out infinite;
}
.pipeline__node.is-done {
  background: rgba(18, 184, 134, 0.25);
  color: #6fe0b8;
  animation: none;
}
.pipeline__node.is-skipped {
  background: rgba(140, 148, 145, 0.22);
  color: #a9b6b0;
  opacity: 0.85;
  animation: none;
}
.pipeline__node.is-failed {
  background: rgba(229, 72, 77, 0.3);
  color: #ff9ea1;
  animation: none;
}
.pipeline__dot {
  display: inline-flex;
}
.pipeline__arrow {
  color: #4b5f57;
}
.pipeline__progress {
  margin-left: auto;
  font-size: var(--font-aux);
  color: #8fb0a4;
}
/* 非视觉提示文字：仅供读屏 */
.pipeline__sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
@media (max-width: 767px) {
  .pipeline__name { display: none; }
}
</style>
