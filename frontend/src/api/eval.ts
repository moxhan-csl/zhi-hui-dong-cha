import { request, authHeaders } from './http'
import type {
  EvalRun,
  EvalTrendPoint,
  GoldenImportResult,
  GoldenQuestion,
  GoldenQuestionInput,
  GoldenSetInfo,
} from '@/types/api'

/** questionIds 为空 = 全部「已复核且启用」的题目 */
export function runEval(questionIds?: string[]) {
  return request<{ runId: string }>({ method: 'POST', url: '/eval/run', data: { questionIds } })
}

export function listRuns() {
  return request<EvalRun[]>({ method: 'GET', url: '/eval/runs' })
}

export function getRun(id: string) {
  return request<EvalRun>({ method: 'GET', url: `/eval/runs/${id}` })
}

export function getTrend() {
  return request<EvalTrendPoint[]>({ method: 'GET', url: '/eval/trend' })
}

export function getGoldenSetInfo() {
  return request<GoldenSetInfo>({ method: 'GET', url: '/eval/golden-set' })
}

/* ===== 题库管理 ===== */

export function listQuestions(status?: 'draft' | 'reviewed') {
  return request<GoldenQuestion[]>({ method: 'GET', url: '/eval/questions', params: { status } })
}

export function createQuestion(input: GoldenQuestionInput) {
  return request<GoldenQuestion>({ method: 'POST', url: '/eval/questions', data: input })
}

export function updateQuestion(id: string, input: GoldenQuestionInput) {
  return request<GoldenQuestion>({ method: 'PUT', url: `/eval/questions/${id}`, data: input })
}

/** 批量复核 / 退回，后端逐条校验（期望文档为空的会被拒绝） */
export function reviewQuestions(ids: string[], reviewed: boolean) {
  return request<{ count: number }>({ method: 'POST', url: '/eval/questions/review', data: { ids, reviewed } })
}

export function setQuestionEnabled(id: string, enabled: boolean) {
  return request<GoldenQuestion>({ method: 'POST', url: '/eval/questions/enabled', data: { id, enabled } })
}

export function deleteQuestions(ids: string[]) {
  return request<{ deleted: number }>({ method: 'DELETE', url: '/eval/questions', data: { ids } })
}

export function importQuestions(text: string) {
  return request<GoldenImportResult>({ method: 'POST', url: '/eval/questions/import', data: { text } })
}

/** 导出 JSONL（带鉴权下载） */
export async function exportRun(id: string) {
  const res = await fetch(`/api/eval/export/${id}`, { headers: authHeaders() })
  if (!res.ok) throw new Error('导出失败')
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `eval-${id}.jsonl`
  a.click()
  URL.revokeObjectURL(url)
}

export async function exportQuestions() {
  const res = await fetch('/api/eval/questions/export', { headers: authHeaders() })
  if (!res.ok) throw new Error('导出失败')
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `golden-questions-${new Date().toISOString().slice(0, 10)}.jsonl`
  a.click()
  URL.revokeObjectURL(url)
}
