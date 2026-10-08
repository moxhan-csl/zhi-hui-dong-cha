<template>
  <div class="kb-page">
    <div class="kb-page__head">
      <h1 class="page-title">知识库</h1>
      <div v-if="canWrite" class="kb-page__ops">
        <el-button :icon="Upload" @click="importChanges">导入变更</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建知识库</el-button>
      </div>
    </div>

    <div class="kb-page__stats">
      <StatCard label="总分块数" :value="fmtNum(overview?.totalChunks)" unit="块" icon="Grid" />
      <StatCard label="知识库数量" :value="overview?.kbCount ?? '—'" unit="个" icon="Collection" />
      <StatCard label="文档向量化比例" :value="pct(overview?.vectorRate)" :unit="rateUnit" icon="DataLine" />
      <StatCard label="当日模型 Tokens" :value="fmtNum(overview?.dailyLlmTokens)" unit="tokens" icon="Coin" />
    </div>

    <div v-loading="loading" class="kb-page__grid">
      <div v-for="kb in bases" :key="kb.id" class="kb-card zd-card">
        <div class="kb-card__head">
          <span class="kb-card__name" :title="kb.name">{{ kb.name }}</span>
          <el-popover v-if="kb.status === 'disconnected'" trigger="hover" width="240">
            <template #reference>
              <el-tag size="small" type="danger" effect="light">
                <el-icon><CircleCloseFilled /></el-icon> 断开
              </el-tag>
            </template>
            <div class="muted">向量库当前不可达，检索已回退进程内实现（重启即丢，且不参与相似度索引）。后台每 30 秒重探一次，探通后自动回放降级期间排队的写入。</div>
          </el-popover>
          <el-tag v-else-if="kb.status === 'syncing'" size="small" type="warning" effect="light" class="kb-card__sync">
            <el-icon class="is-loading"><Refresh /></el-icon> 同步中
          </el-tag>
          <el-tag v-else size="small" type="success" effect="light">
            <el-icon><Link /></el-icon> 已连接
          </el-tag>
        </div>
        <div class="kb-card__meta muted">
          {{ kb.chunkCount.toLocaleString() }} 分块 · 所有者 {{ kb.owner }}
        </div>
        <div class="kb-card__tags">
          <el-tag size="small" effect="plain" :type="scopeType(kb.scope)">{{ scopeLabel(kb.scope) }}</el-tag>
        </div>
        <div class="kb-card__cover">
          <div class="kb-card__cover-label">
            <span class="muted">向量覆盖率</span>
            <span class="muted">{{ coverRate(kb) == null ? `暂无文档（${kb.docCount ?? 0} 篇）` : `${coverRate(kb)}% · ${kb.vectorizedCount}/${kb.docCount} 篇` }}</span>
          </div>
          <el-progress
            :percentage="coverRate(kb) ?? 0"
            :stroke-width="8"
            :show-text="false"
            :color="coverColor(kb)"
          />
        </div>
        <div v-if="canWrite" class="kb-card__ops">
          <el-button size="small" text type="primary" :icon="Edit" @click="openEdit(kb)">编辑</el-button>
          <el-button size="small" text type="danger" :icon="Delete" @click="removeKb(kb)">删除</el-button>
        </div>
      </div>
      <el-empty v-if="!loading && !bases.length" description="暂无可见知识库" />
    </div>

    <!-- 新建 / 编辑知识库对话框 -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑知识库' : '新建知识库'" width="480px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="2-30 字" maxlength="30" show-word-limit />
        </el-form-item>
        <el-form-item label="可见范围" prop="scope">
          <el-radio-group v-model="form.scope">
            <el-radio value="public">公开</el-radio>
            <el-radio value="internal">内部</el-radio>
            <el-radio value="dept">部门</el-radio>
            <el-radio :disabled="lockConfidential" value="confidential">机密</el-radio>
          </el-radio-group>
          <div v-if="lockConfidential" class="muted kb-form__tip">
            已存在的知识库不允许改成机密（后端会直接拒绝）。要用机密范围请新建一个知识库。
          </div>
        </el-form-item>
        <el-form-item v-if="form.scope === 'dept' || form.scope === 'confidential'" label="成员" prop="members">
          <el-select v-model="form.members" multiple filterable allow-create default-first-option placeholder="输入账号（邮箱或工号），或 dept:部门名，回车添加" style="width: 100%" />
          <div class="muted kb-form__tip">后端会校验每一项是否对应真实账号或部门；写错的成员不会静默生效，而是直接报错（错写的结果是那个人永远看不到这个库）。</div>
        </el-form-item>
        <el-form-item label="向量模型">
          <span class="muted">全系统统一使用 {{ embeddingModel || '系统配置的 embedding 模型' }}，不支持按库选择</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ editingId ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Delete, Edit, Plus, Upload } from '@element-plus/icons-vue'
import StatCard from '@/components/common/StatCard.vue'
import { createKnowledgeBase, deleteKnowledgeBase, getOverview, updateKnowledgeBase } from '@/api/knowledge'
import { getModels } from '@/api/settings'
import { useUserStore } from '@/stores/user'
import type { KbOverview, KbScope, KnowledgeBase } from '@/types/api'

const router = useRouter()
const userStore = useUserStore()
/** 后端矩阵：知识库 GET 对全部登录用户放行（按可见库过滤），写操作需 KM_ADMIN+（D-6） */
const canWrite = userStore.atLeast('KM_ADMIN')
const loading = ref(false)
const overview = ref<KbOverview | null>(null)
const bases = computed(() => overview.value?.bases ?? [])

async function load() {
  loading.value = true
  try {
    overview.value = await getOverview()
  } catch { /* toast 已由 request 处理 */ } finally {
    loading.value = false
  }
}
onMounted(load)

function fmtNum(n?: number | null) {
  return n == null ? '—' : n.toLocaleString()
}
function pct(n?: number | null) {
  if (n == null) return '—'
  // 后端 vectorRate 已是 0-100 百分数；null = 当前可见范围内没有文档，没有可算的比率
  return `${n.toFixed(1)}%`
}
/** 分母如实显示出来，避免"100%"看不出是几篇文档算的；后端未上报 totalDocs 时不下判断 */
const rateUnit = computed(() => (overview.value?.totalDocs == null ? '' : `${overview.value.totalDocs} 篇文档`))
/** 向量覆盖率 = 已入库文档 / 该库文档总数（用 chunkCount 当分母是量纲错乱的比值） */
function coverRate(kb: KnowledgeBase): number | null {
  if (!kb.docCount) return null   // 0 篇文档时没有比率可算，不是 0%
  return Math.min(100, Math.round((kb.vectorizedCount / kb.docCount) * 100))
}
function coverColor(kb: KnowledgeBase) {
  const r = coverRate(kb)
  if (r == null) return '#d8e0dc'
  return r >= 90 ? 'var(--success)' : 'var(--brand)'
}
const scopeLabels: Record<KbScope, string> = { public: '公开', internal: '内部', dept: '部门', confidential: '机密' }
function scopeLabel(s: KbScope) { return scopeLabels[s] || s }
function scopeType(s: KbScope) {
  return ({ public: 'info', internal: 'success', dept: 'warning', confidential: 'danger' } as const)[s] || 'info'
}

function importChanges() {
  router.push({ path: '/documents', query: { upload: '1' } })
}

/* 新建 / 编辑（同一个对话框，editingId 决定走 POST 还是 PUT） */
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const originalScope = ref<KbScope | null>(null)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const embeddingModel = ref('')
const form = reactive({
  name: '',
  scope: 'internal' as KbScope,
  members: [] as string[],
})
const rules: FormRules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { min: 2, max: 30, message: '名称需 2-30 字', trigger: 'blur' },
  ],
  scope: [{ required: true, message: '请选择可见范围', trigger: 'change' }],
}

/** 后端 update 直接拒绝把已存在的库改成机密，界面提前把这一档锁住，别让人点了才吃 400 */
const lockConfidential = computed(() => !!editingId.value && originalScope.value !== 'confidential')

function ensureEmbeddingModel() {
  // /api/settings/* 只有 SYS_ADMIN 可读，KM_ADMIN 发这个请求必然换来"权限不足"弹窗，
  // 因此按角色决定是否请求；拿不到时界面显示占位文案。
  if (embeddingModel.value || !userStore.atLeast('SYS_ADMIN')) return
  getModels().then((m) => { embeddingModel.value = m.embedding.model }).catch(() => { /* 显示占位文案 */ })
}

function openCreate() {
  editingId.value = null
  originalScope.value = null
  Object.assign(form, { name: '', scope: 'internal' as KbScope, members: [] as string[] })
  dialogVisible.value = true
  ensureEmbeddingModel()
}

function openEdit(kb: KnowledgeBase) {
  editingId.value = kb.id
  originalScope.value = kb.scope
  Object.assign(form, { name: kb.name, scope: kb.scope, members: [...(kb.members ?? [])] })
  dialogVisible.value = true
  ensureEmbeddingModel()
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!editingId.value && form.scope === 'confidential') {
    try {
      await ElMessageBox.confirm(
        '机密知识库将严格限制访问范围，创建后仅成员可见并全程留痕。确认创建机密知识库？',
        '二次确认',
        { type: 'warning', confirmButtonText: '确认创建', cancelButtonText: '返回修改' }
      )
    } catch {
      return
    }
  }
  submitting.value = true
  try {
    if (editingId.value) {
      await updateKnowledgeBase(editingId.value, { name: form.name, scope: form.scope, members: form.members })
      ElMessage.success('知识库已更新')
    } else {
      await createKnowledgeBase({ ...form })
      ElMessage.success('知识库已创建')
    }
    dialogVisible.value = false
    load()
  } catch { /* handled */ } finally {
    submitting.value = false
  }
}

async function removeKb(kb: KnowledgeBase) {
  const hasDocs = (kb.docCount ?? 0) > 0
  try {
    await ElMessageBox.confirm(
      `确定删除知识库「${kb.name}」？该操作不可撤销。` +
        (hasDocs ? `库里还有 ${kb.docCount} 篇文档，后端会拒绝删库——需先到「文档管理」删除这些文档。` : ''),
      '删除确认',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '返回' }
    )
  } catch {
    return
  }
  try {
    await deleteKnowledgeBase(kb.id)
    ElMessage.success('知识库已删除')
    load()
  } catch { /* handled：后端"库下仍有文档"等原因由请求层 toast 出真实消息 */ }
}
</script>

<style scoped>
.kb-page {
  padding: 16px;
}
.kb-page__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
  flex-wrap: wrap;
  gap: 8px;
}
.page-title {
  font-size: 18px;
  margin: 0;
}
.kb-page__stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
/* el-form-item__content 是 flex，select 占满一行后提示语换行显示 */
.kb-form__tip {
  width: 100%;
  margin-top: 4px;
  font-size: var(--font-aux);
  line-height: 1.5;
}
.kb-page__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
  min-height: 120px;
}
.kb-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: box-shadow 0.15s;
}
.kb-card:hover {
  box-shadow: 0 4px 16px rgba(28, 35, 32, 0.08);
}
.kb-card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.kb-card__name {
  font-size: var(--font-title);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-card__tags {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.kb-card__cover {
  margin-top: 4px;
}
.kb-card__cover-label {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}
.kb-card__ops {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 4px;
  padding-top: 6px;
  border-top: 1px solid var(--border);
}
@media (max-width: 1199px) {
  .kb-page__stats { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 767px) {
  .kb-page__stats { grid-template-columns: 1fr; }
}
</style>
