import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchAgents, fetchAgentDetail } from '@/api/agent'

const LAST_AGENT_KEY = 'ai_agent_last_agent'

export const useAgentStore = defineStore('agent', () => {
  const agents = ref([])
  const selectedAgentId = ref(null)
  const isLoading = ref(false)
  const detailCache = ref(new Map())

  const selectedAgent = computed(
    () => agents.value.find(a => String(a.id) === String(selectedAgentId.value)) || null
  )

  const enabledAgents = computed(() => agents.value.filter(a => a.enabled !== false))

  function selectAgent(id) {
    selectedAgentId.value = id || null
    if (id) {
      localStorage.setItem(LAST_AGENT_KEY, String(id))
    }
  }

  async function loadAgents(params = { enabled: true }) {
    isLoading.value = true
    try {
      const data = await fetchAgents(params)
      const list = Array.isArray(data) ? data : (data?.records || [])
      agents.value = list

      const lastId = localStorage.getItem(LAST_AGENT_KEY)
      if (lastId && list.some(a => String(a.id) === lastId)) {
        selectedAgentId.value = lastId
      } else if (list.length > 0) {
        selectedAgentId.value = String(list[0].id)
      }
    } finally {
      isLoading.value = false
    }
  }

  async function loadDetail(id) {
    if (detailCache.value.has(id)) return detailCache.value.get(id)
    const data = await fetchAgentDetail(id)
    detailCache.value.set(id, data)
    return data
  }

  return {
    agents, selectedAgentId, isLoading,
    selectedAgent, enabledAgents,
    selectAgent, loadAgents, loadDetail
  }
})
