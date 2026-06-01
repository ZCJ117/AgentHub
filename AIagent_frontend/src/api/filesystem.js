import apiClient from './client'

export function fetchDirs(path) {
  const params = path ? { path } : {}
  return apiClient.get('/api/v1/filesystem/dirs', { params })
}
