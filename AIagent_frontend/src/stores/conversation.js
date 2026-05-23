import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  fetchConversations, fetchConversationDetail, fetchMessages,
  deleteConversation as deleteConvApi,
  toggleConversationPin, toggleConversationArchive,
  updateConversationTitle,
  createGroupConversation,
  fetchPinnedMessages
} from '@/api/conversations'

export const useConversationStore = defineStore('conversation', () => {
  const conversations = ref([])
  const activeId = ref(null)
  const loading = ref(false)
  const searchKeyword = ref('')
  const filter = ref('all')

  const pinnedMessages = ref([])

  async function loadPinnedMessages(conversationId) {
    const res = await fetchPinnedMessages(conversationId)
    pinnedMessages.value = res || []
  }

  const activeConversation = computed(() =>
    conversations.value.find(c => c.id === activeId.value) || null
  )

  const sortedConversations = computed(() => {
    const list = [...conversations.value]
    list.sort((a, b) => {
      if (a.pinnedAt && !b.pinnedAt) return -1
      if (!a.pinnedAt && b.pinnedAt) return 1
      const aTime = a.lastActiveAt || ''
      const bTime = b.lastActiveAt || ''
      return bTime.localeCompare(aTime)
    })
    return list
  })

  const filteredConversations = computed(() => {
    let list = sortedConversations.value
    if (filter.value === 'direct') list = list.filter(c => c.conversationType === 'direct')
    if (filter.value === 'group') list = list.filter(c => c.conversationType === 'group')
    if (searchKeyword.value) {
      const kw = searchKeyword.value.toLowerCase()
      list = list.filter(c =>
        (c.title || '').toLowerCase().includes(kw) ||
        (c.agentName || '').toLowerCase().includes(kw)
      )
    }
    return list
  })

  const unreadTotal = computed(() =>
    conversations.value.reduce((sum, c) => sum + (c.unreadCount || 0), 0)
  )

  async function loadList() {
    loading.value = true
    try {
      const params = {}
      if (filter.value !== 'all') params.conversationType = filter.value
      const data = await fetchConversations(params)
      conversations.value = Array.isArray(data) ? data : (data?.records || [])
    } finally {
      loading.value = false
    }
  }

  function setActive(id) {
    activeId.value = id
  }

  async function togglePin(id) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    const newState = !conv.pinnedAt
    await toggleConversationPin(id, newState)
    conv.pinnedAt = newState ? new Date().toISOString() : null
  }

  async function toggleArchive(id) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    const newState = !conv.archived
    await toggleConversationArchive(id, newState)
    conv.archived = newState
  }

  async function deleteConversation(id) {
    await deleteConvApi(id)
    conversations.value = conversations.value.filter(c => c.id !== id)
    if (activeId.value === id) {
      activeId.value = null
    }
  }

  async function createGroup(config) {
    const data = await createGroupConversation(config)
    await loadList()
    return data
  }

  return {
    conversations, activeId, loading, searchKeyword, filter,
    activeConversation, sortedConversations, filteredConversations, unreadTotal,
    loadList, setActive, togglePin, toggleArchive, deleteConversation, createGroup,
    pinnedMessages, loadPinnedMessages
  }
})
