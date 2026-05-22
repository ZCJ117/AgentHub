import apiClient from './client'

export function fetchWorkspaces() {
  return apiClient.get('/api/v1/workspaces')
}
