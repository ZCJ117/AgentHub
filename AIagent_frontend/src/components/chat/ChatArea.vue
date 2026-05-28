<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { useConversationStore } from '@/stores/conversation'
import { useAgentStore } from '@/stores/agent'
import { NAvatar } from 'naive-ui'
import MessageBubble from './MessageBubble.vue'
import ChatEmpty from './ChatEmpty.vue'
import Composer from './Composer.vue'
import StatusBar from './StatusBar.vue'

const convStore = useConversationStore()
const agentStore = useAgentStore()

const props = defineProps({
  messages: { type: Array, default: () => [] },
  isStreaming: { type: Boolean, default: false },
  conversation: { type: Object, default: null },
  prefillText: { type: String, default: '' }
})

const headerTitle = computed(() => {
  if (props.conversation?.title) return props.conversation.title
  if (props.conversation?.agentName) return props.conversation.agentName
  if (agentStore.selectedAgent?.name) return agentStore.selectedAgent.name
  return '新对话'
})

const headerSubtitle = computed(() => {
  const conv = props.conversation
  if (conv?.conversationType === 'group') {
    const count = conv.members?.length || 0
    return count > 0 ? `群聊 · ${count} 人` : '群聊'
  }
  const agentName = conv?.agentName || agentStore.selectedAgent?.name
  return agentName ? `AI 对话 · ${agentName}` : 'AI 对话'
})

const agentNames = computed(() => {
  const members = props.conversation?.members
  if (!members || !Array.isArray(members)) return []
  return members.map(m => m.agentName).filter(Boolean)
})

const headerAvatar = computed(() => {
  if (props.conversation?.agentAvatarUrl) return props.conversation.agentAvatarUrl
  if (agentStore.selectedAgent?.avatarUrl) return agentStore.selectedAgent.avatarUrl
  return null
})

const emit = defineEmits([
  'send', 'stop', 'regenerate', 'reaction', 'interrupt',
  'applyDiff', 'rejectDiff',
  'previewArtifact', 'editArtifact', 'deployArtifact', 'downloadArtifact',
  'cancelTask', 'retryTask',
  'pinMessage', 'unpinMessage',
  'reply', 'showReplyChain'
])

const messagesContainer = ref(null)

const pinnedIds = computed(() =>
  new Set((convStore.pinnedMessages || []).map(p => p.messageId))
)

function scrollToMessage(messageId) {
  const el = document.getElementById(`msg-${messageId}`)
  if (el && messagesContainer.value) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

defineExpose({ scrollToMessage })

watch(
  () => props.messages.length,
  async (newLen, oldLen) => {
    await nextTick()
    if (!messagesContainer.value) return
    const el = messagesContainer.value
    const wasNearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
    if (wasNearBottom || oldLen === 0) {
      el.scrollTop = el.scrollHeight
    }
  },
  { flush: 'post' }
)
</script>

<template>
  <div class="chat-area">
    <div class="chat-header">
      <NAvatar :size="38" round :src="headerAvatar" :fallback-src="undefined">
        {{ (headerTitle || 'AI')[0] }}
      </NAvatar>
      <div class="header-text">
        <span class="header-title">{{ headerTitle }}</span>
        <span class="header-subtitle">{{ headerSubtitle }}</span>
      </div>
    </div>

    <div ref="messagesContainer" class="messages-container">
      <ChatEmpty v-if="messages.length === 0" />

      <MessageBubble
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
        :is-pinned="pinnedIds.has(msg.id)"
        :agent-names="agentNames"
        @regenerate="emit('regenerate', $event)"
        @reaction="(msgId, type) => emit('reaction', msgId, type)"
        @apply-diff="id => emit('applyDiff', id)"
        @reject-diff="id => emit('rejectDiff', id)"
        @preview-artifact="id => emit('previewArtifact', id)"
        @edit-artifact="id => emit('editArtifact', id)"
        @deploy-artifact="id => emit('deployArtifact', id)"
        @download-artifact="id => emit('downloadArtifact', id)"
        @cancel-task="taskId => emit('cancelTask', taskId)"
        @retry-task="(taskId, ids) => emit('retryTask', taskId, ids)"
        @pin-message="id => emit('pinMessage', id)"
        @unpin-message="id => emit('unpinMessage', id)"
        @reply="msg => emit('reply', msg)"
        @show-reply-chain="id => emit('showReplyChain', id)"
      />
    </div>

    <Composer
      :disabled="isStreaming"
      :is-streaming="isStreaming"
      :prefill-text="prefillText"
      :placeholder="conversation ? '输入消息...' : '选择一个 Agent 开始对话...'"
      @send="emit('send', $event)"
      @stop="emit('stop')"
      @interrupt="emit('interrupt')"
    />
  </div>
</template>

<style scoped>
.chat-area {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid #E5E5EA;
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(20px);
}

.header-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #1D1D1F;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-subtitle {
  font-size: 12px;
  color: #86868B;
  line-height: 1.3;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 16px 0;
}
</style>
