import apiClient from './client'

export function fetchAgents(params = {}) {
  return apiClient.get('/api/v1/agents', { params })
}

export function fetchAgentDetail(id) {
  return apiClient.get(`/api/v1/agents/${id}`)
}

export function createAgent(body) {
  return apiClient.post('/api/v1/agents', body)
}

export function updateAgent(id, body) {
  return apiClient.put(`/api/v1/agents/${id}`, body)
}

export function deleteAgent(id) {
  return apiClient.delete(`/api/v1/agents/${id}`)
}
