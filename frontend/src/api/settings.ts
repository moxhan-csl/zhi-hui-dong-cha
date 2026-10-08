import { request } from './http'
import type { AuditLog, ManagedUser, ModelsSetting, RagSetting } from '@/types/api'

export function getModels() {
  return request<ModelsSetting>({ method: 'GET', url: '/settings/models' })
}
export function putModels(data: ModelsSetting) {
  return request<ModelsSetting>({ method: 'PUT', url: '/settings/models', data })
}

export function getRag() {
  return request<RagSetting>({ method: 'GET', url: '/settings/rag' })
}
export function putRag(data: RagSetting) {
  return request<RagSetting>({ method: 'PUT', url: '/settings/rag', data })
}

export function listUsers() {
  return request<ManagedUser[]>({ method: 'GET', url: '/settings/users' })
}
export function createUser(data: Omit<ManagedUser, 'id'>) {
  return request<ManagedUser>({ method: 'POST', url: '/settings/users', data })
}
export function updateUser(id: string, data: Partial<ManagedUser>) {
  return request<ManagedUser>({ method: 'PUT', url: `/settings/users/${id}`, data })
}
export function deleteUser(id: string) {
  return request<void>({ method: 'DELETE', url: `/settings/users/${id}` })
}

export function listAuditLogs() {
  return request<AuditLog[]>({ method: 'GET', url: '/settings/audit-logs' })
}
