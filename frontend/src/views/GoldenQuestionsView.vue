<template>
  <div class="gq-page">
    <div class="gq-page__head">
      <h1 class="page-title">评测题库</h1>
      <div class="gq-page__stats">
        <span class="gq-stat">共 <b>{{ stats.total }}</b> 题</span>
        <span class="gq-stat">待复核 <b>{{ stats.draft }}</b></span>
        <span class="gq-stat">已复核 <b>{{ stats.reviewed }}</b></span>
        <span class="gq-stat gq-stat--ok">参与评测 <b>{{ stats.usable }}</b></span>
      </div>
      <div class="gq-page__ops">
        <el-button :icon="Upload" @click="openImport">导入 JSONL</el-button>
        <el-button :icon="Download" @click="onExport">导出 JSONL</el-button>
        <el-button :disabled="!selection.length" @click="batchReview(true)">批量复核</el-button>
        <el-button :disabled="!selection.length" type="danger" plain :icon="Delete" @click="batchDelete">
          批量删除{{ selection.length ? `(${selection.length})` : '' }}
        </el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建考题</el-button>
      </div>
    </div>

    <p class="gq-page__rule">
      题库存在 MySQL <code>golden_questions</code>，这里改的就是评测真正用的题。<b>只有「已复核」且「启用」的题目参与计分</b>：
      题面、期望答案或期望文档任一改动都会自动退回待复核，必须重新确认——否则趋势线会拿旧结论给新考卷打分。
      期望请填写知识库里真实存在的文档名；语料里没有对应文档时，检索准确率会真实地偏低，不会用自证的高分掩盖。
    </p>

    <div class="gq-page__toolbar zd-card">
      <el-input
        v-model="keyword"
        placeholder="搜索题干 / 期望文档"
        :prefix-icon="Search"
        clearable
        class="gq-page__kw"
      />
      <el-segmented v-model="statusSeg" :options="statusOptions" aria-label="状态筛选" />
      <el-checkbox v-model="onlyEnabled">只看启用</el-checkbox>
      <span class="muted gq-page__count">筛选后 {{ filtered.length }} 条</span>
    </div>

    <div class="zd-card gq-page__table">
      <el-table
        v-loading="loading"
        :data="filtered"
        row-key="id"
        size="small"
        scrollbar-always-on
        @selection-change="(rows: GoldenQuestion[]) => (selection = rows)"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="gq-expand">
              <div><b>期望答案要点：</b>{{ row.golden }}</div>
              <div v-if="row.note"><b>备注：</b>{{ row.note }}</div>
              <div class="muted">
                来源 {{ row.source === 'imported' ? '导入' : '手动录入' }} ·
                创建人 {{ row.createdBy || '—' }} ·
                复核人 {{ row.reviewedBy || '未复核' }} ·
                复核时间 {{ fmt(row.reviewedAt) || '—' }}
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="question" label="题干" min-width="240" show-overflow-tooltip />
        <el-table-column prop="expectedDoc" label="期望文档" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.expectedDoc">{{ row.expectedDoc }}</span>
            <el-tooltip v-else content="期望文档为空，这类题不允许标记为已复核" placement="top">
              <span class="gq-missing">未填</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'reviewed' ? 'success' : 'warning'" effect="light">
              {{ row.status === 'reviewed' ? '已复核' : '待复核' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="70">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              size="small"
              @change="(v: boolean) => toggleEnabled(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="120">
          <template #default="{ row }">{{ fmt(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button
              size="small"
              text
              :type="row.status === 'reviewed' ? 'warning' : 'success'"
              @click="reviewOne(row)"
            >
              {{ row.status === 'reviewed' ? '退回' : '复核' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="editVisible" :title="editing ? '编辑考题' : '新建考题'" width="640px">
      <el-form label-width="110px">
        <el-form-item label="题干">
          <el-input v-model="form.question" type="textarea" :rows="2" placeholder="要考模型的问题" />
        </el-form-item>
        <el-form-item label="期望答案要点">
          <el-input v-model="form.golden" type="textarea" :rows="3" placeholder="Judge 据此判断回答是否达标" />
        </el-form-item>
        <el-form-item label="期望文档">
          <el-select
            v-model="form.expectedDoc"
            filterable
            allow-create
            default-first-option
            placeholder="选择知识库里真实存在的文档（检索命中判定用）"
            style="width: 100%"
          >
            <el-option v-for="d in docNames" :key="d" :label="d" :value="d" />
          </el-select>
          <div v-if="!docNames.length" class="muted gq-hint">
            当前知识库没有已入库文档：此时检索准确率必然为 0，请先上传并解析文档，或如实接受低分。
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="2" placeholder="出题依据、口径说明（可选）" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="muted gq-dialog__tip">
          {{ editing ? '改动题面/答案/期望文档会退回待复核' : '新题一律记为待复核' }}
        </span>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 导入 -->
    <el-dialog v-model="importVisible" title="导入 JSONL" width="620px">
      <p class="muted">
        每行一个 JSON 对象，字段 <code>question / golden / expectedDoc / note</code>。
        按题干去重，<b>导入的题目一律作为待复核入库</b>，不会自带「已复核」身份。
      </p>
      <el-input v-model="importText" type="textarea" :rows="8" placeholder='{"question":"...","golden":"...","expectedDoc":"..."}' />
      <el-upload :show-file-list="false" accept=".jsonl,.ndjson,.txt" :auto-upload="false" :on-change="pickFile">
        <el-button :icon="Upload" class="gq-import__file">或选择 .jsonl 文件</el-button>
      </el-upload>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="doImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Download, Plus, Search, Upload } from '@element-plus/icons-vue'
import {
  createQuestion,
  deleteQuestions,
  exportQuestions,
  getGoldenSetInfo,
  importQuestions,
  listQuestions,
  reviewQuestions,
  setQuestionEnabled,
  updateQuestion,
} from '@/api/eval'
import { listDocuments } from '@/api/documents'
import type { GoldenQuestion, GoldenQuestionInput } from '@/types/api'

const loading = ref(false)
const items = ref<GoldenQuestion[]>([])
const selection = ref<GoldenQuestion[]>([])
const docNames = ref<string[]>([])
const keyword = ref('')
const statusSeg = ref('ALL')
const onlyEnabled = ref(false)
const stats = reactive({ total: 0, draft: 0, reviewed: 0, usable: 0 })

const statusOptions = [
  { label: '全部', value: 'ALL' },
  { label: '待复核', value: 'draft' },
  { label: '已复核', value: 'reviewed' },
]

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return items.value.filter((q) => {
    if (onlyEnabled.value && !q.enabled) return false
    if (!kw) return true
    return `${q.question} ${q.expectedDoc}`.toLowerCase().includes(kw)
  })
})

async function load() {
  loading.value = true
  try {
    items.value = await listQuestions()
    await loadStats()
  } catch { /* toast handled */ } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    const s = await getGoldenSetInfo()
    stats.total = s.total
    stats.draft = s.draft
    stats.reviewed = s.reviewed
    stats.usable = s.usable
  } catch { /* 保留上次数字，不编造 */ }
}

/** 期望文档候选取自真实已入库文档，避免填出不存在的考卷目标 */
async function loadDocs() {
  try {
    const res = await listDocuments({ status: 'READY', page: 1, size: 200 })
    docNames.value = res.items.map((d) => d.name)
  } catch { docNames.value = [] }
}

/* ===== 新建 / 编辑 ===== */
const editVisible = ref(false)
const editing = ref<GoldenQuestion | null>(null)
const saving = ref(false)
const form = reactive<Required<GoldenQuestionInput>>({
  question: '',
  golden: '',
  expectedDoc: '',
  note: '',
  enabled: true,
})

function openCreate() {
  editing.value = null
  Object.assign(form, { question: '', golden: '', expectedDoc: '', note: '', enabled: true })
  editVisible.value = true
}

function openEdit(row: GoldenQuestion) {
  editing.value = row
  Object.assign(form, {
    question: row.question,
    golden: row.golden,
    expectedDoc: row.expectedDoc || '',
    note: row.note || '',
    enabled: row.enabled,
  })
  editVisible.value = true
}

async function save() {
  if (!form.question.trim()) {
    ElMessage.warning('题干不能为空')
    return
  }
  if (!form.golden.trim()) {
    ElMessage.warning('期望答案要点不能为空')
    return
  }
  saving.value = true
  try {
    const payload: GoldenQuestionInput = {
      question: form.question,
      golden: form.golden,
      expectedDoc: form.expectedDoc,
      note: form.note,
      enabled: form.enabled,
    }
    const res = editing.value ? await updateQuestion(editing.value.id, payload) : await createQuestion(payload)
    ElMessage.success(res.status === 'reviewed' ? '已保存' : '已保存（待复核，复核后才参与评测）')
    editVisible.value = false
    load()
  } catch { /* handled */ } finally {
    saving.value = false
  }
}

/* ===== 复核 / 启用 / 删除 ===== */
async function reviewOne(row: GoldenQuestion) {
  const toReview = row.status !== 'reviewed'
  try {
    await reviewQuestions([row.id], toReview)
    ElMessage.success(toReview ? '已确认该题参与评测' : '已退回待复核')
    load()
  } catch { /* handled */ }
}

async function batchReview(reviewed: boolean) {
  const ids = selection.value.map((q) => q.id)
  if (reviewed) {
    const missing = ids.filter((id) => !selection.value.find((q) => q.id === id)?.expectedDoc)
    if (missing.length) {
      ElMessage.warning(`${missing.length} 条未填期望文档，后端会拒绝复核，请先补全`)
      return
    }
  }
  await ElMessageBox.confirm(
    reviewed
      ? `确认这 ${ids.length} 道题可以用作评测？确认后它们会进入评测计分范围。`
      : `退回这 ${ids.length} 道题待复核？退回后不再参与评测计分。`,
    reviewed ? '复核确认' : '退回确认',
    { type: 'warning' }
  )
  try {
    const res = await reviewQuestions(ids, reviewed)
    ElMessage.success(`已${reviewed ? '复核' : '退回'} ${res.count} 条`)
    load()
  } catch { /* handled */ }
}

async function toggleEnabled(row: GoldenQuestion, v: boolean) {
  try {
    await setQuestionEnabled(row.id, v)
    row.enabled = v
    loadStats()
  } catch { /* handled */ }
}

async function batchDelete() {
  const ids = selection.value.map((q) => q.id)
  await ElMessageBox.confirm(
    `确定删除选中的 ${ids.length} 道题？历史评测记录里已经快照下来的题目不受影响。`,
    '批量删除',
    { type: 'warning' }
  )
  try {
    const res = await deleteQuestions(ids)
    ElMessage.success(`已删除 ${res.deleted} 条`)
    load()
  } catch { /* handled */ }
}

/* ===== 导入导出 ===== */
const importVisible = ref(false)
const importText = ref('')
const importing = ref(false)

function openImport() {
  importText.value = ''
  importVisible.value = true
}

function pickFile(file: { raw?: File }) {
  const raw = file.raw
  if (!raw) return
  const reader = new FileReader()
  reader.onload = () => {
    importText.value = String(reader.result || '')
    ElMessage.success(`已读取 ${raw.name}，${importText.value.split('\n').filter((l) => l.trim()).length} 行`)
  }
  reader.onerror = () => ElMessage.error('文件读取失败')
  reader.readAsText(raw, 'utf-8')
}

async function doImport() {
  if (!importText.value.trim()) {
    ElMessage.warning('请粘贴内容或选择文件')
    return
  }
  importing.value = true
  try {
    const res = await importQuestions(importText.value)
    ElMessage.success(
      `导入 ${res.created} 条（全部待复核）；重复跳过 ${res.skippedDuplicate} 条，格式不符 ${res.skippedInvalid} 条`
    )
    importVisible.value = false
    load()
  } catch { /* handled */ } finally {
    importing.value = false
  }
}

async function onExport() {
  try {
    await exportQuestions()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function fmt(ts?: number) {
  if (!ts) return ''
  const d = new Date(ts)
  return Number.isNaN(d.getTime()) ? String(ts) : d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

onMounted(() => {
  load()
  loadDocs()
})
</script>

<style scoped>
.gq-page {
  padding: 16px;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.gq-page__head {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.page-title {
  font-size: 18px;
  margin: 0;
}
.gq-page__stats {
  display: flex;
  gap: 10px;
  font-size: var(--font-aux);
  color: #5c6a64;
}
.gq-stat b {
  color: var(--text);
}
.gq-stat--ok b {
  color: var(--brand);
}
.gq-page__ops {
  margin-left: auto;
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.gq-page__rule {
  margin: 0 0 10px;
  font-size: var(--font-aux);
  line-height: 1.7;
  color: #5c6a64;
  background: var(--brand-soft);
  border-radius: 8px;
  padding: 8px 12px;
}
.gq-page__toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  padding: 12px;
}
.gq-page__kw {
  width: 240px;
}
.gq-page__count {
  margin-left: auto;
}
.gq-page__table {
  padding: 8px;
  flex: 1 1 auto;
  min-height: 240px;
  display: flex;
  flex-direction: column;
}
.gq-page__table :deep(.el-table) {
  flex: 1 1 auto;
  min-height: 0;
}
.gq-page__table :deep(.el-table__inner-wrapper) {
  height: 100%;
}
.gq-expand {
  padding: 8px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}
.gq-missing {
  color: var(--danger);
}
.gq-hint {
  font-size: var(--font-aux);
  line-height: 1.6;
}
.gq-dialog__tip {
  float: left;
  font-size: var(--font-aux);
}
.gq-import__file {
  margin-top: 10px;
}
</style>
