import { request } from './http'
import type { ChatMessageRecord, Conversation } from '@/types/api'

export function listConversations() {
  return request<Conversation[]>({ method: 'GET', url: '/chat/conversations' })
}

export function listMessages(id: string) {
  return request<ChatMessageRecord[]>({ method: 'GET', url: `/chat/conversations/${id}/messages` })
}

export function deleteConversation(id: string) {
  return request<void>({ method: 'DELETE', url: `/chat/conversations/${id}` })
}
