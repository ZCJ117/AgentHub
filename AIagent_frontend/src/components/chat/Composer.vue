<script setup>
import { ref, watch, computed, nextTick } from 'vue'
import { NButton, NPopover, NList, NListItem, NAvatar } from 'naive-ui'
import { useConversationStore } from '@/stores/conversation'
import { useChatStore } from '@/stores/chat'
import { useTextareaAutosize } from '@/composables/useTextareaAutosize'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  isStreaming: { type: Boolean, default: false },
  placeholder: { type: String, default: '输入消息...' },
  prefillText: { type: String, default: '' }
})

const emit = defineEmits(['send', 'stop', 'interrupt'])

const convStore = useConversationStore()
const chatStore = useChatStore()
const text = ref('')
const { textarea, onInput: autosizeOnInput } = useTextareaAutosize()

// @mention state
const mentionOpen = ref(false)
const mentionQuery = ref('')
const mentionIndex = ref(0)
const mentionStartPos = ref(-1)

const isGroupChat = computed(() =>
  convStore.activeConversation?.conversationType === 'group'
)

const members = computed(() =>
  convStore.activeConversation?.members || []
)

const filteredMembers = computed(() => {
  if (!mentionQuery.value) return members.value
  const q = mentionQuery.value.toLowerCase()
  return members.value.filter(m =>
    (m.agentName || '').toLowerCase().includes(q)
  )
})

const highlightedMember = computed(() =>
  filteredMembers.value[mentionIndex.value] || null
)

// Watch for prefill text
watch(() => props.prefillText, (val) => {
  if (val) {
    text.value = val
    nextTick(() => {
      if (textarea.value) {
        textarea.value.focus()
        textarea.value.setSelectionRange(val.length, val.length)
        textarea.value.dispatchEvent(new Event('input'))
      }
    })
  }
}, { immediate: true })

function getLastAtIndex(str, pos) {
  for (let i = pos - 1; i >= 0; i--) {
    if (str[i] === '@') {
      const before = str[i - 1]
      if (!before || /\s/.test(before)) {
        return i
      }
    }
    if (/\s/.test(str[i])) break
  }
  return -1
}

function onInput(e) {
  autosizeOnInput(e)
  if (!isGroupChat.value) {
    mentionOpen.value = false
    return
  }

  const el = e.target
  const cursorPos = el.selectionStart
  const val = el.value

  const atPos = getLastAtIndex(val, cursorPos)
  if (atPos !== -1) {
    mentionStartPos.value = atPos
    mentionQuery.value = val.slice(atPos + 1, cursorPos)
    mentionIndex.value = 0
    mentionOpen.value = true
  } else {
    mentionOpen.value = false
    mentionStartPos.value = -1
    mentionQuery.value = ''
  }
}

function selectMember(member) {
  if (mentionStartPos.value < 0) return
  const savedPos = mentionStartPos.value
  const before = text.value.slice(0, savedPos)
  const after = text.value.slice(savedPos + 1 + mentionQuery.value.length)
  text.value = before + '@' + member.agentName + ' ' + after

  mentionOpen.value = false
  mentionStartPos.value = -1
  mentionQuery.value = ''
  mentionIndex.value = 0

  nextTick(() => {
    if (textarea.value) {
      const newPos = savedPos + member.agentName.length + 2
      textarea.value.focus()
      textarea.value.setSelectionRange(newPos, newPos)
      textarea.value.dispatchEvent(new Event('input'))
    }
  })
}

function handleKeydown(e) {
  if (mentionOpen.value) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      mentionIndex.value = (mentionIndex.value + 1) % filteredMembers.value.length
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      mentionIndex.value = (mentionIndex.value - 1 + filteredMembers.value.length) % filteredMembers.value.length
      return
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      if (highlightedMember.value) {
        selectMember(highlightedMember.value)
      }
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      mentionOpen.value = false
      mentionStartPos.value = -1
      mentionQuery.value = ''
      return
    }
  }

  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function handleSend() {
  const val = text.value.trim()
  if (!val || props.disabled) return
  emit('send', val)
  chatStore.clearReplyTo()
  text.value = ''
}

function handleInterrupt() {
  emit('interrupt')
}

function closeMention() {
  setTimeout(() => {
    mentionOpen.value = false
    mentionStartPos.value = -1
    mentionQuery.value = ''
  }, 150)
}
</script>

<template>
  <div class="composer">
    <div class="composer-inner">
      <div v-if="chatStore.replyTo" class="reply-indicator">
        <span class="reply-indicator-text">正在回复 <strong>{{ chatStore.replyTo.senderName }}</strong>：{{ chatStore.replyTo.preview }}</span>
        <NButton text size="tiny" @click="chatStore.clearReplyTo()">✕ 取消</NButton>
      </div>
      <NPopover
        :show="mentionOpen"
        trigger="manual"
        placement="top-start"
        :width="280"
        display-directive="show"
      >
        <template #trigger>
          <textarea
            ref="textarea"
            v-model="text"
            class="composer-input"
            :placeholder="placeholder"
            :disabled="disabled"
            rows="1"
            @input="onInput"
            @keydown="handleKeydown"
            @blur="closeMention"
          />
        </template>
        <div class="mention-popover">
          <div class="mention-title">选择 Agent</div>
          <NList
            v-if="filteredMembers.length > 0"
            hoverable
            clickable
            :show-divider="false"
            style="max-height: 240px; overflow-y: auto"
          >
            <NListItem
              v-for="(member, idx) in filteredMembers"
              :key="member.agentId"
              :class="{ 'mention-item-active': idx === mentionIndex }"
              @click="selectMember(member)"
            >
              <template #prefix>
                <NAvatar v-if="member.avatarUrl" :size="28" round :src="member.avatarUrl">
                  <template #fallback>
                    {{ (member.agentName || '?')[0] }}
                  </template>
                </NAvatar>
                <NAvatar v-else :size="28" round>
                  {{ (member.agentName || '?')[0] }}
                </NAvatar>
              </template>
              {{ member.agentName }}
            </NListItem>
          </NList>
          <div v-else class="mention-empty">无匹配 Agent</div>
        </div>
      </NPopover>

      <NButton
        v-if="isStreaming"
        type="warning"
        ghost
        @click="handleInterrupt"
        class="send-btn"
      >
        打断
      </NButton>
      <NButton
        v-if="!isStreaming"
        type="primary"
        :disabled="!text.trim()"
        @click="handleSend"
        class="send-btn"
      >
        发送
      </NButton>
      <NButton
        v-if="isStreaming"
        type="error"
        @click="emit('stop')"
        class="send-btn"
      >
        停止
      </NButton>
    </div>
  </div>
</template>

<style scoped>
.composer {
  padding: 16px;
  background: #FFFFFF;
  border-top: 1px solid #E5E5EA;
}

.composer-inner {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  max-width: 900px;
  margin: 0 auto;
  flex-wrap: wrap;
}

.reply-indicator {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  margin-bottom: 8px;
  background: rgba(46, 117, 182, 0.06);
  border-left: 3px solid #2E75B6;
  border-radius: 8px;
  font-size: 13px;
  color: #555;
  width: 100%;
}

.reply-indicator-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
  margin-right: 8px;
}

.composer-input {
  flex: 1;
  resize: none;
  border: 1px solid #E5E5EA;
  border-radius: 14px;
  padding: 10px 16px;
  font-size: 16px;
  line-height: 1.5;
  font-family: inherit;
  outline: none;
  background: #F5F5F7;
  transition: border-color 0.15s;
  min-height: 46px;
  max-height: 180px;
}

.composer-input:focus {
  border-color: #2E75B6;
  background: #FFFFFF;
}

.send-btn {
  flex-shrink: 0;
  border-radius: 14px;
  height: 42px;
}

.mention-popover {
  padding: 4px 0;
}

.mention-title {
  font-size: 12px;
  color: #999;
  padding: 4px 12px 8px;
}

.mention-empty {
  text-align: center;
  color: #999;
  padding: 16px;
  font-size: 14px;
}

.mention-item-active {
  background: rgba(46, 117, 182, 0.1);
}
</style>
