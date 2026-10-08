<template>
  <el-dialog
    :model-value="modelValue"
    title="选择评测题目"
    width="720px"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @open="load"
  >
    <p class="muted qc-note">
      只有「已复核且启用」的题目可以参与计分。未复核的题在题库页（评测中心 › 评测题库）确认后才会出现在这里。
      检索按<b>你当前账号可见的知识库</b>执行，并走问答同一条严格链路——拿不到真实 embedding 就记为失败，不会改用哈希假向量算分。
      期望文档在机密库里时，需由系统管理员发起才会被检到。
    </p>
    <el-radio-group v-model="mode" class="qc-mode">
      <el-radio value="all">全部已复核且启用（{{ rows.length }} 题）</el-radio>
      <el-radio value="custom">手动勾选（{{ selection.length }} 题）</el-radio>
    </el-radio-group>

    <el-table
      v-if="mode === 'custom'"
      ref="tableRef"
      v-loading="loading"
      :data="rows"
      row-key="id"
      size="small"
      max-height="340"
      @selection-change="(r: GoldenQuestion[]) => (selection = r)"
    >
      <el-table-column type="selection" width="42" reserve-selection />
      <el-table-column prop="question" label="题干" min-width="260" show-overflow-tooltip />
      <el-table-column prop="expectedDoc" label="期望文档" min-width="180" show-overflow-tooltip />
    </el-table>
    <el-empty v-else-if="!loading && !rows.length" description="没有已复核的题目" :image-size="60" />

    <template #footer>
      <span class="muted qc-foot">本次跑 {{ willRun }} 题</span>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="willRun === 0" @click="confirm">开始评测</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { TableInstance } from 'element-plus'
import { listQuestions } from '@/api/eval'
import type { GoldenQuestion } from '@/types/api'

defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; confirm: [string[]] }>()

const tableRef = ref<TableInstance>()
const rows = ref<GoldenQuestion[]>([])
const selection = ref<GoldenQuestion[]>([])
const loading = ref(false)
const mode = ref<'all' | 'custom'>('all')

const willRun = computed(() => (mode.value === 'all' ? rows.value.length : selection.value.length))

async function load() {
  loading.value = true
  selection.value = []
  try {
    const all = await listQuestions('reviewed')
    rows.value = all.filter((q) => q.enabled)
    if (mode.value === 'custom') await selectAll()
  } catch { /* handled */ } finally {
    loading.value = false
  }
}

/** 默认整套勾选，跑子集才是例外；表格只在 custom 模式下挂载 */
async function selectAll() {
  await nextTick()
  tableRef.value?.clearSelection()
  rows.value.forEach((r) => tableRef.value?.toggleRowSelection(r, true))
}

watch(mode, (m) => {
  if (m === 'custom') selectAll()
})

function confirm() {
  if (mode.value === 'all') {
    if (!rows.value.length) {
      ElMessage.warning('当前没有已复核且启用的题目，请先到评测题库出题并复核')
      return
    }
    // 空数组 = 后端按「全部已复核且启用」取题，避免前端把 id 列表截断成另一套口径
    emit('confirm', [])
  } else {
    if (!selection.value.length) return
    emit('confirm', selection.value.map((q) => q.id))
  }
  emit('update:modelValue', false)
}
</script>

<style scoped>
.qc-note {
  margin: 0 0 10px;
  font-size: var(--font-aux);
  line-height: 1.6;
}
.qc-mode {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  margin-bottom: 10px;
}
.qc-foot {
  float: left;
  font-size: var(--font-aux);
  line-height: 32px;
}
</style>
