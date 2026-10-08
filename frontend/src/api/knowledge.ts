import { request } from './http'
import type { KbOverview, KnowledgeBase, KbScope } from '@/types/api'

export function listKnowledgeBases() {
  return request<KnowledgeBase[]>({ method: 'GET', url: '/knowledge-bases' })
}

export function getOverview() {
  return request<KbOverview>({ method: 'GET', url: '/knowledge-bases/overview' })
}

export function createKnowledgeBase(body: {
  name: string
  scope: KbScope
  members: string[]
}) {
  return request<KnowledgeBase>({
    method: 'POST',
    url: '/knowledge-bases',
    data: body,
    params: body.scope === 'confidential' ? { confirm: true } : undefined,
  })
}

export function updateKnowledgeBase(id: string, body: Partial<KnowledgeBase>) {
  return request<KnowledgeBase>({ method: 'PUT', url: `/knowledge-bases/${id}`, data: body })
}

export function deleteKnowledgeBase(id: string) {
  return request<void>({ method: 'DELETE', url: `/knowledge-bases/${id}` })
}
