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
      if (stored) {
        activeId.value = Number(stored)
      }
    }
  }

  return { workspaces, activeId, activeWorkspace, loadAndSelect }
})
