import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchWorkspaces } from '@/api/workspaces'

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaces = ref([])
  const activeId = ref(null)

  const activeWorkspace = computed(() =>
    workspaces.value.find(w => w.id === activeId.value) || null
  )

  async function loadAndSelect() {
    const stored = localStorage.getItem('ai_agent_workspace_id')
    try {
      const data = await fetchWorkspaces()
      const list = Array.isArray(data) ? data : (data?.records || [])
      workspaces.value = list
      if (list.length > 0) {
        const id = stored && list.some(w => String(w.id) === String(stored))
          ? Number(stored)
          : list[0].id
        activeId.value = id
        localStorage.setItem('ai_agent_workspace_id', String(id))
      }
    } catch {
      // 失败时保持 activeId 为 null，让调用方可以重试
    }
  }

  async function refresh() {
    try {
      const data = await fetchWorkspaces()
      const list = Array.isArray(data) ? data : (data?.records || [])
      workspaces.value = list
    } catch {
      // keep stale data on failure
    }
  }

  async function selectWorkspace(id) {
    activeId.value = id
    localStorage.setItem('ai_agent_workspace_id', String(id))

    const { useConversationStore } = await import('@/stores/conversation')
    const { useAgentStore } = await import('@/stores/agent')
    const { useArtifactStore } = await import('@/stores/artifact')
    const { useOrchestratorStore } = await import('@/stores/orchestrator')

    useConversationStore().setActive(null)
    useOrchestratorStore().reset()
    await Promise.allSettled([
      useConversationStore().loadList(),
      useAgentStore().loadAgents({ enabled: true }),
      useArtifactStore().loadList()
    ])
  }

  return { workspaces, activeId, activeWorkspace, loadAndSelect, refresh, selectWorkspace }
})
