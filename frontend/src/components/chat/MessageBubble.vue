<template>
  <div v-if="msg.role === 'user'" class="bubble bubble--user">
    <div class="bubble__content">{{ msg.content }}</div>
  </div>

  <div v-else class="bubble bubble--ai">
    <div class="bubble__meta">
      <span class="bubble__name">智汇助手</span>
      <span class="bubble__time">{{ timeText }}</span>
      <span v-if="msg.warning" class="bubble__warn" role="status">
        <el-icon><WarningFilled /></el-icon> 检索零命中
      </span>
      <span v-else-if="msg.revisedFrom" class="bubble__warn" role="status" :title="msg.revisedFrom">
        <el-icon><WarningFilled /></el-icon> 上一版未过质量校验，已重写
      </span>
    </div>
    <div class="bubble__content" :class="{ 'stream-cursor': msg.streaming }" aria-live="polite">
      <template v-for="(seg, i) in segments" :key="i">
        <span v-if="seg.type === 'text'">{{ seg.value }}</span>
        <button
          v-else
          class="cite-badge"
          :aria-label="`引用 ${seg.value}，查看来源卡片`"
          @click="jumpCitation(Number(seg.value))"
        >[{{ seg.value }}]</button>
      </template>
      <span v-if="!msg.content && msg.streaming" class="muted">思考中…</span>
    </div>

    <div v-if="msg.error" class="bubble__error">
      <el-icon color="var(--danger)"><CircleCloseFilled /></el-icon>
      <span>{{ msg.error.message }}</span>
      <el-button v-if="msg.error.retryable" size="small" type="primary" plain @click="emit('retry')">重试</el-button>
    </div>

    <!-- 引用卡片网格（两列） -->
    <div v-if="msg.citations?.length" class="cite-grid">
      <div
        v-for="c in msg.citations"
        :id="`cite-${msg.id}-${c.id}`"
        :key="c.id"
        class="cite-card"
        role="button"
        tabindex="0"
        :aria-label="`引用 ${c.id}：${c.doc} ${where(c)}，打开来源`"
        @click="emit('openSource', c)"
        @keyup.enter="emit('openSource', c)"
      >
        <div class="cite-card__head">
          <span class="cite-card__no">[{{ c.id }}]</span>
          <span class="cite-card__doc" :title="c.doc">{{ c.doc }}</span>
          <span class="cite-card__page">{{ where(c) }}</span>
        </div>
        <p v-if="c.snippet" class="cite-card__snippet">{{ c.snippet }}</p>
        <span class="cite-card__open">查看原文 →</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CircleCloseFilled, WarningFilled } from '@element-plus/icons-vue'
import type { Citation } from '@/types/api'

export interface UiChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations?: Citation[]
  warning?: boolean
  /** 上一版没通过质量校验、后端已重写这一版（D-1）。存作废原因，只在这次流里存在，不落库 */
  revisedFrom?: string
  error?: { message: string; retryable: boolean }
  streaming?: boolean
  createdAt: string
}

const props = defineProps<{ msg: UiChatMessage }>()
const emit = defineEmits<{ retry: []; openSource: [citation: Citation] }>()

/** 引用定位标签：页码未知时退回分块序号，不显示编造的页码 */
function where(c: Citation) {
  return c.page ? `第 ${c.page} 页` : `分块 #${c.chunkIndex + 1}`
}

/** 将行内 [n] 解析为上标徽标段 */
const segments = computed(() => {
  const out: { type: 'text' | 'cite'; value: string }[] = []
  const re = /\[(\d{1,2})\]/g
  let last = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(props.msg.content))) {
    if (m.index > last) out.push({ type: 'text', value: props.msg.content.slice(last, m.index) })
    out.push({ type: 'cite', value: m[1] })
    last = m.index + m[0].length
  }
  if (last < props.msg.content.length) out.push({ type: 'text', value: props.msg.content.slice(last) })
  return out
})

const timeText = computed(() => {
  if (!props.msg.createdAt) return ''
  const d = new Date(props.msg.createdAt)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
})

function jumpCitation(n: number) {
  const target = document.getElementById(`cite-${props.msg.id}-${n}`)
  if (!target) return
  target.scrollIntoView({ behavior: 'smooth', block: 'center' })
  target.classList.add('citation-card--flash')
  setTimeout(() => target.classList.remove('citation-card--flash'), 2600)
}
</script>

<style scoped>
.bubble {
  display: flex;
  max-width: 78%;
}
.bubble--user {
  margin-left: auto;
}
.bubble--user .bubble__content {
  background: var(--brand);
  color: #fff;
  border-radius: var(--radius-card) var(--radius-card) 4px var(--radius-card);
  padding: 10px 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble--ai {
  flex-direction: column;
  margin-right: auto;
}
.bubble--ai .bubble__content {
  background: var(--brand-soft);
  color: var(--text);
  border-radius: var(--radius-card) var(--radius-card) var(--radius-card) 4px;
  padding: 10px 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--font-aux);
  color: #7a857f;
  margin-bottom: 4px;
}
.bubble__name {
  font-weight: 600;
}
.bubble__warn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--warning);
}
.bubble__error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  color: var(--danger);
  font-size: var(--font-aux);
}
.cite-badge {
  display: inline-block;
  border: none;
  background: rgba(18, 184, 134, 0.15);
  color: var(--brand);
  font-size: 11px;
  line-height: 16px;
  padding: 0 5px;
  margin: 0 2px;
  border-radius: 8px;
  vertical-align: super;
  cursor: pointer;
}
.cite-badge:hover,
.cite-badge:focus-visible {
  background: var(--brand);
  color: #fff;
  outline: 2px solid var(--brand);
  outline-offset: 1px;
}
.cite-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 10px;
}
.cite-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  background: var(--bg);
  padding: 8px 10px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.cite-card:hover,
.cite-card:focus-visible {
  border-color: var(--brand);
  box-shadow: 0 0 0 1px var(--brand);
}
.cite-card__open {
  display: inline-block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--brand);
}
.cite-card__head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-aux);
}
.cite-card__no {
  color: var(--brand);
  font-weight: 700;
}
.cite-card__doc {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cite-card__page {
  color: #7a857f;
}
.cite-card__snippet {
  margin: 6px 0 0;
  font-size: var(--font-aux);
  color: #55615b;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
@media (max-width: 767px) {
  .bubble { max-width: 92%; }
  .cite-grid { grid-template-columns: 1fr; }
}
</style>
