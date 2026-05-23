<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { useConversationStore } from '@/stores/conversation'
import MessageBubble from './MessageBubble.vue'
import ChatEmpty from './ChatEmpty.vue'
import Composer from './Composer.vue'
import StatusBar from './StatusBar.vue'

const convStore = useConversationStore()

const props = defineProps({
  messages: { type: Array, default: () => [] },
  isStreaming: { type: Boolean, default: false },
  conversation: { type: Object, default: null },
  prefillText: { type: String, default: '' }
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
    <div class="chat-title">
      <span class="title-text">
        {{ conversation?.title || conversation?.agentName || '新对话' }}
      </span>
    </div>

    <div ref="messagesContainer" class="messages-container">
      <ChatEmpty v-if="messages.length === 0" />

      <MessageBubble
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
        :is-pinned="pinnedIds.has(msg.id)"
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

.chat-title {
  padding: 12px 16px;
  border-bottom: 1px solid #E5E5EA;
  background: rgba(255,255,255,0.8);
  backdrop-filter: blur(20px);
}

.title-text {
  font-size: 18px;
  font-weight: 600;
  color: #1D1D1F;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 16px 0;
}
</style>
