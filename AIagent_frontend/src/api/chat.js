import { getToken } from '@/utils/token'
import apiClient from './client'

const BASE = ''

export function streamChat(body, signal) {
  const wsId = localStorage.getItem('ai_agent_workspace_id') || ''
  const token = getToken()
  return fetch(`${BASE}/api/v1/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'X-Workspace-Id': wsId,
      Accept: 'text/event-stream'
    },
    body: JSON.stringify(body),
    signal
  })
}

export function stopChat(conversationId) {
  return apiClient.post(`/api/v1/chat/${conversationId}/stop`)
}

export function interruptChat(conversationId, message) {
  return apiClient.post(`/api/v1/chat/${conversationId}/interrupt`, { message })
}
