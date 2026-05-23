import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  fetchConversations, fetchConversationsPage, fetchConversationDetail, fetchMessages,
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
  const currentPage = ref(1)
  const hasMore = ref(false)
  const loadingMore = ref(false)

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
      const params = { page: 1, size: 50 }
      if (filter.value !== 'all') params.conversationType = filter.value
      if (searchKeyword.value) params.keyword = searchKeyword.value
      const data = await fetchConversationsPage(params)
      conversations.value = data?.records || []
      currentPage.value = data?.current ?? 1
      hasMore.value = (data?.current ?? 0) < (data?.pages ?? 0)
    } catch (e) {
      console.warn('Failed to load conversations:', e)
    } finally {
      loading.value = false
    }
  }

  async function loadMore() {
    if (!hasMore.value || loadingMore.value) return
    loadingMore.value = true
    try {
      const params = { page: currentPage.value + 1, size: 50 }
      if (filter.value !== 'all') params.conversationType = filter.value
      if (searchKeyword.value) params.keyword = searchKeyword.value
      const data = await fetchConversationsPage(params)
      const records = data?.records || []
      conversations.value.push(...records)
      currentPage.value = data?.current ?? currentPage.value + 1
      hasMore.value = (data?.current ?? 0) < (data?.pages ?? 0)
    } catch (e) {
      console.warn('Failed to load more conversations:', e)
    } finally {
      loadingMore.value = false
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
    currentPage, hasMore, loadingMore,
    activeConversation, sortedConversations, filteredConversations, unreadTotal,
    loadList, loadMore, setActive, togglePin, toggleArchive, deleteConversation, createGroup,
    pinnedMessages, loadPinnedMessages
  }
})
