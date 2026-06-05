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

export function continueDag(conversationId, agentName) {
  return apiClient.post(`/api/v1/chat/${conversationId}/dag/continue`, { agentName })
}

export function uploadFile(conversationIdOrNull, file) {
  const wsId = localStorage.getItem('ai_agent_workspace_id')
  const token = getToken()
  const formData = new FormData()
  formData.append('file', file)
  const convId = conversationIdOrNull || 'default'

  const headers = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (wsId) headers['X-Workspace-Id'] = wsId

  return fetch(`${BASE}/api/v1/chat/upload?conversationId=${encodeURIComponent(convId)}`, {
    method: 'POST',
    headers,
    body: formData
  }).then(res => {
    if (!res.ok) throw new Error(`Upload failed: ${res.status}`)
    return res.json()
  }).then(data => {
    if (data && (data.code === 200 || data.code === '0000')) {
      return data.data
    }
    throw new Error(data?.message || 'Upload failed')
  })
}
