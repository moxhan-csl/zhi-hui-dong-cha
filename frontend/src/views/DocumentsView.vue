<template>
  <div class="doc-page">
    <div class="doc-page__head">
      <h1 class="page-title">文档管理</h1>
    </div>

    <div class="doc-page__toolbar zd-card">
      <el-input
        v-model="query.keyword"
        placeholder="搜索文档名称"
        :prefix-icon="Search"
        clearable
        class="doc-page__kw"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-segmented v-model="statusSeg" :options="statusOptions" aria-label="状态筛选" />
      <div v-if="canWrite" class="doc-page__ops">
        <el-button :disabled="!selection.length" :icon="Delete" @click="batchDelete">
          批量删除{{ selection.length ? `(${selection.length})` : '' }}
        </el-button>
        <el-button type="primary" :icon="Upload" @click="kbPickVisible = true">上传文档</el-button>
      </div>
    </div>

    <div class="zd-card doc-page__table">
      <el-table
        v-loading="loading"
        :data="items"
        row-key="id"
        scrollbar-always-on
        @selection-change="(rows: DocumentItem[]) => (selection = rows)"
      >
        <el-table-column v-if="canWrite" type="selection" width="42" />
        <el-table-column type="expand">
          <template #default="{ row }">
            <ChunkPreview :doc-id="row.id" :total="row.chunkCount" />
          </template>
        </el-table-column>
        <el-table-column prop="name" label="文档" min-width="240" show-overflow-tooltip />
        <el-table-column prop="type" label="类型" width="90" />
        <el-table-column prop="kbName" label="所属知识库" min-width="160" show-overflow-tooltip />
        <el-table-column prop="chunkCount" label="分块数" width="90" align="right" />
        <el-table-column label="状态" width="180">
          <template #default="{ row }">
            <div class="doc-status">
              <span class="pill" :class="pillClass(row.status)">{{ statusLabel(row.status) }}</span>
              <el-progress
                v-if="row.status === 'PARSING'"
                class="doc-status__bar"
                :percentage="Math.min(100, Math.round(row.progress ?? 0))"
                :stroke-width="6"
                :show-text="false"
              />
              <span v-if="row.status === 'PARSING'" class="doc-status__stage">{{ stageLabel(row.stage) }}</span>
              <el-popover v-if="row.status === 'FAILED' && row.failReason" trigger="hover" width="260">
                <template #reference>
                  <el-icon color="var(--danger)"><WarningFilled /></el-icon>
                </template>
                <div class="muted">失败原因：{{ row.failReason }}</div>
              </el-popover>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">{{ fmt(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'FAILED'" size="small" text type="primary" @click="retry(row)">重试</el-button>
            <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="doc-page__pager"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.size"
        :current-page="query.page"
        @current-change="(p: number) => { query.page = p; load() }"
      />
    </div>

    <!-- 上传前选择知识库 -->
    <el-dialog v-model="kbPickVisible" title="上传文档" width="420px">
      <el-form label-width="90px">
        <el-form-item label="目标知识库">
          <el-select v-model="uploadKbId" placeholder="选择知识库" style="width: 100%">
            <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-upload
            action="/api/documents/upload"
            :headers="uploadHeaders"
            :data="{ kbId: uploadKbId }"
            name="file"
            drag
            :show-file-list="false"
            :before-upload="doUpload"
            :on-success="onUploadSuccess"
            :on-error="onUploadError"
          >
            <el-icon :size="36" color="var(--brand)"><UploadFilled /></el-icon>
            <div>将文件拖到此处，或<em>点击选择</em></div>
            <template #tip><div class="muted">支持 PDF / DOCX / XLSX / MD / TXT</div></template>
          </el-upload>
        </el-form-item>
      </el-form>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Search, Upload, UploadFilled, WarningFilled } from '@element-plus/icons-vue'
import ChunkPreview from '@/components/documents/ChunkPreview.vue'
import { deleteDocument, listDocuments, retryDocument } from '@/api/documents'
import { listKnowledgeBases } from '@/api/knowledge'
import { authHeaders } from '@/api/http'
import { useUserStore } from '@/stores/user'
import type { DocStatus, DocumentItem, KnowledgeBase } from '@/types/api'

const route = useRoute()
/** 后端矩阵：文档 GET 对全部登录用户放行，POST/PUT/DELETE 需 KM_ADMIN+（D-6） */
const canWrite = useUserStore().atLeast('KM_ADMIN')
const loading = ref(false)
const items = ref<DocumentItem[]>([])
const total = ref(0)
const selection = ref<DocumentItem[]>([])
const kbs = ref<KnowledgeBase[]>([])
const query = reactive({ keyword: (route.query.keyword as string) || '', status: '', page: 1, size: 20 })

const statusSeg = ref('ALL')
const statusOptions = [
  { label: '全部', value: 'ALL' },
  { label: '处理中', value: 'DOING' },
  { label: '已入库', value: 'READY' },
  { label: '失败', value: 'FAILED' },
]
watch(statusSeg, (v) => {
  query.status = v === 'DOING' ? 'PARSING' : v === 'ALL' ? '' : v
  query.page = 1
  load()
})

const uploadHeaders = computed(() => authHeaders())
const uploadKbId = ref('')
const kbPickVisible = ref(false)

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await listDocuments({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      page: query.page,
      size: query.size,
    })
    items.value = res.items
    total.value = res.total
    ensurePolling()
  } catch { /* toast handled */ } finally {
    if (!silent) loading.value = false
  }
}

function reload() {
  query.page = 1
  load()
}

/* 处理中状态轮询 */
let pollTimer: number | undefined
function ensurePolling() {
  const busy = items.value.some((d) => d.status === 'PARSING' || d.status === 'PENDING')
  window.clearInterval(pollTimer)
  if (busy) {
    pollTimer = window.setInterval(() => load(true), 2500)
  }
}
onBeforeUnmount(() => window.clearInterval(pollTimer))

onMounted(async () => {
  load()
  try { kbs.value = await listKnowledgeBases() } catch { /* ignore */ }
  if (kbs.value.length) uploadKbId.value = kbs.value[0].id
  if (route.query.upload === '1' && canWrite) {
    kbPickVisible.value = true
  }
})

watch(() => route.query, (q) => {
  if (q.keyword !== undefined) {
    query.keyword = String(q.keyword)
    reload()
  }
  if (q.upload === '1' && canWrite) kbPickVisible.value = true
})

function doUpload() {
  if (!uploadKbId.value) {
    ElMessage.warning('请先选择目标知识库')
    return false
  }
  return true
}
function onUploadSuccess(data: DocumentItem) {
  ElMessage.success(`「${data.name || '文档'}」已进入解析队列`)
  kbPickVisible.value = false
  load()
}
function onUploadError(err: Error) {
  ElMessage.error(`上传失败：${err.message}`)
}

async function retry(row: DocumentItem) {
  try {
    await retryDocument(row.id)
    ElMessage.success('已创建新的解析任务')
    load(true)
    ensurePolling()
  } catch { /* handled */ }
}

async function remove(row: DocumentItem) {
  await ElMessageBox.confirm(`确定删除文档「${row.name}」？`, '删除确认', { type: 'warning' })
  try {
    await deleteDocument(row.id)
    ElMessage.success('已删除')
    load()
  } catch { /* handled */ }
}

async function batchDelete() {
  await ElMessageBox.confirm(`确定删除选中的 ${selection.value.length} 个文档？`, '批量删除', { type: 'warning' })
  try {
    for (const d of selection.value) await deleteDocument(d.id)
    ElMessage.success('批量删除完成')
    load()
  } catch { /* handled */ }
}

const labels: Record<DocStatus, string> = { PENDING: '待处理', PARSING: '解析中', READY: '已入库', FAILED: '失败' }
function statusLabel(s: DocStatus) { return labels[s] || s }
/** 后端 IngestService 按阶段写 stage，这里只做展示文案映射 */
const stageLabels: Record<string, string> = {
  queued: '排队中',
  extracting: '抽取正文',
  chunking: '切分文本',
  embedding: '向量化',
  writing: '写入向量库',
  done: '完成',
  failed: '失败',
}
function stageLabel(s?: string | null) { return s ? stageLabels[s] ?? s : '处理中' }
function pillClass(s: DocStatus) {
  return { PENDING: 'pill--pending', PARSING: 'pill--parsing', READY: 'pill--ready', FAILED: 'pill--failed' }[s]
}
function fmt(iso: string) {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.doc-page {
  padding: 16px;
  /* 占满内容区高度，纵向滚动交给表格内部，页面本身不再整体滚 */
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.doc-page__head {
  margin-bottom: 12px;
}
.page-title {
  font-size: 18px;
  margin: 0;
}
.doc-page__toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  padding: 12px;
}
.doc-page__kw {
  width: 240px;
}
.doc-page__ops {
  margin-left: auto;
  display: flex;
  gap: 8px;
  align-items: center;
}
.doc-page__table {
  padding: 8px;
  flex: 1 1 auto;
  min-height: 260px; /* 窗口太矮时退回整页滚动，不把表格压成一两条高 */
  display: flex;
  flex-direction: column;
}
/* 高度沿 el-table → inner-wrapper 传到 body-wrapper（EP 给它 flex:1），
   于是横向滚动条贴在表格底边、始终在视口内，而不是掉到页面最底下 */
.doc-page__table :deep(.el-table) {
  flex: 1 1 auto;
  min-height: 0;
}
.doc-page__table :deep(.el-table__inner-wrapper) {
  height: 100%;
}
.doc-page__pager {
  margin-top: 10px;
  flex: 0 0 auto;
  justify-content: flex-end;
}
.doc-status {
  display: flex;
  align-items: center;
  gap: 8px;
}
.doc-status__bar {
  width: 70px;
}
.doc-status__stage {
  font-size: var(--font-aux);
  color: #7a857f;
  white-space: nowrap;
}
</style>
