import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { streamChat, stopChat, interruptChat } from '@/api/chat'
import { fetchMessages } from '@/api/conversations'
import { addReaction, regenerateMessage } from '@/api/messages'
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

  async function initConversation(convId) {
    conversationId.value = convId
    messages.value = []

    if (!convId) return

    try {
      const data = await fetchMessages(convId, { limit: 50 })
      const records = data?.records || []
      messages.value = records.reverse()
      hasMoreHistory.value = data?.hasMore || false
      nextBeforeId.value = data?.nextBeforeId || null
    } catch (err) {
      console.warn('Failed to load message history:', err)
    }
  }

  async function loadMoreHistory() {
    if (!hasMoreHistory.value || !nextBeforeId.value || !conversationId.value) return

    try {
      const data = await fetchMessages(conversationId.value, {
        beforeId: nextBeforeId.value,
        limit: 50
      })
      const records = data?.records || []
      messages.value = [...records.reverse(), ...messages.value]
      hasMoreHistory.value = data?.hasMore || false
      nextBeforeId.value = data?.nextBeforeId || null
    } catch (err) {
      console.warn('Failed to load more history:', err)
    }
  }

  async function sendMessage(text, agentId, options = {}) {
    if (!text.trim() || isStreaming.value) return

    streamError.value = ''

    addMessageLocal('user', text)

    const assistantId = addMessageLocal('assistant', '', { status: 'streaming' })

    isStreaming.value = true

    sse = useSSE()

    sse.on('text', (data) => {
      const msg = messages.value.find(m => m.id === assistantId)
      if (msg) {
        updateMessage(assistantId, { content: (msg.content || '') + (data.delta || '') })
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

    sse.on('done', (data) => {
      updateMessage(assistantId, {
        status: 'completed',
        tokenUsage: data.tokenUsage || null
      })
      currentTurnId.value = data.turnId || null
      isStreaming.value = false
      sse.disconnect()
    })

    sse.on('error', (data) => {
      streamError.value = data.message || 'Stream error'
      updateMessage(assistantId, { status: 'error' })
      isStreaming.value = false
      sse.disconnect()
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
    const msg = messages.value.find(m => m.id === messageId)
    if (!msg || msg.role !== 'assistant') return

    try {
      await addReaction(messageId, reactionType)
    } catch (err) {
      console.warn('Reaction failed:', err)
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
    streamError.value = ''
    if (sse) sse.disconnect()
    isStreaming.value = false
  }

  return {
    messages, conversationId, isStreaming, streamError,
    currentTurnId, hasMoreHistory, isEmpty,
    initConversation, loadMoreHistory, sendMessage, stopGeneration,
    handleReaction, handleRegenerate, clearMessages,
    addMessageLocal, updateMessage
  }
})
