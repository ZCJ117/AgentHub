import apiClient from './client'

export function fetchArtifacts(params) {
  return apiClient.get('/api/v1/artifacts', { params })
}

export function fetchArtifactDetail(id) {
  return apiClient.get(`/api/v1/artifacts/${id}`)
}

export function fetchArtifactVersions(id) {
  return apiClient.get(`/api/v1/artifacts/${id}/versions`)
}

export function fetchVersionDetail(artifactId, versionId) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/versions/${versionId}`)
}

export function fetchArtifactContent(artifactId) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/content`)
}

export function fetchVersionContent(artifactId, versionId) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/versions/${versionId}/content`)
}

export function fetchVersionDiff(artifactId, fromVersion, toVersion) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/versions/diff`, {
    params: { from: fromVersion, to: toVersion }
  })
}

export function restoreArtifactVersion(artifactId, versionId) {
  return apiClient.post(`/api/v1/artifacts/${artifactId}/versions/${versionId}/restore`)
}

export function deployArtifact(artifactId, config) {
  return apiClient.post(`/api/v1/artifacts/${artifactId}/deploy`, config)
}

export function fetchDeployStatus(artifactId) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/deploy/status`)
}

export function fetchDeployHistory(artifactId, params) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/deploy/history`, { params })
}

export function updateArtifactTags(id, tags) {
  return apiClient.put(`/api/v1/artifacts/${id}/tags`, { tags })
}
