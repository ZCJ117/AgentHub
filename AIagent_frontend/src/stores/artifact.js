import { defineStore } from 'pinia'
import { ref } from 'vue'
import apiClient from '@/api/client'

export const useArtifactStore = defineStore('artifact', () => {
  const artifacts = ref([])
  const current = ref(null)
  const versions = ref([])
  const diffResult = ref(null)
  const deployStatus = ref(null)
  const deployHistory = ref([])
  const loading = ref(false)

  async function loadList(params = {}) {
    loading.value = true
    try {
      const data = await apiClient.get('/api/v1/artifacts', { params })
      artifacts.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load artifacts:', err)
    } finally {
      loading.value = false
    }
  }

  async function loadDetail(id) {
    try {
      current.value = await apiClient.get(`/api/v1/artifacts/${id}`)
    } catch (err) {
      console.warn('Failed to load artifact detail:', err)
    }
  }

  async function loadVersions(id) {
    try {
      const data = await apiClient.get(`/api/v1/artifacts/${id}/versions`)
      versions.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load versions:', err)
    }
  }

  async function loadDiff(artifactId, fromVersion, toVersion) {
    try {
      const data = await apiClient.get(`/api/v1/artifacts/${artifactId}/versions/diff`, {
        params: { from: fromVersion, to: toVersion }
      })
      diffResult.value = data?.diff || ''
    } catch (err) {
      console.warn('Failed to load diff:', err)
    }
  }

  async function restoreVersion(artifactId, versionId) {
    try {
      await apiClient.post(`/api/v1/artifacts/${artifactId}/versions/${versionId}/restore`)
      await loadDetail(artifactId)
      await loadVersions(artifactId)
    } catch (err) {
      console.warn('Failed to restore version:', err)
    }
  }

  async function deploy(artifactId, config = {}) {
    try {
      deployStatus.value = { status: 'deploying' }
      await apiClient.post(`/api/v1/artifacts/${artifactId}/deploy`, config)
      await loadDeployStatus(artifactId)
    } catch (err) {
      deployStatus.value = { status: 'failed', error: err.message }
    }
  }

  async function loadDeployStatus(artifactId) {
    try {
      deployStatus.value = await apiClient.get(`/api/v1/artifacts/${artifactId}/deploy/status`)
    } catch (err) {
      console.warn('Failed to load deploy status:', err)
    }
  }

  async function loadDeployHistory(artifactId) {
    try {
      const data = await apiClient.get(`/api/v1/artifacts/${artifactId}/deploy/history`)
      deployHistory.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load deploy history:', err)
    }
  }

  async function updateTags(id, tags) {
    try {
      await apiClient.put(`/api/v1/artifacts/${id}/tags`, { tags })
      if (current.value && current.value.id === id) {
        current.value.tags = tags
      }
    } catch (err) {
      console.warn('Failed to update tags:', err)
    }
  }

  return {
    artifacts, current, versions, diffResult, deployStatus, deployHistory, loading,
    loadList, loadDetail, loadVersions, loadDiff,
    restoreVersion, deploy, loadDeployStatus, loadDeployHistory, updateTags
  }
})
