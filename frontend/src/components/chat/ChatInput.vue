<template>
  <div class="chat-input">
    <div class="chat-input__box" :class="{ 'is-focused': focused }">
      <textarea
        ref="taRef"
        v-model="text"
        class="chat-input__ta"
        rows="1"
        placeholder="输入问题，Enter 发送 / Shift+Enter 换行"
        aria-label="提问输入框"
        @input="onInput"
        @keydown="onKeydown"
        @focus="focused = true"
        @blur="focused = false"
      />
    </div>
    <div class="chat-input__bar">
      <span v-if="truncated" class="chat-input__tip" role="status">
        <el-icon color="var(--warning)"><WarningFilled /></el-icon> 超过 500 字已截断
      </span>
      <span v-else class="chat-input__count muted">{{ text.length }}/500</span>
      <div class="chat-input__btns">
        <el-button v-if="generating" type="danger" plain :icon="VideoPause" @click="emit('stop')">停止生成</el-button>
        <el-button
          type="primary"
          class="chat-input__send"
          :class="{ 'chat-input__send--active': canSend }"
          :disabled="!canSend"
          :icon="Promotion"
          aria-label="发送"
          @click="submit"
        >发送</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Promotion, VideoPause, WarningFilled } from '@element-plus/icons-vue'

const props = defineProps<{ generating: boolean; disabled?: boolean }>()
const emit = defineEmits<{ send: [question: string] ; stop: [] }>()

const text = ref('')
const focused = ref(false)
const truncated = ref(false)
const taRef = ref<HTMLTextAreaElement>()

const canSend = computed(() => text.value.trim().length > 0 && !props.disabled)

function onInput() {
  if (text.value.length > 500) {
    text.value = text.value.slice(0, 500)
    truncated.value = true
    setTimeout(() => (truncated.value = false), 3000)
  }
  autoGrow()
}

function autoGrow() {
  nextTick(() => {
    const ta = taRef.value
    if (!ta) return
    ta.style.height = 'auto'
    ta.style.height = Math.min(ta.scrollHeight, 160) + 'px'
  })
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

function submit() {
  const q = text.value.trim()
  if (!q || props.disabled) return
  // 生成中再次发送 = 打断旧流（由父级 ChatView 处理）
  emit('send', q)
  text.value = ''
  autoGrow()
}

defineExpose({ focus: () => taRef.value?.focus() })
</script>

<style scoped>
.chat-input {
  border-top: 1px solid var(--border);
  background: var(--bg);
  padding: 10px 16px 12px;
}
.chat-input__box {
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.chat-input__box.is-focused {
  border-color: var(--brand);
  box-shadow: 0 0 0 2px var(--brand-soft);
}
.chat-input__ta {
  display: block;
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  padding: 10px 12px;
  font-size: var(--font-body);
  font-family: inherit;
  color: var(--text);
  background: transparent;
  max-height: 160px;
}
.chat-input__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.chat-input__tip {
  color: var(--warning);
  font-size: var(--font-aux);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.chat-input__send {
  background: #b9c6c0;
  border-color: #b9c6c0;
  color: #fff;
}
.chat-input__send--active,
.chat-input__send--active:hover {
  background: var(--brand);
  border-color: var(--brand);
}
.chat-input__send:disabled {
  opacity: 0.75;
}
</style>
