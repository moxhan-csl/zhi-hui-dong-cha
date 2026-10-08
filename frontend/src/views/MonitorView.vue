<template>
  <div class="mon-page" v-loading="loading">
    <div class="mon-page__head">
      <h1 class="page-title">监控中心</h1>
      <span class="muted">每 30 秒自动刷新 · 更新于 {{ updatedAt }}</span>
    </div>

    <div class="mon-page__cards">
      <StatCard label="P50 延迟" :value="latencyValue(metrics?.p50Ms)" unit="ms" icon="Timer" :hint="latencyHint" />
      <StatCard
        label="P95 延迟"
        :value="latencyValue(metrics?.p95Ms)"
        unit="ms"
        icon="Stopwatch"
        :hint="(metrics?.p95Ms ?? 0) > 2000 ? '超过告警规则 2000ms' : '告警规则 2000ms'"
      />
      <StatCard label="近 30 分钟请求数" :value="requestTotal" unit="次" icon="DataLine" :hint="`${instanceNote}，进程重启后清零`" />
      <StatCard label="当日模型 Tokens" :value="metrics ? fmtNum(metrics.cost.tokensToday) : '—'" icon="Coin" :hint="tokenHint" />
    </div>

    <div class="mon-page__charts">
      <section class="zd-card">
        <h2 class="mon-sec__title">请求量趋势 <span class="muted mon-sec__note">{{ instanceNote }}</span></h2>
        <EChart :option="requestOption" height="280px" aria-label="请求量趋势图" />
      </section>
      <section class="zd-card">
        <h2 class="mon-sec__title">LLM 成本看板</h2>
        <div class="mon-cost">
          <div class="mon-cost__nums">
            <div>
              <span class="muted">当日成本（按单价估算，非账单实数）</span>
              <div class="mon-cost__today" :class="{ over: overBudget }">¥{{ metrics?.cost.today ?? '—' }}</div>
            </div>
            <div>
              <span class="muted">告警阈值（{{ metrics?.cost.thresholdSource ?? '配置项未读取' }}）</span>
              <div class="mon-cost__th">{{ metrics ? `¥${metrics.cost.threshold}` : '—' }}</div>
            </div>
          </div>
          <el-progress
            :percentage="budgetPct"
            :stroke-width="12"
            :status="overBudget ? 'exception' : undefined"
            :color="overBudget ? 'var(--danger)' : 'var(--brand)'"
          />
          <p v-if="overBudget" class="mon-cost__warn" role="alert">
            <el-icon><WarningFilled /></el-icon> 当日估算成本已超过配置阈值
          </p>
          <p class="muted mon-cost__src">
            数据来源：{{ metrics?.sources?.cost ?? '—' }} · 成本含 embedding 调用；
            其中 {{ fmtNum(metrics?.cost.tokensEstimated ?? 0) }} tokens 为服务未回 usage 时按字符数估算
          </p>
        </div>
        <EChart :option="costOption" height="200px" aria-label="成本日趋势图" />
      </section>
    </div>

    <section class="zd-card mon-alerts">
      <h2 class="mon-sec__title">告警策略</h2>
      <ul>
        <li v-for="(a, i) in metrics?.alerts ?? []" :key="i" class="mon-alerts__item" :class="{ 'is-active': a.active }">
          <span class="mon-alerts__dot" :class="`dot--${a.level}`" aria-hidden="true" />
          <span class="mon-alerts__level">{{ levelLabel(a.level) }}</span>
          <span class="mon-alerts__rule">{{ a.rule }}</span>
          <span class="mon-alerts__msg muted">{{ a.message }}</span>
          <el-tag v-if="a.active" size="small" type="danger" effect="dark">触发中</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">正常</el-tag>
        </li>
      </ul>
      <p v-if="metrics && !metrics.alerts.length" class="muted">未配置告警规则</p>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'
import StatCard from '@/components/common/StatCard.vue'
import EChart from '@/components/common/EChart.vue'
import { getMetrics } from '@/api/monitor'
import type { MonitorMetrics } from '@/types/api'

const loading = ref(false)
const metrics = ref<MonitorMetrics | null>(null)
const updatedAt = ref('')
let timer: number | undefined

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    metrics.value = await getMetrics()
    updatedAt.value = new Date().toLocaleTimeString('zh-CN')
  } catch { /* handled */ } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(() => load(true), 30000)
})
onBeforeUnmount(() => window.clearInterval(timer))

const instanceNote = '仅统计当前实例、当前进程'
const threshold = computed(() => (metrics.value?.cost.threshold ? Number(metrics.value.cost.threshold) : null))
function fmtNum(n?: number | null) {
  return n == null ? '—' : n.toLocaleString()
}
const requestTotal = computed(() =>
  (metrics.value?.requestTrend ?? []).reduce((acc, p) => acc + (p.count || 0), 0)
)
const latencyHint = computed(() => {
  const n = metrics.value?.latencySamples
  if (n == null) return `${instanceNote}，样本数未上报`
  return n ? `样本 ${n} 条（${instanceNote}）` : '本进程还没有延迟样本，下面的分位数不具参考性'
})
/** 没有延迟样本时后端不报分位数（字段缺席），"没测到"和"很快"必须区分开 */
function latencyValue(v?: number) {
  if (v == null) return '—'
  return metrics.value?.latencySamples === 0 ? '—' : v
}
const tokenHint = computed(() => {
  const c = metrics.value?.cost
  if (!c) return '数据来源未就绪'
  const est = c.tokensEstimated > 0 ? `，含 ${fmtNum(c.tokensEstimated)} 估算` : ''
  return `来源 ${metrics.value?.sources?.cost ?? '—'}${est}`
})
const budgetPct = computed(() => {
  const c = metrics.value?.cost
  // 阈值未加载时不猜数：显示 0 并等下一次刷新
  if (!c || !c.threshold) return 0
  return Math.min(100, Math.round((c.today / c.threshold) * 100))
})
const overBudget = computed(() => {
  const c = metrics.value?.cost
  return !!c && c.today > c.threshold
})

const requestOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 44, right: 16, top: 24, bottom: 28 },
  xAxis: { type: 'category', data: (metrics.value?.requestTrend ?? []).map((p) => p.time) },
  yAxis: { type: 'value' },
  series: [{
    name: '请求量',
    type: 'line',
    // 逐分钟真实计数，不做平滑：平滑会在两个真实点之间画出没有发生过的曲线
    smooth: false,
    areaStyle: { opacity: 0.12 },
    lineStyle: { width: 2 },
    itemStyle: { color: '#12b886' },
    data: (metrics.value?.requestTrend ?? []).map((p) => p.count),
  }],
}))

const costOption = computed(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (v: number) => `¥${v}` },
  grid: { left: 44, right: 16, top: 24, bottom: 28 },
  xAxis: { type: 'category', data: (metrics.value?.cost.trend ?? []).map((p) => p.date) },
  yAxis: { type: 'value', axisLabel: { formatter: '¥{value}' } },
  series: [{
    name: '成本',
    type: 'bar',
    barMaxWidth: 26,
    itemStyle: { color: '#228be6', borderRadius: [4, 4, 0, 0] },
    markLine: threshold.value == null ? undefined : {
      silent: true,
      symbol: 'none',
      lineStyle: { color: '#e5484d', type: 'dashed' },
      label: { formatter: `阈值 ¥${threshold.value}`, fontSize: 11 },
      data: [{ yAxis: threshold.value }],
    },
    data: (metrics.value?.cost.trend ?? []).map((p) => p.amount),
  }],
}))

function levelLabel(l: string) {
  return { info: '提示', warn: '警告', critical: '严重' }[l] || l
}
</script>

<style scoped>
.mon-page {
  padding: 16px;
}
.mon-page__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 14px;
  flex-wrap: wrap;
  gap: 6px;
}
.page-title {
  font-size: 18px;
  margin: 0;
}
.mon-page__cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}
.mon-page__charts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 12px;
}
.mon-sec__title {
  font-size: var(--font-title);
  margin: 0 0 12px;
}
.mon-sec__note {
  font-size: var(--font-aux);
  font-weight: 400;
}
.mon-cost__src {
  font-size: var(--font-aux);
  margin: 8px 0 0;
}
.mon-cost__nums {
  display: flex;
  gap: 40px;
  margin-bottom: 8px;
}
.mon-cost__today {
  font-size: 24px;
  font-weight: 700;
}
.mon-cost__today.over { color: var(--danger); }
.mon-cost__th {
  font-size: 18px;
  font-weight: 600;
  color: #7a857f;
}
.mon-cost__warn {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--danger);
  font-size: var(--font-aux);
}
.mon-alerts ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
.mon-alerts__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: var(--radius-control);
}
.mon-alerts__item.is-active {
  background: #fdeaea;
}
.mon-alerts__dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex: 0 0 10px;
}
.dot--info { background: var(--info); }
.dot--warn { background: var(--warning); }
.dot--critical { background: var(--danger); }
.mon-alerts__rule {
  font-weight: 600;
  min-width: 180px;
}
.mon-alerts__msg {
  flex: 1;
}
@media (max-width: 1199px) {
  .mon-page__cards { grid-template-columns: repeat(2, 1fr); }
  .mon-page__charts { grid-template-columns: 1fr; }
}
@media (max-width: 767px) {
  .mon-page__cards { grid-template-columns: 1fr; }
}
</style>
