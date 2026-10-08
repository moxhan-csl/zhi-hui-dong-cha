<template>
  <div class="eval-page">
    <div class="eval-page__head">
      <h1 class="page-title">评测中心</h1>
      <div>
        <el-button :icon="Collection" @click="router.push('/eval/questions')">评测题库</el-button>
        <el-button :icon="Files" @click="bankInfo">题库实况</el-button>
        <el-button type="primary" :icon="VideoPlay" :loading="running" @click="runNow">
          {{ running ? '评测运行中…' : '运行评测' }}
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="usableCount === 0"
      class="eval-page__alert"
      type="warning"
      :closable="false"
      show-icon
      title="题库里没有「已复核且启用」的题目，评测不会出分"
      description="先到「评测题库」录入考题并逐条复核——没有已复核考卷时跑出来的分数没有测量含义，系统选择直接不出分，而不是给一个自证清白的高分。"
    />

    <div class="eval-page__main" :class="{ 'is-single': isNarrow }">
      <!-- 左栏：5 指标 -->
      <section class="zd-card eval-metrics">
        <h2 class="eval-sec__title">质量指标（最近一次完成）</h2>
        <div v-for="m in metricDefs" :key="m.key" class="eval-metric">
          <div class="eval-metric__row">
            <span class="eval-metric__name">{{ m.label }}</span>
            <span class="eval-metric__val" :class="undetermined(m) ? 'na' : pass(m) ? 'ok' : 'bad'">
              {{ undetermined(m) ? '未判定' : fmtPct(metricValue(m)) }}
              <el-icon v-if="latestMetrics && !undetermined(m)"><component :is="pass(m) ? 'Top' : 'Bottom'" /></el-icon>
              <span class="muted">（目标 {{ m.targetLabel }}）</span>
            </span>
          </div>
          <el-progress
            :percentage="pctValue(m)"
            :stroke-width="10"
            :show-text="false"
            :color="undetermined(m) ? '#d8e0dc' : pass(m) ? 'var(--success)' : 'var(--danger)'"
          />
        </div>
        <p v-if="latestMetrics" class="eval-metrics__note">
          本次 {{ latestMetrics.sampleCount }} 题：检索准确率 / 引用完整率按规则全量统计；
          相关度 / 忠实度 / 幻觉率只统计模型判定成功的样本——其中 {{ latestMetrics.judgedByLlm }} 题由 LLM Judge 打分，
          未打分的题只出现在明细里并标注 rule-fallback，不混进上面的平均值。
        </p>
        <el-alert
          v-if="retrievalFailures > 0"
          class="eval-metrics__warn"
          type="warning"
          :closable="false"
          show-icon
          :title="`本次有 ${retrievalFailures} 题检索链路失败，答案为空`"
          description="失败题仍计入分母，所以检索准确率与引用完整率会被拖低；这不是内容质量问题，而是 embedding/向量库当时不可用。分数要重新跑一次才算数。"
        />
        <p v-if="!latestMetrics" class="muted">尚无已完成的评测运行</p>
      </section>

      <!-- 右栏：三阶段趋势 -->
      <section class="zd-card">
        <h2 class="eval-sec__title">评测趋势（历次运行原值）</h2>
        <p class="eval-sec__note">每根柱子取自一次已完成运行的原始指标，未做归一化或反向换算；幻觉率越低越好，未判定的运行不画柱。</p>
        <el-alert
          v-if="mixedSets"
          class="eval-trend__warn"
          type="info"
          :closable="false"
          show-icon
          :title="`趋势里出现了 ${mixedSets} 种不同题集，跨题集的柱子不可直接比较`"
          description="每次运行都会快照当时的题目集合；换了考题等于换了一次测量，分数涨跌不能归因于系统本身。"
        />
        <EChart :option="trendOption" height="290px" aria-label="历次评测运行指标柱状图" />
      </section>
    </div>

    <!-- 评测报告 -->
    <section class="zd-card eval-report">
      <h2 class="eval-sec__title">评测报告</h2>
      <div class="eval-report__body">
        <el-table
          :data="runs"
          highlight-current-row
          class="eval-report__runs"
          @current-change="selectRun"
        >
          <el-table-column label="运行时间" prop="createdAt" :formatter="(_r: unknown, _c: unknown, v: string) => fmt(v)" width="108" />
          <el-table-column label="状态" width="68">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="题量" width="56">
            <template #default="{ row }">{{ row.questionCount ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="发起人 / 环境" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.env" :class="{ 'eval-env--degraded': row.env.degraded }">{{ envBrief(row.env) }}</span>
              <span v-else class="muted">旧运行（无环境快照）</span>
            </template>
          </el-table-column>
          <el-table-column label="检索准确率" width="96">
            <template #default="{ row }">{{ fmtMetric(row.metrics?.retrievalPrecision) }}</template>
          </el-table-column>
          <el-table-column label="幻觉率" width="72">
            <template #default="{ row }">{{ fmtMetric(row.metrics?.hallucinationRate) }}</template>
          </el-table-column>
        </el-table>

        <div class="eval-report__detail">
          <template v-if="currentRun">
            <div class="eval-report__detail-head">
              <span>
                逐样本打分明细（{{ samples.length }} 条）
                <span v-if="currentRun?.questionSignature" class="muted">· 题集 {{ currentRun.questionSignature }}</span>
              </span>
              <el-button size="small" :icon="Download" @click="download">导出 JSONL</el-button>
            </div>
            <p v-if="currentRun?.env" class="eval-sec__note">{{ envDetail(currentRun.env) }}</p>
            <el-alert
              v-if="currentRun?.status === 'failed'"
              class="eval-trend__warn"
              type="error"
              :closable="false"
              show-icon
              title="本次运行未出分"
              :description="currentRun.failReason || '失败原因未记录（该运行早于环境快照改动）'"
            />
            <el-table
              :data="samples"
              v-loading="detailLoading"
              size="small"
              class="eval-report__samples"
              :max-height="isNarrow ? 420 : undefined"
              scrollbar-always-on
            >
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="eval-sample__diff">
                    <div><b>标准答案：</b>{{ row.golden }}</div>
                    <div><b>模型回答：</b>{{ row.answer }}</div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
              <el-table-column v-for="k in scoreKeys" :key="k" :label="shortLabel(k)" width="90">
                <template #default="{ row }">{{ fmtMetric(row.scores?.[k]) }}</template>
              </el-table-column>
              <el-table-column label="判定" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.passed ? 'success' : 'danger'" effect="plain">
                    {{ row.passed ? '通过' : '未通过' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="打分方式" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">
                  <span v-if="row.retrievalFailed" class="muted">检索失败（无答案、不进判定）</span>
                  <span v-else-if="row.judge === 'rule-fallback'" class="muted">规则兜底（模型未判定，不进汇总）</span>
                  <span v-else>{{ row.judge }}</span>
                </template>
              </el-table-column>
            </el-table>
          </template>
          <el-empty v-else description="选择左侧一次运行查看明细" :image-size="80" />
        </div>
      </div>
    </section>

    <QuestionPicker v-model="pickerVisible" @confirm="startRun" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Collection, Download, Files, VideoPlay } from '@element-plus/icons-vue'
import EChart from '@/components/common/EChart.vue'
import QuestionPicker from '@/components/eval/QuestionPicker.vue'
import { exportRun, getGoldenSetInfo, getRun, getTrend, listRuns, runEval } from '@/api/eval'
import { useMedia } from '@/composables/useMedia'
import type { EvalEnv, EvalMetrics, EvalRun, EvalSample, EvalTrendPoint, GoldenSetInfo } from '@/types/api'

const { width } = useMedia()
const isNarrow = computed(() => width.value < 1200)

const metricDefs = [
  { key: 'retrievalPrecision' as const, label: '检索准确率', target: 90, gte: true, targetLabel: '≥90%', modelOnly: false },
  { key: 'relevance' as const, label: '回答相关度', target: 85, gte: true, targetLabel: '≥85%', modelOnly: true },
  { key: 'faithfulness' as const, label: '忠实度', target: 88, gte: true, targetLabel: '≥88%', modelOnly: true },
  { key: 'hallucinationRate' as const, label: '幻觉率', target: 5, gte: false, targetLabel: '≤5%', modelOnly: true },
  { key: 'citationCompleteness' as const, label: '引用完整率', target: 90, gte: true, targetLabel: '≥90%', modelOnly: false },
]

const runs = ref<EvalRun[]>([])
const trend = ref<EvalTrendPoint[]>([])
const currentRun = ref<EvalRun | null>(null)
const samples = ref<EvalSample[]>([])
const detailLoading = ref(false)
const running = ref(false)
const pickerVisible = ref(false)
const bank = ref<GoldenSetInfo | null>(null)
const router = useRouter()
let pollTimer: number | undefined

const usableCount = computed(() => bank.value?.usable ?? 0)

/** 题集指纹种数：>1 说明趋势里混了不同考卷（含没有指纹的历史运行，题来自已下线的写死文件），不能当成同一条测量线看 */
const mixedSets = computed(() => {
  const sigs = new Set(trend.value.map((t) => t.questionSignature || '打包文件期（无指纹）'))
  return sigs.size > 1 ? sigs.size : 0
})

const latestMetrics = computed<EvalMetrics | null>(() => {
  const done = runs.value.find((r) => r.status === 'done')
  return done?.metrics ?? null
})

/** 最近一次完成运行里检索链路失败的题数；旧运行不带这个字段，按 0 处理（不编一个"没有失败"的结论） */
const retrievalFailures = computed(() => latestMetrics.value?.retrievalFailedCount ?? 0)

function shortEmbed(mode?: string) {
  if (!mode) return '向量化未知'
  return mode.startsWith('openai-embedding:') ? `emb:${mode.slice('openai-embedding:'.length)}` : mode
}
function envBrief(env: EvalEnv) {
  return [env.initiator || '发起人未知', env.vectorStore || '向量库未知', shortEmbed(env.embedMode)].join(' · ')
}
function envDetail(env: EvalEnv) {
  const bits = [
    `发起人 ${env.initiator || '未知'}（${env.initiatorRole || '角色未知'}）`,
    `向量库 ${env.vectorStore || '—'}`,
    `向量化 ${shortEmbed(env.embedMode)}`,
    `chat ${env.chatModel || '—'}`,
    `topK ${env.ragTopK ?? '—'} / 阈值 ${env.ragScoreThreshold ?? '—'} / 查询改写 ${env.ragQueryRewrite == null ? '—' : env.ragQueryRewrite ? '开' : '关'}`,
  ]
  if (env.degraded) bits.push('环境已降级，本轮分数与正常态不可比')
  return `本次运行环境：${bits.join(' · ')}`
}

/** 后端以 -1 表示"本次没有样本被模型判定"，这不是 0 分，不能拿来算达标 */
function metricValue(m: (typeof metricDefs)[number]): number | null {
  const v = latestMetrics.value?.[m.key]
  if (v == null || v < 0) return null
  return v
}

function undetermined(m: (typeof metricDefs)[number]) {
  return m.modelOnly && latestMetrics.value != null && metricValue(m) == null
}

function pass(m: (typeof metricDefs)[number]) {
  const v = metricValue(m)
  if (v == null) return false
  return m.gte ? v >= m.target : v <= m.target
}
function pctValue(m: (typeof metricDefs)[number]) {
  const v = metricValue(m)
  // 后端指标已是 0-100 百分数
  return v == null ? 0 : Math.round(v)
}
function fmtPct(v?: number | null) {
  return v == null ? '—' : `${v.toFixed(1)}%`
}
/** 表格用：后端 -1 表示未判定，不能显示成 "-1.0%" */
function fmtMetric(v?: number | null) {
  if (v == null) return '—'
  return v < 0 ? '未判定' : `${v.toFixed(1)}%`
}
function fmt(iso: string) {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

const scoreKeys = ['retrievalPrecision', 'relevance', 'faithfulness', 'hallucinationRate', 'citationCompleteness']
function shortLabel(k: string) {
  return { retrievalPrecision: '检索', relevance: '相关', faithfulness: '忠实', hallucinationRate: '幻觉', citationCompleteness: '引用' }[k] || k
}

const trendOption = computed(() => {
  const xs = trend.value.map((t) => fmt(t.createdAt))
  const colors = ['#12b886', '#228be6', '#f5a623', '#e5484d', '#2fbf71']
  return {
    color: colors,
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v: number | null) => (v == null ? '未判定' : `${v}%`),
    },
    legend: { textStyle: { fontSize: 11 } },
    grid: { left: 40, right: 12, top: 42, bottom: 24 },
    xAxis: { type: 'category', data: xs },
    yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
    // 直接画后端原值：-1（未判定）画成空柱，不做 100-幻觉率 这类反向包装
    series: metricDefs.map((m, i) => ({
      name: m.label,
      type: 'bar',
      barMaxWidth: 22,
      data: trend.value.map((t) => {
        const v = t.metrics?.[m.key]
        return v == null || v < 0 ? null : Math.round(v)
      }),
      itemStyle: { color: colors[i], borderRadius: [4, 4, 0, 0] },
    })),
  }
})

async function loadAll() {
  try {
    const [r, t] = await Promise.all([listRuns(), getTrend()])
    runs.value = r
    trend.value = t
    if (!currentRun.value && r.length) selectRun(r[0])
  } catch { /* handled */ }
  try {
    bank.value = await getGoldenSetInfo()
  } catch { bank.value = null }
}

async function selectRun(row: EvalRun | null) {
  if (!row) return
  currentRun.value = row
  samples.value = row.samples ?? []
  detailLoading.value = true
  try {
    const full = await getRun(row.id)
    samples.value = full.samples ?? []
  } catch { /* handled */ } finally {
    detailLoading.value = false
  }
}

function runNow() {
  pickerVisible.value = true
}

/** ids 为空 = 后端按「全部已复核且启用」取题 */
async function startRun(ids: string[]) {
  running.value = true
  try {
    const { runId } = await runEval(ids)
    ElMessage.success(`评测已触发，${ids.length ? `本次勾选 ${ids.length} 题` : '本次使用全部已复核题目'}…`)
    poll(runId)
  } catch {
    running.value = false
  }
}

function poll(runId: string) {
  window.clearInterval(pollTimer)
  pollTimer = window.setInterval(async () => {
    try {
      const r = await listRuns()
      runs.value = r
      const mine = r.find((x) => x.id === runId)
      if (mine && mine.status === 'done') {
        window.clearInterval(pollTimer)
        running.value = false
        ElMessage.success('评测完成')
        loadAll()
      } else if (mine && mine.status === 'failed') {
        window.clearInterval(pollTimer)
        running.value = false
        ElMessage.error(mine.failReason ? `评测失败：${mine.failReason}` : '评测失败，未出分')
        loadAll()
      }
    } catch { /* ignore */ }
  }, 3000)
}
onBeforeUnmount(() => window.clearInterval(pollTimer))
onMounted(loadAll)

async function download() {
  if (!currentRun.value) return
  try {
    await exportRun(currentRun.value.id)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function bankInfo() {
  const b = bank.value
  const stats = b
    ? `题库共 ${b.total} 题：待复核 ${b.draft}、已复核 ${b.reviewed}，其中「已复核且启用」${b.usable} 题会参与评测` +
      (b.sampleLimit ? `；配置项 app.eval.sample-limit=${b.sampleLimit}，本次实际最多跑 ${b.effectiveSampleCount} 题。` : '。') +
      ` 数据位置：${b.storage}。`
    : '题库实况读取失败，宁可不显示也不编一个数。'
  ElMessageBox.alert(
    stats +
      ' 评测取题一律来自数据库，不再读打包在 jar 里的写死文件；改考卷不用发版。' +
      ' 检索准确率 / 引用完整率按规则全量统计；相关度、忠实度、幻觉率由 chat 模型作 LLM-as-a-Judge，' +
      '模型判定失败的样本只出现在明细里并标注 rule-fallback，不进汇总。' +
      ' 内置演示语料已下线：若期望文档不在知识库中，检索类指标会真实地偏低，不会出现自证的高分。',
    '题库实况',
    { confirmButtonText: '知道了', type: 'info' }
  ).catch(() => { /* 关闭弹窗 */ })
}

function statusLabel(s: EvalRun['status']) {
  return s === 'done' ? '完成' : s === 'failed' ? '失败' : '运行中'
}
function statusTag(s: EvalRun['status']) {
  return s === 'done' ? 'success' : s === 'failed' ? 'danger' : 'warning'
}
</script>

<style scoped>
.eval-page {
  padding: 16px;
}
.eval-page__head {
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
.eval-page__main {
  display: grid;
  grid-template-columns: 5fr 7fr;
  gap: 12px;
  margin-bottom: 12px;
}
.eval-page__alert {
  margin-bottom: 12px;
}
.eval-trend__warn {
  margin-bottom: 8px;
}
.eval-page__main.is-single {
  grid-template-columns: 1fr;
}
.eval-sec__title {
  font-size: var(--font-title);
  margin: 0 0 12px;
}
.eval-sec__note {
  margin: -6px 0 8px;
  font-size: var(--font-aux);
  color: #7a857f;
}
.eval-metrics__note {
  margin: 4px 0 0;
  font-size: var(--font-aux);
  color: #7a857f;
}
.eval-metrics__warn {
  margin-top: 10px;
}
.eval-env--degraded {
  color: var(--danger);
}
.eval-metric {
  margin-bottom: 14px;
}
.eval-metric__row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
  font-size: 13px;
}
.eval-metric__val.ok { color: var(--success); }
.eval-metric__val.bad { color: var(--danger); }
.eval-metric__val.na { color: #7a857f; }
.eval-report__body {
  display: grid;
  grid-template-columns: 5fr 7fr;
  gap: 16px;
}
@media (max-width: 1199px) {
  .eval-report__body { grid-template-columns: 1fr; }
}
.eval-report__detail-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.eval-sample__diff {
  padding: 8px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}
/*
  桌面宽度（≥1200，即 isNarrow 为假的那一段）把整页锁在内容区高度里：
  页头与指标/趋势区各占固定高度，剩下的都给报告卡片，两张表撑满它——
  el-table 只有拿到确定高度时才会把滚动收进表体，于是页面本身不出滚动条。
  窄屏不套用：指标区竖排后就接近 700px 高，硬撑会把表格压扁。
*/
@media (min-width: 1200px) {
  .eval-page {
    height: 100%;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .eval-page__head,
  .eval-page__alert,
  .eval-page__main {
    flex: 0 0 auto;
  }
  .eval-report {
    flex: 1 1 auto;
    min-height: 200px;
    display: flex;
    flex-direction: column;
  }
  .eval-report__body {
    flex: 1 1 auto;
    min-height: 168px;
    /*
      运行列表的列宽下限是 550px（108+68+56+150+96+72），轨道窄于它就必然出横向滚动条；
      而 5fr 在 1200 视口只分到约 367px。所以给左轨一个下限 560（留 10px 余量），
      右轨写 minmax(0, 7fr) 允许被压小——逐样本明细表本来就自带横向滚动（D-20 的取舍），压不坏。
    */
    grid-template-columns: minmax(560px, 5fr) minmax(0, 7fr);
  }
  .eval-report__runs {
    height: 100%;
  }
  .eval-report__detail {
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .eval-report__detail-head {
    flex: 0 0 auto;
  }
  .eval-report__samples {
    flex: 1 1 auto;
    min-height: 0;
  }
  .eval-report__body :deep(.el-table__inner-wrapper) {
    height: 100%;
  }
}
.eval-sample__diff {
  padding: 8px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}
</style>
