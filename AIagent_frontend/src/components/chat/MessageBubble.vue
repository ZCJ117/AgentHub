<script setup>
import { computed, onMounted } from 'vue'
import { renderMarkdown } from '@/composables/useMarkdown'
import { NAvatar, NButton, NCode, NTag, NIcon, NImage } from 'naive-ui'
import { PinOutline, Pin } from '@vicons/ionicons5'
import PlanCard from './PlanCard.vue'
import DiffViewCard from './DiffViewCard.vue'
import ArtifactPreviewCard from './ArtifactPreviewCard.vue'
import { useArtifactStore } from '@/stores/artifact'
import { useChatStore } from '@/stores/chat'

const EMOJI_MAP = { like: '👍', dislike: '👎', regenerate: '🔄', apply_diff: '✅' }

const props = defineProps({
  message: { type: Object, required: true },
  isPinned: { type: Boolean, default: false }
})

const artifactStore = useArtifactStore()
const chatStore = useChatStore()

const previewCardArtifact = computed(() => {
  const refId = props.message?.artifactRefs?.[0]
  if (!refId) return null
  return artifactStore.artifacts.find(a => a.id === refId) || null
})

const emit = defineEmits([
  'regenerate', 'reaction',
  'cancelTask', 'retryTask',
  'applyDiff', 'rejectDiff',
  'previewArtifact', 'editArtifact', 'deployArtifact', 'downloadArtifact',
  'pinMessage', 'unpinMessage',
  'reply', 'showReplyChain'
])

const isUser = computed(() => props.message.role === 'user')
const isStreaming = computed(() => props.message.status === 'streaming')
const isError = computed(() => props.message.status === 'error')

const renderedContent = computed(() => {
  if (props.message.messageType === 'text' || props.message.messageType === 'system') {
    return renderMarkdown(props.message.content || '')
  }
  return ''
})

const reactions = computed(() => {
  return chatStore.getReactions(props.message.id)
})

const visibleReactions = computed(() => {
  if (props.message.role === 'assistant') return reactions.value
  return reactions.value.filter(r => r.reactionType === 'like' || r.reactionType === 'dislike')
})

const codeLanguage = computed(() => {
  const match = (props.message.content || '').match(/^```(\w+)/)
  return match ? match[1] : 'text'
})

const codeContent = computed(() => {
  let content = props.message.content || ''
  content = content.replace(/^```\w*\n?/, '').replace(/\n?```$/, '')
  return content
})

onMounted(() => {
  if (props.message.id && !isUser.value && !isStreaming.value) {
    chatStore.loadReactions(props.message.id)
  }
})
</script>

<template>
  <div class="message-row" :class="{ 'is-user': isUser }" :id="'msg-' + message.id">
    <NAvatar
      v-if="!isUser"
      :size="32"
      :src="message.senderAgentAvatarUrl"
      round
      class="msg-avatar"
    >
      {{ (message.senderAgentName || 'AI')[0] }}
    </NAvatar>

    <div class="msg-body" :class="[`msg-type-${message.messageType}`]">
      <div v-if="!isUser && message.senderAgentName" class="msg-agent-name">
        {{ message.senderAgentName }}
        <NTag v-if="message.senderAgentId" size="tiny" :bordered="false" style="margin-left: 4px">
          Agent
        </NTag>
      </div>

      <div
        v-if="message.messageType === 'text' || message.messageType === 'system'"
        class="msg-text markdown-body"
        :class="{ 'is-system': message.messageType === 'system' }"
        v-html="renderedContent"
      />

      <div v-else-if="message.messageType === 'code'" class="msg-code">
        <NCode :code="codeContent" :language="codeLanguage" />
        <NButton size="tiny" quaternary @click="navigator.clipboard?.writeText(codeContent)">
          复制
        </NButton>
      </div>

      <div v-else-if="message.messageType === 'image'" class="msg-image">
        <NImage :src="message.content" alt="attachment" style="max-width:320px;border-radius:14px" />
      </div>

      <div v-else-if="message.messageType === 'file'" class="msg-file">
        <span>📎 {{ message.content }}</span>
        <NButton size="tiny" quaternary @click="window.open(message.content, '_blank', 'noopener')">下载</NButton>
      </div>

      <DiffViewCard
        v-else-if="message.messageType === 'diff'"
        :message="message"
        @apply="emit('applyDiff', $event)"
        @reject="emit('rejectDiff', $event)"
      />

      <PlanCard
        v-else-if="message.messageType === 'plan_card'"
        :message="message"
        @cancel="emit('cancelTask', message.props?.taskId)"
        @retry="emit('retryTask', message.props?.taskId, message.props?.failedAssignmentIds || [])"
      />

      <ArtifactPreviewCard
        v-else-if="message.messageType === 'preview_card'"
        :message="message"
        :artifact="previewCardArtifact"
        @preview="emit('previewArtifact', $event)"
        @edit="emit('editArtifact', $event)"
        @deploy="emit('deployArtifact', $event)"
        @download="emit('downloadArtifact', $event)"
      />

      <div v-else class="msg-text">
        {{ message.content }}
      </div>

      <div v-if="isStreaming" class="msg-status streaming">生成中...</div>
      <div v-else-if="isError" class="msg-status error">生成失败</div>

      <!-- Reaction tags bar -->
      <div v-if="visibleReactions.length > 0" class="msg-reactions">
        <span
          v-for="r in visibleReactions"
          :key="r.reactionType"
          class="reaction-tag"
          :class="{ active: r.hasMyReaction }"
          @click="emit('reaction', props.message.id, r.reactionType)"
        >{{ EMOJI_MAP[r.reactionType] || r.reactionType }} {{ r.count }}</span>
      </div>

      <!-- Action buttons -->
      <div v-if="!isUser && !isStreaming && !isError" class="msg-actions">
        <NButton size="tiny" quaternary @click="emit('reply', props.message)">回复</NButton>
        <NButton size="tiny" quaternary @click="navigator.clipboard?.writeText(message.content)">复制</NButton>
        <NButton size="tiny" quaternary @click="emit('regenerate', message.id)">重新生成</NButton>
        <NButton
          v-if="message.replyToId"
          size="tiny"
          quaternary
          @click="emit('showReplyChain', message.id)"
        >🔗 查看回复链</NButton>
        <NButton
          v-if="!isPinned"
          size="tiny"
          quaternary
          @click.stop="emit('pinMessage', message.id)"
          title="钉选消息"
        >
          <NIcon><PinOutline /></NIcon>
        </NButton>
        <NButton
          v-else
          size="tiny"
          quaternary
          type="primary"
          @click.stop="emit('unpinMessage', message.id)"
          title="取消钉选"
        >
          <NIcon><Pin /></NIcon>
        </NButton>
      </div>

      <div v-if="message.tokenUsage" class="msg-tokens">
        {{ message.tokenUsage.totalTokens }} tokens
      </div>
    </div>

    <div v-if="isUser" class="msg-avatar-spacer" />
  </div>
</template>

<style scoped>
.message-row {
  display: flex;
  gap: 12px;
  padding: 8px 16px;
  max-width: 900px;
  margin: 0 auto;
}

.message-row.is-user {
  flex-direction: row-reverse;
}

.msg-avatar {
  flex-shrink: 0;
  margin-top: 2px;
}

.msg-avatar-spacer {
  width: 32px;
  flex-shrink: 0;
}

.msg-body {
  flex: 1;
  min-width: 0;
}

.msg-agent-name {
  font-size: 14px;
  color: #666;
  margin-bottom: 4px;
}

.msg-text {
  background: #FFFFFF;
  border-radius: 14px;
  padding: 12px 16px;
  font-size: 16px;
  line-height: 1.6;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
  word-break: break-word;
}

.msg-text.is-system {
  border-left: 3px solid #2E75B6;
  background: #F0F4FA;
}

.is-user .msg-text {
  background: #2E75B6;
  color: #FFFFFF;
}

.msg-code {
  background: #FFFFFF;
  border-radius: 14px;
  padding: 12px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
  overflow: hidden;
}

.msg-image img {
  max-width: 320px;
  border-radius: 14px;
  cursor: pointer;
}

.msg-status {
  font-size: 13px;
  margin-top: 4px;
}

.msg-status.streaming {
  color: #2E75B6;
}

.msg-status.error {
  color: #FF3B30;
}

.msg-actions {
  display: flex;
  gap: 4px;
  margin-top: 4px;
  opacity: 0;
  transition: opacity 0.15s;
}

.message-row:hover .msg-actions {
  opacity: 1;
}

.msg-tokens {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}

.preview-iframe {
  width: 100%;
  min-height: 240px;
  border: 1px solid #E5E5EA;
  border-radius: 14px;
}

.markdown-body :deep(pre) {
  background: #1D1D1F;
  color: #F5F5F7;
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 13px;
}

.markdown-body :deep(code) {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 13px;
}

.markdown-body :deep(blockquote) {
  border-left: 3px solid #2E75B6;
  padding-left: 12px;
  color: #666;
  margin: 8px 0;
}

.msg-reactions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 4px;
  margin-left: 0;
}

.reaction-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 8px;
  font-size: 13px;
  background: #fff;
  border: 1px solid #e0e0e0;
  color: #666;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
  user-select: none;
}

.reaction-tag:hover {
  background: rgba(46, 117, 182, 0.06);
  border-color: #2E75B6;
}

.reaction-tag.active {
  background: rgba(46, 117, 182, 0.1);
  border-color: #2E75B6;
  color: #2E75B6;
}
</style>
