import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { streamChat, stopChat, interruptChat } from '@/api/chat'
import { fetchMessages } from '@/api/conversations'
import { addReaction, removeReaction, fetchReactions, regenerateMessage } from '@/api/messages'
import { useAuthStore } from '@/stores/auth'
import { useConversationStore } from '@/stores/conversation'
import { useSSE } from '@/composables/useSSE'
import { useOrchestratorStore } from '@/stores/orchestrator'
import { useArtifactStore } from '@/stores/artifact'

let msgIdCounter = 0
function nextLocalId() {
  return `local_${++msgIdCounter}_${Date.now()}`
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const conversationId = ref(null)
  const isStreaming = ref(false)
  const streamError = ref('')
  const currentTurnId = ref(null)
  const hasMoreHistory = ref(false)
  const nextBeforeId = ref(null)
  const messageReactionsMap = ref(new Map())
  const pendingReactions = ref(new Set())
  const replyTo = ref(null) // { id, preview, senderName } | null

  // Multi-agent group chat: maps agentName → local message id
  const agentStreams = ref(new Map())

  // Router must be obtained at store setup time, not inside async SSE callbacks
  const router = useRouter()

  function setReplyTo(msg) {
    replyTo.value = {
      id: msg.id,
      preview: (msg.content || '').slice(0, 80),
      senderName: msg.senderAgentName || '你'
    }
  }

  function clearReplyTo() {
    replyTo.value = null
  }

  function cleanupAgentStreams(status) {
    const toRemove = []
    agentStreams.value.forEach((agentId, agentName) => {
      const msg = messages.value.find(m => m.id === agentId)
      if (msg && (msg.status === 'waiting' || msg.status === 'ready')) {
        return // keep READY/WAITING agents intact
      }
      updateMessage(agentId, { status })
      toRemove.push(agentName)
    })
    toRemove.forEach(name => agentStreams.value.delete(name))
  }

  let sse = null

  const isEmpty = computed(() => messages.value.length === 0)

  function addMessageLocal(role, content, extra = {}) {
    const id = nextLocalId()
    messages.value.push({
      id,
      role,
      content,
      messageType: extra.messageType || 'text',
      senderAgentId: extra.senderAgentId || null,
      senderAgentName: extra.senderAgentName || null,
      senderAgentAvatarUrl: extra.senderAgentAvatarUrl || null,
      artifactRefs: extra.artifactRefs || null,
      replyToId: extra.replyToId || null,
      status: extra.status || 'pending',
      tokenUsage: null,
      createTime: new Date().toISOString(),
      ...extra
    })
    return id
  }

  function updateMessage(id, updates) {
    const idx = messages.value.findIndex(m => m.id === id)
    if (idx !== -1) {
      messages.value[idx] = { ...messages.value[idx], ...updates }
    }
  }

  async function loadReactions(messageId) {
    try {
      const data = await fetchReactions(messageId)
      const authStore = useAuthStore()
      const myId = authStore.userId
      const list = []
      if (data && typeof data === 'object') {
        for (const [type, users] of Object.entries(data)) {
          const count = Array.isArray(users) ? users.length : 0
          const hasMyReaction = Array.isArray(users)
            ? users.some(u => (u.userId || u.id) === myId)
            : false
          if (count > 0) list.push({ reactionType: type, count, hasMyReaction })
        }
      }
      messageReactionsMap.value.set(messageId, list)
    } catch (err) {
      console.warn('Failed to load reactions:', err)
    }
  }

  function getReactions(messageId) {
    return messageReactionsMap.value.get(messageId) || []
  }

  async function initConversation(convId) {
    conversationId.value = convId
    replyTo.value = null
    agentStreams.value.clear()
    messages.value = []
    messageReactionsMap.value.clear()

    if (!convId) return

    try {
      const data = await fetchMessages(convId, { limit: 50 })
      const msgs = data?.messages || []
      messages.value = msgs
      hasMoreHistory.value = data?.hasMore || false
      nextBeforeId.value = data?.nextBeforeId || null
    } catch (err) {
      console.warn('Failed to load message history:', err)
      if (err.response?.status === 403 || err.response?.status === 404) {
        conversationId.value = null
        router.replace('/chat')
      }
    }
  }

  async function loadMoreHistory() {
    if (!hasMoreHistory.value || !nextBeforeId.value || !conversationId.value) return

    try {
      const data = await fetchMessages(conversationId.value, {
        beforeId: nextBeforeId.value,
        limit: 50
      })
      const msgs = data?.messages || []
      messages.value = [...msgs, ...messages.value]
      hasMoreHistory.value = data?.hasMore || false
      nextBeforeId.value = data?.nextBeforeId || null
    } catch (err) {
      console.warn('Failed to load more history:', err)
    }
  }

  async function sendMessage(text, agentId, options = {}) {
    console.log('[chatStore] sendMessage called, text:', text, 'agentId:', agentId, 'isStreaming:', isStreaming.value)
    if (!text.trim() || isStreaming.value) {
      console.log('[chatStore] sendMessage blocked — empty text or already streaming')
      return
    }

    streamError.value = ''
    addMessageLocal('user', text, { replyToId: replyTo.value?.id || null })

    const assistantId = addMessageLocal('assistant', '', { status: 'streaming' })

    isStreaming.value = true

    sse = useSSE()

    // ── Safety timeouts: prevent UI from getting stuck if SSE drops ──
    let contentReceived = false
    const NO_CONTENT_TIMEOUT = 30_000   // 30s without any content delta
    const MAX_STREAM_TIME = 90_000      // 90s total max

    let contentTimeoutId = setTimeout(() => {
      if (!contentReceived && isStreaming.value && agentStreams.value.size === 0) {
        streamError.value = 'Agent 响应超时，请重试'
        updateMessage(assistantId, { status: 'error' })
        isStreaming.value = false
        sse.disconnect()
      }
    }, NO_CONTENT_TIMEOUT)

    let maxTimeId
    function scheduleMaxTimeCheck() {
      maxTimeId = setTimeout(() => {
        if (!isStreaming.value) return
        if (agentStreams.value.size > 0) {
          scheduleMaxTimeCheck()
          return
        }
        updateMessage(assistantId, { status: 'completed' })
        isStreaming.value = false
        sse.disconnect()
      }, MAX_STREAM_TIME)
    }
    scheduleMaxTimeCheck()

    // Backend broadcasts content as "content_delta" events
    sse.on('content_delta', (data) => {
      contentReceived = true
      clearTimeout(contentTimeoutId)
      contentTimeoutId = setTimeout(() => {
        if (isStreaming.value && agentStreams.value.size === 0) {
          streamError.value = 'Agent 响应超时，请重试'
          updateMessage(assistantId, { status: 'error' })
          isStreaming.value = false
          sse.disconnect()
        }
      }, NO_CONTENT_TIMEOUT)

      // Route to correct agent bubble in group chat, or orchestrator otherwise
      const targetId = data.agentName
        ? agentStreams.value.get(data.agentName)
        : assistantId
      if (targetId) {
        const msg = messages.value.find(m => m.id === targetId)
        if (msg) {
          updateMessage(targetId, { content: (msg.content || '') + String(data.delta || '') })
        }
      }
    })

    sse.on('tool_call', (data) => {
      const msg = messages.value.find(m => m.id === assistantId)
      if (msg) {
        const indicator = `\n\n> 调用工具: **${data.toolName}**...\n`
        updateMessage(assistantId, { content: (msg.content || '') + indicator })
      }
    })

    sse.on('tool_result', (data) => {
      const msg = messages.value.find(m => m.id === assistantId)
      if (msg) {
        const result = data.output
          ? `\n> 工具结果: ${data.output.slice(0, 200)}${data.output.length > 200 ? '...' : ''}\n`
          : '\n> 工具执行完成\n'
        updateMessage(assistantId, { content: (msg.content || '') + result })
      }
    })

    sse.on('orchestrator_plan', (data) => {
      try {
        const orchStore = useOrchestratorStore()
        orchStore.handleOrchestratorPlan(data)
      } catch (e) {
        console.warn('Orchestrator plan error:', e)
      }
    })

    sse.on('delegation_progress', (data) => {
      try {
        const orchStore = useOrchestratorStore()
        orchStore.handleDelegationProgress(data)
      } catch (e) {
        console.warn('Delegation progress error:', e)
      }
    })

    sse.on('artifact_preview', (data) => {
      const artifactStore = useArtifactStore()
      if (data.artifactId) {
        artifactStore.handleArtifactPreview({
          artifactId: data.artifactId,
          artifactType: data.type || 'html',
          artifactName: data.name || 'New Artifact',
          conversationId: conversationId.value,
          previewUrl: data.previewUrl
        })
        addMessageLocal('assistant', data.previewUrl || '', {
          messageType: 'preview_card',
          artifactRefs: [data.artifactId],
          status: 'completed'
        })
      }
    })

    sse.on('agent_message_start', (data) => {
      const hasDependency = !!data.dependsOn
      const agentId = addMessageLocal('assistant', '', {
        status: hasDependency ? 'waiting' : 'streaming',
        senderAgentName: data.agentName,
        senderAgentId: data.agentId,
        dependsOn: data.dependsOn || null
      })
      agentStreams.value.set(data.agentName, agentId)
    })

    sse.on('agent_message_complete', (data) => {
      const agentId = agentStreams.value.get(data.agentName)
      if (agentId) {
        const status = data.status === 'error' ? 'error'
          : data.status === 'waiting' ? 'waiting'
          : 'completed'
        updateMessage(agentId, {
          status,
          dependsOn: data.dependsOn || undefined
        })
        agentStreams.value.delete(data.agentName)
      }
    })

    sse.on('agent_ready', (data) => {
      const agentId = agentStreams.value.get(data.agentName)
      if (agentId) {
        updateMessage(agentId, {
          status: 'ready',
          dependsOn: data.dependsOn || '',
          taskDescription: data.taskDescription || ''
        })
      }
    })

    sse.on('session', (data) => {
      if (data.conversationId) {
        conversationId.value = data.conversationId
      }
      // Set orchestrator sender name for group chat messages
      const convStore = useConversationStore()
      const conv = convStore.activeConversation
      if (conv?.conversationType === 'group') {
        updateMessage(assistantId, { senderAgentName: '任务分配智能体' })
      }
    })

    sse.on('done', async (data) => {
      doneReceived = true
      clearTimeout(contentTimeoutId)
      clearTimeout(maxTimeId)

      // Replace local message ID with server-assigned ID
      const serverMsgId = data.assistantMessageId ? String(data.assistantMessageId) : null
      if (serverMsgId) {
        const idx = messages.value.findIndex(m => m.id === assistantId)
        if (idx !== -1) {
          messages.value[idx] = { ...messages.value[idx], id: serverMsgId }
        }
      }

      updateMessage(serverMsgId || assistantId, {
        status: 'completed',
        tokenUsage: data.tokenUsage || null
      })

      // Defensive: clean up any sub-agent streams that didn't get agent_message_complete
      cleanupAgentStreams('completed')

      // Fallback: if content_delta events were lost, use the full
      // content from the done payload so the bubble isn't blank
      if (data.content) {
        const msg = messages.value.find(m => m.id === (serverMsgId || assistantId))
        if (msg && (!msg.content || msg.content.trim() === '')) {
          updateMessage(serverMsgId || assistantId, { content: data.content })
        }
      }

      currentTurnId.value = data.turnId || null
      isStreaming.value = false
      sse.disconnect()

      // Reload conversation list so new conversation appears in sidebar
      const convStore = useConversationStore()
      await convStore.loadList()

      // Navigate to conversation page only for new chats (no route param yet)
      if (conversationId.value && !router.currentRoute.value.params.conversationId) {
        const conv = convStore.conversations.find(
          c => String(c.conversationId) === String(conversationId.value)
        )
        if (conv?.conversationId) {
          convStore.setActive(conv.conversationId)
          router.replace(`/chat/${conv.conversationId}`)
        }
      }
    })

    sse.on('error', (data) => {
      clearTimeout(contentTimeoutId)
      clearTimeout(maxTimeId)
      streamError.value = data.message || 'Stream error'
      updateMessage(assistantId, { status: 'error' })
      isStreaming.value = false
      sse.disconnect()
    })

    // ── Lifecycle event handlers (backend emits these for observability) ──
    sse.on('thinking_delta', () => {})
    sse.on('stream_started', () => {})
    sse.on('message_start', () => {})
    sse.on('turn_interrupted', () => {})

    // Fallback: if message_complete arrives but done is lost, auto-complete after 3s
    let doneReceived = false
    sse.on('message_complete', (data) => {
      if (data.status === 'completed' || data.status === 'stopped') {
        setTimeout(() => {
          if (!doneReceived && isStreaming.value && agentStreams.value.size === 0) {
            updateMessage(assistantId, { status: 'completed' })
            isStreaming.value = false
            sse.disconnect()
            clearTimeout(contentTimeoutId)
            clearTimeout(maxTimeId)
          }
        }, 3000)
      }
    })

    sse.connect((signal) => streamChat({
      agentId,
      message: text,
      conversationId: conversationId.value || null,
      ...options
    }, signal))
  }

  function stopGeneration() {
    if (sse) {
      sse.disconnect()
      isStreaming.value = false
    }
    if (conversationId.value) {
      stopChat(conversationId.value).catch(() => {})
    }
  }

  async function handleReaction(messageId, reactionType) {
    const key = `${messageId}:${reactionType}`
    if (pendingReactions.value.has(key)) return
    pendingReactions.value.add(key)

    const list = messageReactionsMap.value.get(messageId) || []
    const item = list.find(r => r.reactionType === reactionType)

    try {
      if (item?.hasMyReaction) {
        await removeReaction(messageId, reactionType)
        item.count = Math.max(0, item.count - 1)
        item.hasMyReaction = false
      } else {
        await addReaction(messageId, reactionType)
        if (item) {
          item.count += 1
          item.hasMyReaction = true
        } else {
          list.push({ reactionType, count: 1, hasMyReaction: true })
        }
      }
      messageReactionsMap.value.set(messageId, list.filter(r => r.count > 0))
    } catch (err) {
      console.warn('Reaction toggle failed:', err)
    } finally {
      pendingReactions.value.delete(key)
    }
  }

  async function handleRegenerate(messageId) {
    const msg = messages.value.find(m => m.id === messageId)
    if (!msg) return

    try {
      await regenerateMessage(messageId)
      if (conversationId.value) {
        await initConversation(conversationId.value)
      }
    } catch (err) {
      console.warn('Regenerate failed:', err)
    }
  }

  function clearMessages() {
    messages.value = []
    conversationId.value = null
    messageReactionsMap.value.clear()
    replyTo.value = null
    agentStreams.value.clear()
    streamError.value = ''
    if (sse) sse.disconnect()
    isStreaming.value = false
  }

  return {
    messages, conversationId, isStreaming, streamError,
    currentTurnId, hasMoreHistory, isEmpty,
    initConversation, loadMoreHistory, sendMessage, stopGeneration,
    handleReaction, handleRegenerate, clearMessages,
    addMessageLocal, updateMessage,
    messageReactionsMap, pendingReactions, loadReactions, getReactions,
    replyTo, setReplyTo, clearReplyTo,
    agentStreams
  }
})
