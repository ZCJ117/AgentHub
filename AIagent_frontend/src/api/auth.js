import apiClient from './client'

export function login(username, password) {
  return apiClient.post('/api/v1/auth/login', { username, password })
}

export function getProfile() {
  return apiClient.get('/api/v1/auth/me')
}

export function updateProfile(body) {
  return apiClient.put('/api/v1/auth/me', body)
}

export function changePassword(id, oldPassword, newPassword) {
  return apiClient.put(`/api/v1/auth/users/${id}/password`, { oldPassword, newPassword })
}
