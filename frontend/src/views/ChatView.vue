<template>
  <div class="chat-page">
    <!-- 左：对话列表（≥1200 内嵌；<1200 抽屉） -->
    <aside v-if="!collapsed" class="chat-page__list zd-card">
      <ConversationList
        :items="conversations"
        :active-id="activeId"
        @select="openConversation"
        @create="newConversation"
        @remove="removeConversation"
      />
    </aside>
    <el-drawer v-else v-model="listOpen" direction="ltr" :size="280" title="历史对话">
      <ConversationList
        :items="conversations"
        :active-id="activeId"
        @select="(id) => { openConversation(id); listOpen = false }"
        @create="() => { newConversation(); listOpen = false }"
        @remove="removeConversation"
      />
    </el-drawer>

    <!-- 右：会话主体 -->
    <section class="chat-page__main">
      <header class="chat-page__head">
        <div class="chat-page__head-left">
          <el-button v-if="collapsed" text :icon="Expand" aria-label="打开对话列表" @click="listOpen = true" />
          <h1 class="chat-page__title">{{ title || '新会话' }}</h1>
          <el-tag v-if="!kbs.length" size="small" type="info" effect="light">暂无可见知识库</el-tag>
          <el-tag v-else-if="!kbFilter.length && disconnectedCount === 0" size="small" type="success" effect="light">
            {{ kbs.length }} 个知识库已连接
          </el-tag>
          <el-tag v-else-if="!kbFilter.length" size="small" type="danger" effect="light">
            {{ disconnectedCount }}/{{ kbs.length }} 个知识库未连接
          </el-tag>
          <el-tag v-else-if="filterAllConnected" size="small" type="success" effect="light">已选库全部连接</el-tag>
          <el-tag v-else size="small" type="danger" effect="light">部分知识库断开</el-tag>
        </div>
        <el-select
          v-model="kbFilter"
          multiple
          collapse-tags
          collapse-tags-tooltip
          clearable
          placeholder="知识库过滤（默认全部）"
          class="chat-page__kb"
          aria-label="知识库过滤"
        >
          <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
      </header>

      <AgentPipeline :states="pipelineStates" :progress-text="pipelineProgress" />

      <div ref="streamRef" class="chat-page__stream" aria-live="polite">
        <div v-if="!messages.length" class="chat-page__empty">
          <el-icon :size="42" color="var(--brand)"><ChatDotRound /></el-icon>
          <p>你好，我是智汇助手。提问企业知识，回答将附引用溯源。</p>
        </div>
        <MessageBubble
          v-for="m in messages"
          :key="m.id"
          :msg="m"
          class="chat-page__msg"
          @retry="retryLast"
          @open-source="openSource"
        />
      </div>

      <ChatInput
        ref="inputRef"
        :generating="streaming"
        @send="ask"
        @stop="stopGen"
      />
    </section>

    <!-- 引用来源抽屉 -->
    <el-drawer v-model="sourceOpen" title="引用来源" :size="drawerSize">
      <div v-if="sourceCite" class="source">
        <div class="source__head">
          <span class="source__no">[{{ sourceCite.id }}]</span>
          <span class="source__doc" :title="sourceCite.doc">{{ sourceCite.doc }}</span>
          <span class="source__page">{{ citeWhere(sourceCite) }}</span>
        </div>
        <blockquote v-if="sourceCite.snippet" class="source__snippet">{{ sourceCite.snippet }}</blockquote>
        <ChunkPreview v-if="sourceCite.docId" :doc-id="sourceCite.docId" />
        <p v-else class="muted">该引用缺少文档 ID，无法加载原文</p>
        <div class="source__foot">
          <el-button size="small" type="primary" plain @click="gotoDocuments">在文档管理中查看</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Expand } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ConversationList from '@/components/chat/ConversationList.vue'
import AgentPipeline from '@/components/chat/AgentPipeline.vue'
import MessageBubble, { type UiChatMessage } from '@/components/chat/MessageBubble.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import ChunkPreview from '@/components/documents/ChunkPreview.vue'
import { deleteConversation, listConversations, listMessages } from '@/api/chat'
import { listKnowledgeBases } from '@/api/knowledge'
import { useChatStream } from '@/composables/useChatStream'
import { useMedia } from '@/composables/useMedia'
import type { AgentStatus, AgentStep, Citation, Conversation, KnowledgeBase } from '@/types/api'

const { width } = useMedia()
const collapsed = computed(() => width.value < 1200) // <1200 折叠为抽屉
const listOpen = ref(false)

const router = useRouter()
const sourceOpen = ref(false)
const sourceCite = ref<Citation | null>(null)
const drawerSize = computed(() => (width.value < 768 ? '86%' : '420px'))

function openSource(c: Citation) {
  sourceCite.value = c
  sourceOpen.value = true
}

/** 引用定位标签：页码未知时退回分块序号，不显示编造的页码 */
function citeWhere(c: Citation) {
  return c.page ? `第 ${c.page} 页` : `分块 #${c.chunkIndex + 1}`
}

function gotoDocuments() {
  const c = sourceCite.value
  if (!c) return
  router.push({ path: '/documents', query: { keyword: c.doc } })
}

const conversations = ref<Conversation[]>([])
const activeId = ref<string | null>(null)
const title = computed(() => conversations.value.find((c) => c.id === activeId.value)?.title || '')
const messages = ref<UiChatMessage[]>([])
const kbs = ref<KnowledgeBase[]>([])
const kbFilter = ref<string[]>([])
const filterAllConnected = computed(() =>
  kbFilter.value.every((id) => kbs.value.find((k) => k.id === id)?.status === 'connected')
)
const disconnectedCount = computed(() => kbs.value.filter((k) => k.status !== 'connected').length)

const pipelineStates = reactive<Record<AgentStep, { status: AgentStatus; detail?: string }>>({
  intent: { status: 'pending' },
  retrieval: { status: 'pending' },
  synthesis: { status: 'pending' },
  validation: { status: 'pending' },
})
const pipelineText = ref('')
const pipelineProgress = computed(() => {
  const order: AgentStep[] = ['intent', 'retrieval', 'synthesis', 'validation']
  const done = order.filter((s) => pipelineStates[s].status === 'done').length
  const running = order.find((s) => pipelineStates[s].status === 'running')
  if (pipelineText.value) return pipelineText.value
  if (running) return `${done}/4 完成 · ${running} 执行中`
  return done === 4 ? '4/4 完成' : done > 0 ? `${done}/4 完成` : ''
})

const { streaming, send, stop } = useChatStream()
const streamRef = ref<HTMLElement>()
const inputRef = ref<{ focus: () => void } | null>(null)
let lastQuestion = ''

async function loadConversations() {
  try {
    conversations.value = await listConversations()
  } catch { /* 后端未就绪时静默 */ }
}

async function loadKbs() {
  try {
    kbs.value = await listKnowledgeBases()
  } catch { /* ignore */ }
}

onMounted(() => {
  loadConversations()
  loadKbs()
})

function resetPipeline() {
  for (const s of ['intent', 'retrieval', 'synthesis', 'validation'] as AgentStep[]) {
    pipelineStates[s] = { status: 'pending' }
  }
  pipelineText.value = ''
}

function scrollToBottom() {
  nextTick(() => {
    const el = streamRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function newConversation() {
  stop()
  finalizeAborted()
  activeId.value = null
  messages.value = []
  resetPipeline()
}

async function openConversation(id: string) {
  if (id === activeId.value) return
  stop()
  finalizeAborted()
  activeId.value = id
  resetPipeline()
  messages.value = []
  try {
    const records = await listMessages(id)
    messages.value = records.map((r) => ({
      id: r.id,
      role: r.role,
      content: r.content,
      citations: r.citations || undefined,
      createdAt: r.createdAt,
    }))
    scrollToBottom()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function removeConversation(id: string) {
  await ElMessageBox.confirm('删除后不可恢复，确定删除该会话？', '删除会话', { type: 'warning' })
  try {
    await deleteConversation(id)
    conversations.value = conversations.value.filter((c) => c.id !== id)
    if (activeId.value === id) newConversation()
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

/** 打断旧流后收尾：去掉光标、保留已生成文字 */
function finalizeAborted() {
  const last = messages.value[messages.value.length - 1]
  if (last?.role === 'assistant' && last.streaming) {
    last.streaming = false
  }
}

async function ask(question: string) {
  lastQuestion = question
  // 生成中再次发送 → 打断旧流（send 内部 abort）
  finalizeAborted()
  const now = new Date().toISOString()
  messages.value.push({ id: `u-${Date.now()}`, role: 'user', content: question, createdAt: now })
  resetPipeline()
  scrollToBottom()

  let ai: UiChatMessage | null = null
  // push 后必须取回数组内的响应式代理，直接改原始对象不会触发渲染
  const pushAi = (m: UiChatMessage) => {
    messages.value.push(m)
    return messages.value[messages.value.length - 1]
  }

  try {
  await send(
    {
      conversationId: activeId.value,
      question,
      knowledgeBaseIds: kbFilter.value,
    },
    {
      onStart(d) {
        activeId.value = d.conversationId
        ai = pushAi({ id: d.messageId, role: 'assistant', content: '', streaming: true, createdAt: new Date().toISOString() })
        scrollToBottom()
      },
      onAgentUpdate(u) {
        pipelineStates[u.step] = { status: u.status, detail: u.detail }
        if (u.detail) pipelineText.value = u.detail
        if (u.status === 'failed') pipelineText.value = `${u.step} 阶段失败`
      },
      onToken(t) {
        if (!ai) {
          ai = pushAi({ id: `a-${Date.now()}`, role: 'assistant', content: '', streaming: true, createdAt: new Date().toISOString() })
        }
        ai.content += t
        scrollToBottom()
      },
      onCitation(cs) {
        if (ai) ai.citations = cs
      },
      onWarning(w) {
        if (w.type === 'no-result' && ai) ai.warning = true
      },
      onRevision(d) {
        // 后端判定上一版没通过质量校验、要重写：已流出的文字必须作废，否则两版会拼在同一条消息里
        if (ai) {
          ai.content = ''
          ai.revisedFrom = d.reason
        }
      },
      onDone() {
        if (ai) ai.streaming = false
        loadConversations()
      },
      onError(e) {
        if (e.message === '__aborted__') {
          finalizeAborted()
          return
        }
        if (!ai) {
          ai = pushAi({ id: `a-${Date.now()}`, role: 'assistant', content: '', createdAt: new Date().toISOString() })
        }
        ai.streaming = false
        ai.error = { message: e.message, retryable: e.retryable }
        scrollToBottom()
      },
    }
  )
  } catch {
    // 网络中断：useChatStream 内已回调 onError，这里兜底防止未处理 rejection
    finalizeAborted()
  }
}

function stopGen() {
  stop()
  finalizeAborted()
}

function retryLast() {
  if (lastQuestion) ask(lastQuestion)
}
</script>

<style scoped>
.chat-page {
  display: flex;
  gap: 12px;
  height: 100%;
  padding: 12px;
  min-height: 0;
}
.chat-page__list {
  flex: 0 0 240px;
  width: 240px;
  padding: 10px;
  overflow: hidden;
}
.chat-page__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  overflow: hidden;
}
.chat-page__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}
.chat-page__head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.chat-page__title {
  font-size: var(--font-title);
  font-weight: 600;
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.chat-page__kb {
  width: 240px;
}
.chat-page .pipeline {
  margin: 10px 14px 0;
}
.chat-page__stream {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
}
.chat-page__empty {
  margin: auto;
  text-align: center;
  color: #7a857f;
}
.source {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.source__head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--font-aux);
}
.source__no {
  color: var(--brand);
  font-weight: 700;
}
.source__doc {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}
.source__page {
  color: #7a857f;
  white-space: nowrap;
}
.source__snippet {
  margin: 0;
  padding: 8px 12px;
  border-left: 3px solid var(--brand);
  background: var(--brand-soft);
  border-radius: 0 var(--radius-control) var(--radius-control) 0;
  font-size: 13px;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
}
.source__foot {
  display: flex;
  justify-content: flex-end;
  padding-top: 4px;
}
@media (max-width: 767px) {
  .chat-page { padding: 8px; gap: 8px; }
  .chat-page__kb { width: 100%; }
}
</style>
