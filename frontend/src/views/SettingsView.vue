<template>
  <div class="set-page">
    <h1 class="page-title set-page__title">系统设置</h1>

    <!-- 条目列表 -->
    <div class="set-page__list">
      <button v-for="item in entries" :key="item.key" class="set-entry zd-card" @click="openEntry(item.key)">
        <span class="set-entry__icon" :style="{ background: item.bg }">
          <el-icon :size="18" color="#fff"><component :is="item.icon" /></el-icon>
        </span>
        <span class="set-entry__body">
          <span class="set-entry__title">{{ item.title }}</span>
          <span class="set-entry__summary muted">{{ item.summary() }}</span>
        </span>
        <span class="set-entry__status" :class="`dot--${item.status().state}`" :title="item.status().text">
        </span>
        <span class="muted set-entry__status-text">{{ item.status().text }}</span>
        <el-icon class="set-entry__arrow"><ArrowRight /></el-icon>
      </button>
    </div>

    <!-- 模型服务 -->
    <el-drawer v-model="modelsVisible" title="大模型 / Embedding 服务" size="440px">
      <el-form label-position="top" v-loading="modelsLoading">
        <el-form-item label="Base URL">
          <el-input v-model="modelsForm.chat.baseUrl" placeholder="https://api.example.com/v1，留空使用 Mock" />
        </el-form-item>
        <el-form-item label="对话模型名">
          <el-input v-model="modelsForm.chat.model" placeholder="qwen-plus" />
        </el-form-item>
        <el-form-item label="温度">
          <el-slider v-model="modelsForm.chat.temperature" :min="0" :max="2" :step="0.05" show-input />
          <p class="form-hint muted">后端只接受 0-2（超出保存时报错），滑块范围与之一致。</p>
        </el-form-item>
        <el-form-item label="Embedding 模型">
          <el-input v-model="modelsForm.embedding.model" placeholder="服务商实际提供的 embedding 模型名" />
          <p class="form-hint muted">模型名会原样发给 embedding 接口；写错不会立刻报错，只会在下次入库/检索时失败。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelsVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveModels">保存</el-button>
      </template>
    </el-drawer>

    <!-- RAG 策略 -->
    <el-drawer v-model="ragVisible" title="RAG 检索策略" size="440px">
      <el-form label-position="top" v-loading="ragLoading">
        <el-form-item label="查询重写">
          <el-switch v-model="ragForm.queryRewrite" />
        </el-form-item>
        <el-form-item label="多查询扩展数量">
          <el-input-number v-model="ragForm.multiQueryCount" :min="1" :max="5" />
          <p class="form-hint muted">后端只接受 1-5。</p>
        </el-form-item>
        <el-form-item label="TopK">
          <el-input-number v-model="ragForm.topK" :min="1" :max="20" />
          <p class="form-hint muted">后端只接受 1-20；引用卡片最多取前 4 个分块。</p>
        </el-form-item>
        <el-form-item label="相似度阈值">
          <el-slider v-model="ragForm.scoreThreshold" :min="0" :max="1" :step="0.01" show-input />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ragVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRag">保存</el-button>
      </template>
    </el-drawer>

    <!-- 向量存储：只读 -->
    <el-drawer v-model="vectorVisible" title="向量存储" size="440px">
      <el-alert
        :type="health && health.vectorStore === 'pgvector' ? 'success' : 'warning'"
        :closable="false" show-icon
        :title="vectorTitle"
        :description="vectorDesc" />
      <p v-if="health" class="muted">
        降级期排队待回放的向量写入：{{ health.pendingVectorOps }} 条 · 外部依赖探活：{{ health.status }}
      </p>
    </el-drawer>

    <!-- 用户与权限 -->
    <el-drawer v-model="usersVisible" title="用户与权限" size="720px">
      <div class="set-users__head">
        <el-button type="primary" size="small" :icon="Plus" @click="openUserCreate">新增用户</el-button>
        <span class="muted">角色变更将写入审计日志</span>
      </div>
      <!-- max-height：抽屉里用户一多时滚动发生在表格内部，横向滚动条不会沉到抽屉外 -->
      <el-table :data="users" v-loading="usersLoading" size="small" max-height="calc(100vh - 200px)" scrollbar-always-on>
        <el-table-column prop="name" label="姓名" width="110" />
        <el-table-column prop="account" label="账号" min-width="160" />
        <el-table-column label="角色" width="150">
          <template #default="{ row }">
            <el-tooltip :disabled="!isSelf(row)" content="不能修改自己的角色，请用另一个系统管理员账号操作" placement="top">
              <el-select v-model="row.role" size="small" :disabled="isSelf(row)" @change="(v: Role) => changeRole(row, v)">
                <el-option label="普通员工" value="EMPLOYEE" />
                <el-option label="知识管理员" value="KM_ADMIN" />
                <el-option label="系统管理员" value="SYS_ADMIN" />
              </el-select>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="dept" label="部门" width="120" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tooltip :disabled="!isSelf(row)" content="不能停用当前登录账号" placement="top">
              <el-switch :model-value="row.enabled" size="small" :disabled="isSelf(row)" @change="(v: boolean) => toggleUser(row, v)" />
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" text type="danger" :disabled="isSelf(row)" @click="removeUser(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-dialog v-model="userCreateVisible" title="新增用户" width="420px" append-to-body>
        <el-form label-width="80px">
          <el-form-item label="姓名"><el-input v-model="userForm.name" /></el-form-item>
          <el-form-item label="账号"><el-input v-model="userForm.account" placeholder="邮箱或工号" /></el-form-item>
          <el-form-item label="工号"><el-input v-model="userForm.empNo" placeholder="选填；账号为邮箱时可用工号登录" /></el-form-item>
          <el-form-item label="密码">
            <el-input v-model="userForm.password" type="password" show-password placeholder="6-32 位" />
          </el-form-item>
          <el-form-item label="部门"><el-input v-model="userForm.dept" /></el-form-item>
          <el-form-item label="角色">
            <el-select v-model="userForm.role" style="width: 100%">
              <el-option label="普通员工" value="EMPLOYEE" />
              <el-option label="知识管理员" value="KM_ADMIN" />
              <el-option label="系统管理员" value="SYS_ADMIN" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="userCreateVisible = false">取消</el-button>
          <el-button type="primary" @click="createUserSubmit">创建</el-button>
        </template>
      </el-dialog>
    </el-drawer>

    <!-- 审计日志 -->
    <el-drawer v-model="auditVisible" title="审计日志" size="720px">
      <el-table :data="auditLogs" v-loading="auditLoading" size="small" scrollbar-always-on>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmt(row.at) }}</template>
        </el-table-column>
        <el-table-column prop="actor" label="操作人" width="110" />
        <el-table-column prop="action" label="动作" width="130" />
        <el-table-column prop="target" label="对象" min-width="140" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="160" show-overflow-tooltip />
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight, Plus } from '@element-plus/icons-vue'
import {
  createUser, deleteUser, getModels, getRag, listAuditLogs, listUsers, putModels, putRag, updateUser,
} from '@/api/settings'
import { getHealth } from '@/api/monitor'
import type { AuditLog, HealthInfo, ManagedUser, ModelsSetting, RagSetting, Role } from '@/types/api'
import { useUserStore } from '@/stores/user'

type EntryKey = 'models' | 'vector' | 'rag' | 'users' | 'audit' | 'embedding'

const models = ref<ModelsSetting | null>(null)
const rag = ref<RagSetting | null>(null)
const users = ref<ManagedUser[]>([])
const auditLogs = ref<AuditLog[]>([])
const saving = ref(false)
const health = ref<HealthInfo | null>(null)

async function loadHealth() {
  try { health.value = await getHealth() } catch { health.value = null }
}

const vectorTitle = computed(() => {
  const h = health.value
  if (!h) return '向量库状态未知（/api/health 未响应）'
  return h.vectorStore === 'pgvector' ? 'PostgreSQL + pgvector（持久化向量库）' : '进程内向量存储（已降级）'
})
const vectorDesc = computed(() => {
  const h = health.value
  if (!h) return '取不到探活结果时不做任何推断。'
  return h.vectorStore === 'pgvector'
    ? '向量与分块正文写入 PG；连接抖动时自动回退内存实现，后台每 30 秒重探并回放排队的写入。无可配置项。'
    : '当前向量只存在本进程内存里，重启即丢，检索质量不保证。请检查 PG 连接（PG_HOST/PG_PORT/账号），恢复后无需重启即可自动切回。'
})

const entries = computed<{
  key: EntryKey; title: string; icon: string; bg: string; summary: () => string; status: () => { state: string; text: string }
}[]>(() => [
  {
    key: 'models', title: '大模型服务', icon: 'Cpu', bg: '#12b886',
    summary: () => (models.value ? `${models.value.chat.model} · ${models.value.chat.baseUrl || 'Mock Provider'} · 温度 ${models.value.chat.temperature}` : '加载中…'),
    // 状态取 /api/health 的真实探活结果，"配置读到了"不等于"服务在跑"
    status: () => llmStatus(),
  },
  {
    key: 'embedding', title: 'Embedding 模型', icon: 'DataLine', bg: '#228be6',
    summary: () => (models.value ? models.value.embedding.model : '加载中…'),
    status: () => (health.value
      ? (health.value.llm === 'openai-compatible'
        ? { state: 'ok', text: '真实 embedding' }
        : { state: 'err', text: '已降级哈希向量' })
      : { state: 'idle', text: '未探活' }),
  },
  {
    key: 'vector', title: '向量存储', icon: 'Coin', bg: '#f5a623',
    summary: () => (health.value
      ? (health.value.vectorStore === 'pgvector'
        ? 'PostgreSQL + pgvector'
        : `进程内回退（待回放 ${health.value.pendingVectorOps} 条写入）`)
      : '未取到探活结果'),
    status: () => (health.value
      ? (health.value.vectorStore === 'pgvector' ? { state: 'ok', text: 'pgvector 可用' } : { state: 'err', text: '已降级内存' })
      : { state: 'idle', text: '未探活' }),
  },
  {
    key: 'rag', title: 'RAG 策略', icon: 'MagicStick', bg: '#7c5cd6',
    summary: () => (rag.value ? `topK ${rag.value.topK} · 阈值 ${rag.value.scoreThreshold} · 查询重写${rag.value.queryRewrite ? '开' : '关'} · 多查询 ${rag.value.multiQueryCount}` : '加载中…'),
    status: () => ({ state: rag.value ? 'ok' : 'idle', text: rag.value ? '已生效' : '—' }),
  },
  {
    key: 'users', title: '用户与权限', icon: 'UserFilled', bg: '#2fbf71',
    summary: () => (users.value.length ? `共 ${users.value.length} 名用户 · 管理员 ${users.value.filter((u) => u.role !== 'EMPLOYEE').length} 名` : '点击查看'),
    status: () => ({ state: users.value.length ? 'ok' : 'idle', text: users.value.length ? `${users.value.length} 个账号` : '未加载' }),
  },
  {
    key: 'audit', title: '审计日志', icon: 'Tickets', bg: '#8a948f',
    summary: () => (auditLogs.value.length ? `最近 ${auditLogs.value.length} 条 · 最新：${auditLogs.value[0].action} by ${auditLogs.value[0].actor}` : '暂无记录'),
    status: () => ({ state: auditLogs.value.length ? 'ok' : 'idle', text: auditLogs.value.length ? `${auditLogs.value.length} 条留痕` : '无记录' }),
  },
])

const modelsVisible = ref(false)
const ragVisible = ref(false)
const vectorVisible = ref(false)
const usersVisible = ref(false)
const auditVisible = ref(false)

const modelsLoading = ref(false)
const ragLoading = ref(false)
const usersLoading = ref(false)
const auditLoading = ref(false)

const modelsForm = reactive<ModelsSetting>({ chat: { baseUrl: '', model: '', temperature: 0.3 }, embedding: { model: 'bge-large-zh' } })
const ragForm = reactive<RagSetting>({ queryRewrite: true, multiQueryCount: 3, topK: 5, scoreThreshold: 0.3 })

function openEntry(key: EntryKey) {
  if (key === 'models' || key === 'embedding') {
    if (models.value) Object.assign(modelsForm, JSON.parse(JSON.stringify(models.value)))
    modelsVisible.value = true
  } else if (key === 'rag') {
    if (rag.value) Object.assign(ragForm, rag.value)
    ragVisible.value = true
  } else if (key === 'vector') {
    vectorVisible.value = true
  } else if (key === 'users') {
    usersVisible.value = true
    loadUsers()
  } else if (key === 'audit') {
    auditVisible.value = true
    loadAudit()
  }
}

async function loadModels() {
  modelsLoading.value = true
  try {
    models.value = await getModels()
    Object.assign(modelsForm, JSON.parse(JSON.stringify(models.value)))
  } catch { /* handled */ } finally { modelsLoading.value = false }
}
async function loadRag() {
  ragLoading.value = true
  try {
    rag.value = await getRag()
    Object.assign(ragForm, rag.value)
  } catch { /* handled */ } finally { ragLoading.value = false }
}
async function loadUsers() {
  usersLoading.value = true
  try { users.value = await listUsers() } catch { /* handled */ } finally { usersLoading.value = false }
}
async function loadAudit() {
  auditLoading.value = true
  try { auditLogs.value = await listAuditLogs() } catch { /* handled */ } finally { auditLoading.value = false }
}

function llmStatus() {
  if (!health.value) return { state: 'idle', text: '未探活' }
  return health.value.llm === 'openai-compatible'
    ? { state: 'ok', text: '真实模型可用' }
    : { state: 'err', text: '已降级 Mock' }
}

onMounted(() => {
  loadModels()
  loadRag()
  loadUsers()
  loadAudit()
  loadHealth()
})

async function saveModels() {
  saving.value = true
  try {
    models.value = await putModels(JSON.parse(JSON.stringify(modelsForm)))
    ElMessage.success('已保存，配置热生效')
    modelsVisible.value = false
  } catch { /* handled */ } finally { saving.value = false }
}
async function saveRag() {
  saving.value = true
  try {
    rag.value = await putRag({ ...ragForm })
    ElMessage.success('已保存，配置热生效')
    ragVisible.value = false
  } catch { /* handled */ } finally { saving.value = false }
}

const userStore = useUserStore()
/** 改自己的角色/停用自己会把管理员关在门外，后端同样会拒绝 */
function isSelf(row: ManagedUser) {
  return !!userStore.user && row.id === userStore.user.id
}

async function changeRole(row: ManagedUser, role: Role) {  try {
    await updateUser(row.id, { role })
    ElMessage.success(`${row.name} 角色已更新，已写入审计日志`)
    loadAudit()
  } catch {
    loadUsers()
  }
}
async function toggleUser(row: ManagedUser, enabled: boolean) {
  try {
    await updateUser(row.id, { enabled })
    row.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已禁用')
  } catch { loadUsers() }
}
async function removeUser(row: ManagedUser) {
  await ElMessageBox.confirm(`确定删除用户「${row.name}」？`, '删除用户', { type: 'warning' })
  try {
    await deleteUser(row.id)
    ElMessage.success('已删除')
    loadUsers()
  } catch { /* handled */ }
}

const userCreateVisible = ref(false)
const userForm = reactive({ name: '', account: '', empNo: '', password: '', dept: '', role: 'EMPLOYEE' as Role })
function openUserCreate() {
  Object.assign(userForm, { name: '', account: '', empNo: '', password: '', dept: '', role: 'EMPLOYEE' })
  userCreateVisible.value = true
}
async function createUserSubmit() {
  if (!userForm.name || !userForm.account) {
    ElMessage.warning('姓名与账号必填')
    return
  }
  if (userForm.password.length < 6 || userForm.password.length > 32) {
    ElMessage.warning('密码需为 6-32 位')
    return
  }
  try {
    await createUser({
      name: userForm.name,
      account: userForm.account,
      empNo: userForm.empNo || undefined,
      password: userForm.password,
      dept: userForm.dept,
      role: userForm.role,
      enabled: true,
    } as Omit<ManagedUser, 'id'>)
    ElMessage.success('用户已创建')
    userCreateVisible.value = false
    loadUsers()
    loadAudit()
  } catch { /* handled */ }
}

function fmt(iso: string) {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN')
}
</script>

<style scoped>
.set-page {
  padding: 16px;
}
.set-page__title {
  font-size: 18px;
  margin: 0 0 14px;
}
.set-page__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 860px;
}
.set-entry {
  display: flex;
  align-items: center;
  gap: 14px;
  text-align: left;
  cursor: pointer;
  transition: box-shadow 0.15s, border-color 0.15s;
  font: inherit;
  color: inherit;
}
.set-entry:hover {
  border-color: var(--brand);
  box-shadow: 0 2px 10px rgba(18, 184, 134, 0.12);
}
.set-entry:focus-visible {
  outline: 2px solid var(--brand);
}
.set-entry__icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 40px;
}
.set-entry__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.set-entry__title {
  font-size: var(--font-title);
  font-weight: 600;
}
.set-entry__summary {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.set-entry__status {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex: 0 0 10px;
}
.form-hint {
  font-size: var(--font-aux);
  margin: 4px 0 0;
  line-height: 1.4;
}
.dot--ok { background: var(--success); box-shadow: 0 0 0 3px rgba(47, 191, 113, 0.18); }
.dot--idle { background: #b9c6c0; }
.dot--err { background: var(--danger); }
.set-entry__status-text { min-width: 48px; text-align: right; }
.set-entry__arrow { color: #9aa6a0; }
.set-users__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
</style>
