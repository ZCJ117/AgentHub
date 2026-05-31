import apiClient from './client'

export function fetchWorkspaces() {
  return apiClient.get('/api/v1/workspaces')
}

export function updateWorkspace(id, data) {
  return apiClient.put(`/api/v1/workspaces/${id}`, data)
}
