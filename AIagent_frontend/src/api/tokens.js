import apiClient from './client'

export function fetchTokens() {
  return apiClient.get('/api/v1/auth/tokens')
}

export function createToken(data) {
  return apiClient.post('/api/v1/auth/tokens', data)
}

export function revokeToken(id) {
  return apiClient.delete(`/api/v1/auth/tokens/${id}`)
}
