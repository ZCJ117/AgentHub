<script setup>
import { ref, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useConversationStore } from '@/stores/conversation'
import { useChatStore } from '@/stores/chat'
import { useAgentStore } from '@/stores/agent'
import { useOrchestratorStore } from '@/stores/orchestrator'
import { useArtifactStore } from '@/stores/artifact'
import { interruptChat, continueDag } from '@/api/chat'
import { pinMessage, unpinMessage } from '@/api/conversations'
import { fetchReplyChain } from '@/api/messages'
import { renderMarkdown } from '@/composables/useMarkdown'
import { NModal, NTimeline, NTimelineItem, NIcon } from 'naive-ui'
import { ChevronForwardOutline } from '@vicons/ionicons5'
import ConversationSidebar from '@/components/chat/ConversationSidebar.vue'
import ChatArea from '@/components/chat/ChatArea.vue'
import TopBar from '@/components/layout/TopBar.vue'
import DetailPanel from '@/components/layout/DetailPanel.vue'

const route = useRoute()
const router = useRouter()
const convStore = useConversationStore()
const chatStore = useChatStore()
const agentStore = useAgentStore()
const orchStore = useOrchestratorStore()
const artifactStore = useArtifactStore()

const composerPrefillText = ref('')
const chatAreaRef = ref(null)
const sidebarCollapsed = ref(false)

provide('scrollToMessage', (messageId) => {
  chatAreaRef.value?.scrollToMessage(messageId)
})

watch(
  () => route.params.conversationId,
  async (id) => {
    const convId = id || null
    if (String(convStore.activeId) === String(convId) && String(chatStore.conversationId) === String(convId)) return
    convStore.setActive(convId)
    await chatStore.initConversation(convId)
    if (convId) {
      try { await convStore.loadPinnedMessages(convId) } catch {}
    }
  },
  { immediate: true }
)

function getEffectiveAgentId() {
  if (convStore.activeConversation?.conversationType === 'group') {
    return null  // Group chat uses arther-agent Orchestrator, no single agent
  }
  return convStore.activeConversation?.agentId || agentStore.selectedAgentId
}

function handleSendMessage(text) {
  const isGroupChat = convStore.activeConversation?.conversationType === 'group'
  const agentId = getEffectiveAgentId()
  if (!agentId && !isGroupChat) {
    if (agentStore.agents.length > 0) {
      agentStore.selectAgent(agentStore.agents[0].id)
      chatStore.sendMessage(text, agentStore.agents[0].id)
    }
    return
  }

  // Parse @mentions
  const members = convStore.activeConversation?.members || []
  const mentionRegex = /@(\S+)/g
  const matches = [...text.matchAll(mentionRegex)]
  const mentionedAgentIds = members
    .filter(m => matches.some(match => match[1] === m.agentName))
    .map(m => m.agentId)

  chatStore.sendMessage(text, agentId, mentionedAgentIds.length > 0 ? { mentionedAgentIds } : undefined)
  composerPrefillText.value = ''
}

function handleStopGeneration() {
  chatStore.stopGeneration()
}

async function handleInterrupt() {
  const convId = convStore.activeId
  if (!convId) return
  await interruptChat(convId, '请暂停当前任务')
  chatStore.stopGeneration()
}

function handleApplyDiff(messageId) {
  const msg = chatStore.messages.find(m => m.id === messageId)
  if (!msg) return
  const agentId = getEffectiveAgentId()
  if (!agentId) return
  const diffMessage = 'Apply the following diff:\n' + (msg.content || '')
  chatStore.sendMessage(diffMessage, agentId)
}

function handleRejectDiff(messageId) {
  chatStore.updateMessage(messageId, { diffRejected: true })
}

function handlePreviewArtifact(artifactId) {
  if (!artifactId) return
  router.push(`/artifacts/${artifactId}`)
}

function handleEditArtifact(artifactId) {
  if (!artifactId) return
  composerPrefillText.value = `请修改 @artifact:${artifactId}`
}

function handleDeployArtifact(artifactId) {
  if (!artifactId) return
  artifactStore.deploy(artifactId, {})
}

function handleDownloadArtifact(artifactId) {
  if (!artifactId) return
  window.open(`/api/v1/artifacts/${artifactId}/download`, '_blank')
}

async function handleCancelTask(taskId) {
  if (!taskId) return
  await orchStore.cancelTask(taskId)
}

async function handleContinueDag(message) {
  const convId = chatStore.conversationId
  const agentName = message.senderAgentName
  if (!convId || !agentName) return
  try {
    await continueDag(convId, agentName)
  } catch (e) {
    console.error('Failed to continue DAG:', e)
  }
}

async function handleRetryTask(taskId, assignmentIds) {
  if (!taskId) return
  await orchStore.retryAssignments(taskId, assignmentIds || [])
}

async function handlePinMessage(messageId) {
  const convId = convStore.activeId
  if (!convId) return
  await pinMessage(convId, { messageId, note: '' })
  await convStore.loadPinnedMessages(convId)
}

async function handleUnpinMessage(messageId) {
  const convId = convStore.activeId
  if (!convId) return
  await unpinMessage(convId, messageId)
  await convStore.loadPinnedMessages(convId)
}

const replyChain = ref([])
const showReplyChain = ref(false)

async function handleShowReplyChain(messageId) {
  try {
    const data = await fetchReplyChain(messageId)
    replyChain.value = Array.isArray(data) ? data : []
    showReplyChain.value = true
  } catch (e) {
    console.warn('Failed to load reply chain:', e)
  }
}

function handleReply(msg) {
  chatStore.setReplyTo(msg)
}

function formatTimestamp(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const h = String(d.getHours()).padStart(2, '0')
  const m = String(d.getMinutes()).padStart(2, '0')
  return h + ':' + m
}
</script>

<template>
  <div class="chat-view">
    <ConversationSidebar v-show="!sidebarCollapsed" @collapse="sidebarCollapsed = true" />
    <button v-show="sidebarCollapsed" class="sidebar-expand-btn" @click="sidebarCollapsed = false">
      <NIcon :component="ChevronForwardOutline" size="18" />
    </button>
    <div class="chat-main">
      <TopBar />
      <ChatArea
        ref="chatAreaRef"
        :messages="chatStore.messages"
        :is-streaming="chatStore.isStreaming"
        :conversation="convStore.activeConversation"
        :prefill-text="composerPrefillText"
        @send="handleSendMessage"
        @stop="handleStopGeneration"
        @interrupt="handleInterrupt"
        @regenerate="chatStore.handleRegenerate"
        @reaction="chatStore.handleReaction"
        @apply-diff="handleApplyDiff"
        @reject-diff="handleRejectDiff"
        @preview-artifact="handlePreviewArtifact"
        @edit-artifact="handleEditArtifact"
        @deploy-artifact="handleDeployArtifact"
        @download-artifact="handleDownloadArtifact"
        @cancel-task="handleCancelTask"
        @continue-dag="handleContinueDag"
        @retry-task="handleRetryTask"
        @pin-message="handlePinMessage"
        @unpin-message="handleUnpinMessage"
        @reply="handleReply"
        @show-reply-chain="handleShowReplyChain"
      />
    </div>
    <DetailPanel @unpin-message="handleUnpinMessage" />

    <NModal v-model:show="showReplyChain" preset="card" title="回复链" style="max-width:560px">
      <NTimeline>
        <NTimelineItem
          v-for="item in replyChain"
          :key="item.id"
          :title="(item.senderAgentName || '你') + ' · ' + formatTimestamp(item.createdAt || item.createTime)"
          :color="item.role === 'user' ? '#1a73e8' : '#34a853'"
        >
          <div v-html="renderMarkdown((item.content || '').slice(0, 300))" />
        </NTimelineItem>
      </NTimeline>
    </NModal>
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  height: 100vh;
  background: #F5F5F7;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #FFFFFF;
}

.sidebar-expand-btn {
  width: 32px;
  height: 60px;
  align-self: center;
  border: 1px solid #E5E5EA;
  border-left: none;
  border-radius: 0 8px 8px 0;
  background: #F5F5F7;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #666;
}
.sidebar-expand-btn:hover {
  background: #E8E8ED;
}
</style>
