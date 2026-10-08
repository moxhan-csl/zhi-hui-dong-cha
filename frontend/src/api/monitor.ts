import { request } from './http'
import type { HealthInfo, MonitorMetrics } from '@/types/api'

/** 公开探活端点：报告外部依赖的真实存活与降级状态 */
export function getHealth() {
  return request<HealthInfo>({ method: 'GET', url: '/health', silent: true })
}

export function getMetrics() {
  return request<MonitorMetrics>({ method: 'GET', url: '/monitor/metrics' })
}

export function getAlerts() {
  return request<MonitorMetrics['alerts']>({ method: 'GET', url: '/monitor/alerts' })
}
