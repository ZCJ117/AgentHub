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
  const conversationDetailCache = ref(new Map())
  const pendingDetailFetches = new Map()

  async function loadPinnedMessages(conversationId) {
    const res = await fetchPinnedMessages(conversationId)
    pinnedMessages.value = res || []
  }

  const activeConversation = computed(() => {
    const base = conversations.value.find(c =>
      c.conversationId === activeId.value || String(c.id) === String(activeId.value)
    )
    const cacheKey = String(activeId.value)
    const detail = conversationDetailCache.value.get(cacheKey)

    if (!base && !detail) return null
    if (!base) return detail
    if (detail) return { ...base, members: detail.members || [] }
    return base
  })

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

  async function setActive(id) {
    activeId.value = id
    if (!id) return

    const cacheKey = String(id)
    if (conversationDetailCache.value.has(cacheKey)) return
    if (pendingDetailFetches.has(cacheKey)) return

    const promise = (async () => {
      try {
        const detail = await fetchConversationDetail(id)
        if (detail) {
          conversationDetailCache.value.set(cacheKey, detail)
          if (detail.conversationId && detail.conversationId !== String(id)) {
            conversationDetailCache.value.set(detail.conversationId, detail)
          }
        }
      } catch (e) {
        console.warn('Failed to fetch conversation detail:', e)
        if (e.response?.status === 404 && String(activeId.value) === cacheKey) {
          activeId.value = null
        }
      } finally {
        pendingDetailFetches.delete(cacheKey)
      }
    })()
    pendingDetailFetches.set(cacheKey, promise)
  }

  function _findConv(identifier) {
    return conversations.value.find(c =>
      c.conversationId === identifier || String(c.id) === String(identifier)
    )
  }

  async function togglePin(id) {
    const conv = _findConv(id)
    if (!conv) return
    const newState = !conv.pinnedAt
    await toggleConversationPin(conv.conversationId, newState)
    conv.pinnedAt = newState ? new Date().toISOString() : null
  }

  async function toggleArchive(id) {
    const conv = _findConv(id)
    if (!conv) return
    const newState = !conv.archived
    await toggleConversationArchive(conv.conversationId, newState)
    conv.archived = newState
  }

  async function deleteConversation(id) {
    const conv = _findConv(id)
    const cid = conv ? conv.conversationId : id
    await deleteConvApi(cid)
    conversations.value = conversations.value.filter(c => c.conversationId !== cid)
    if (activeId.value === id || activeId.value === cid) {
      activeId.value = null
    }
  }

  function cacheDetail(convId, detail) {
    const cacheKey = String(convId)
    conversationDetailCache.value.set(cacheKey, detail)
    if (detail.conversationId && detail.conversationId !== cacheKey) {
      conversationDetailCache.value.set(detail.conversationId, detail)
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
    loadList, loadMore, setActive, cacheDetail, togglePin, toggleArchive, deleteConversation, createGroup,
    pinnedMessages, loadPinnedMessages
  }
})
