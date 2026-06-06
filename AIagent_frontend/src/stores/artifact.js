import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  fetchArtifacts,
  fetchArtifactDetail,
  fetchArtifactVersions,
  fetchArtifactContent,
  fetchVersionContent,
  fetchVersionDiff,
  restoreArtifactVersion,
  deployArtifact,
  fetchDeployStatus,
  fetchDeployHistory,
  updateArtifactTags
} from '@/api/artifacts'

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
      const data = await fetchArtifacts(params)
      artifacts.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load artifacts:', err)
    } finally {
      loading.value = false
    }
  }

  async function loadDetail(id) {
    try {
      current.value = await fetchArtifactDetail(id)
    } catch (err) {
      console.warn('Failed to load artifact detail:', err)
    }
  }

  async function loadVersions(id) {
    try {
      const data = await fetchArtifactVersions(id)
      versions.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load versions:', err)
    }
  }

  async function loadDiff(artifactId, fromVersion, toVersion) {
    try {
      const data = await fetchVersionDiff(artifactId, fromVersion, toVersion)
      diffResult.value = data?.diff || ''
    } catch (err) {
      console.warn('Failed to load diff:', err)
    }
  }

  async function restoreVersion(artifactId, versionId) {
    try {
      await restoreArtifactVersion(artifactId, versionId)
      await loadDetail(artifactId)
      await loadVersions(artifactId)
    } catch (err) {
      console.warn('Failed to restore version:', err)
    }
  }

  async function deploy(artifactId, config = {}) {
    try {
      deployStatus.value = { status: 'deploying' }
      await deployArtifact(artifactId, config)
      await loadDeployStatus(artifactId)
    } catch (err) {
      deployStatus.value = { status: 'failed', error: err.message }
    }
  }

  async function loadDeployStatus(artifactId) {
    try {
      deployStatus.value = await fetchDeployStatus(artifactId)
    } catch (err) {
      console.warn('Failed to load deploy status:', err)
    }
  }

  async function loadDeployHistory(artifactId) {
    try {
      const data = await fetchDeployHistory(artifactId)
      deployHistory.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load deploy history:', err)
    }
  }

  async function updateTags(id, tags) {
    try {
      await updateArtifactTags(id, tags)
      if (current.value && current.value.id === id) {
        current.value.tags = tags
      }
    } catch (err) {
      console.warn('Failed to update tags:', err)
    }
  }

  async function loadContent(artifactId) {
    try {
      const data = await fetchArtifactContent(artifactId)
      if (current.value && current.value.id === artifactId) {
        current.value = {
          ...current.value,
          content: data.content,
          contentType: data.contentType,
          downloadUrl: data.downloadUrl
        }
      }
      return data
    } catch (err) {
      console.warn('Failed to load artifact content:', err)
      return null
    }
  }

  async function loadVersionContent(artifactId, versionId) {
    try {
      return await fetchVersionContent(artifactId, versionId)
    } catch (err) {
      console.warn('Failed to load version content:', err)
      return null
    }
  }

  const EPHEMERAL_KEY = 'agenthub_ephemeral_artifacts'

  function loadEphemeralArtifacts() {
    try {
      const raw = sessionStorage.getItem(EPHEMERAL_KEY)
      if (raw) {
        const saved = JSON.parse(raw)
        if (Array.isArray(saved)) {
          artifacts.value = saved
        }
      }
    } catch (e) { /* ignore */ }
  }

  function saveEphemeralArtifacts() {
    try {
      const ephemeral = artifacts.value.filter(a => a.content)
      if (ephemeral.length > 0) {
        sessionStorage.setItem(EPHEMERAL_KEY, JSON.stringify(ephemeral.slice(0, 20)))
      }
    } catch (e) { /* ignore */ }
  }

  // Restore ephemeral artifacts on store init
  loadEphemeralArtifacts()

  function handleArtifactPreview({ artifactId, artifactType, artifactName, conversationId, previewUrl, content }) {
    const existing = artifacts.value.find(a => a.id === artifactId)
    if (existing) {
      existing.previewUrl = previewUrl
      existing.filePath = previewUrl
      existing.content = content || existing.content || ''
    } else {
      artifacts.value.unshift({
        id: artifactId,
        artifactType,
        artifactName,
        conversationId,
        previewUrl,
        filePath: previewUrl,
        content: content || '',
        deployStatus: 'none',
        currentVersion: 1
      })
    }
    saveEphemeralArtifacts()
  }

  return {
    artifacts, current, versions, diffResult, deployStatus, deployHistory, loading,
    loadList, loadDetail, loadVersions, loadDiff,
    restoreVersion, deploy, loadDeployStatus, loadDeployHistory, updateTags,
    loadContent, loadVersionContent, handleArtifactPreview
  }
})
