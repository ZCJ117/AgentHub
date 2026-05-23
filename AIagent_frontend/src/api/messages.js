import apiClient from './client'

export function fetchMessageDetail(id) {
  return apiClient.get(`/api/v1/conversations/messages/${id}`)
}

export function regenerateMessage(id, message) {
  return apiClient.post(`/api/v1/conversations/messages/${id}/regenerate`, { message })
}

export function fetchReactions(id) {
  return apiClient.get(`/api/v1/conversations/messages/${id}/reactions`)
}

export function addReaction(id, reactionType) {
  return apiClient.post(`/api/v1/conversations/messages/${id}/reactions`, { reactionType })
}

export function removeReaction(id, reactionType) {
  return apiClient.delete(`/api/v1/conversations/messages/${id}/reactions/${reactionType}`)
}

export function fetchReplyChain(id) {
  return apiClient.get(`/api/v1/conversations/messages/${id}/reply-chain`)
}
