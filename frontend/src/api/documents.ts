import { request } from './http'
import type { DocChunk, DocPage, DocumentItem } from '@/types/api'

export function listDocuments(params: { keyword?: string; status?: string; page?: number; size?: number }) {
  return request<DocPage>({ method: 'GET', url: '/documents', params })
}

export function getDocument(id: string) {
  return request<DocumentItem>({ method: 'GET', url: `/documents/${id}` })
}

export function listChunks(id: string, page = 1) {
  return request<DocChunk[]>({ method: 'GET', url: `/documents/${id}/chunks`, params: { page } })
}

export function retryDocument(id: string) {
  return request<DocumentItem>({ method: 'POST', url: `/documents/${id}/retry` })
}

export function deleteDocument(id: string) {
  return request<void>({ method: 'DELETE', url: `/documents/${id}` })
}
