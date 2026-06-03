import apiClient from './client'

export function fetchConversations(params = {}) {
  return apiClient.get('/api/v1/conversations', { params })
}

export function fetchConversationsPage(params = {}) {
  return apiClient.get('/api/v1/conversations/page', { params })
}

export function fetchConversationDetail(id) {
  return apiClient.get(`/api/v1/conversations/${id}`)
}

export function fetchMessages(id, params = {}) {
  return apiClient.get(`/api/v1/conversations/${id}/messages`, { params })
}

export function deleteConversation(id) {
  return apiClient.delete(`/api/v1/conversations/${id}`)
}

export function updateConversationTitle(id, title) {
  return apiClient.put(`/api/v1/conversations/${id}/title`, { title })
}

export function toggleConversationPin(id, pinned) {
  return apiClient.put(`/api/v1/conversations/${id}/pin`, { pinned })
}

export function toggleConversationArchive(id, archived) {
  return apiClient.put(`/api/v1/conversations/${id}/archive`, { archived })
}

export function createGroupConversation(body) {
  return apiClient.post('/api/v1/conversations/group', body)
}

export function createDirectConversation(body) {
  return apiClient.post('/api/v1/conversations', body)
}

export function fetchPinnedMessages(id) {
  return apiClient.get(`/api/v1/conversations/${id}/pins`)
}

export function pinMessage(conversationId, { messageId, note }) {
  return apiClient.post(`/api/v1/conversations/${conversationId}/pins`, { messageId, note })
}

export function unpinMessage(conversationId, messageId) {
  return apiClient.delete(`/api/v1/conversations/${conversationId}/pins/${messageId}`)
}
