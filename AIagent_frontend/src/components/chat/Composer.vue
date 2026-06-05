<script setup>
import { ref, watch, computed, nextTick } from 'vue'
import { NButton, NPopover, NList, NListItem, NAvatar, useMessage } from 'naive-ui'
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
const message = useMessage()

// ── 文件上传状态 ──
const SUPPORTED_TYPES = [
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'text/plain',
  'text/markdown',
  'text/x-markdown'
]
const MAX_FILE_SIZE = 10 * 1024 * 1024
const MAX_FILE_COUNT = 5

const pendingFiles = ref([])
const isDragging = ref(false)
const uploadErrors = ref(new Map())
let fileIdCounter = 0
let dragCounter = 0

function acceptFile(file) {
  if (!SUPPORTED_TYPES.includes(file.type) && !file.name.match(/\.(md|txt)$/i)) {
    message.warning(`不支持的文件类型: ${file.name}`)
    return false
  }
  if (file.size > MAX_FILE_SIZE) {
    message.warning(`${file.name} 超过 10MB 限制`)
    return false
  }
  return true
}

function addFiles(files) {
  const remaining = MAX_FILE_COUNT - pendingFiles.value.length
  if (remaining <= 0) {
    message.warning(`最多上传 ${MAX_FILE_COUNT} 个文件`)
    return
  }
  const toAdd = []
  for (const f of files) {
    if (toAdd.length >= remaining) {
      message.warning(`最多上传 ${MAX_FILE_COUNT} 个文件，已截断`)
      break
    }
    if (acceptFile(f)) {
      toAdd.push({ id: ++fileIdCounter, name: f.name, size: f.size, type: f.type, file: f })
    }
  }
  pendingFiles.value = [...pendingFiles.value, ...toAdd]
}

function removeFile(id) {
  pendingFiles.value = pendingFiles.value.filter(f => f.id !== id)
  uploadErrors.value.delete(id)
}

function handleFileInput(e) {
  if (e.target.files?.length) addFiles(e.target.files)
  e.target.value = ''
}

function onDragOver(e) {
  e.preventDefault()
}

function onDragEnter(e) {
  e.preventDefault()
  dragCounter++
  isDragging.value = true
}

function onDragLeave(e) {
  dragCounter--
  if (dragCounter <= 0) {
    dragCounter = 0
    isDragging.value = false
  }
}

function onDrop(e) {
  e.preventDefault()
  dragCounter = 0
  isDragging.value = false
  if (e.dataTransfer?.files?.length) addFiles(e.dataTransfer.files)
}
// ── 文件上传状态结束 ──

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
  if (pendingFiles.value.length === 0) {
    emit('send', val)
    chatStore.clearReplyTo()
    text.value = ''
    return
  }
  emit('send', val, pendingFiles.value.map(f => f.file))
  chatStore.clearReplyTo()
  text.value = ''
  pendingFiles.value = []
  uploadErrors.value.clear()
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

function formatSize(bytes) {
  if (bytes < 1024) return bytes + 'B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'KB'
  return (bytes / (1024 * 1024)).toFixed(1) + 'MB'
}

function onPaste(e) {
  // 暂不处理粘贴文件
}
</script>

<template>
  <div
    class="composer"
    @dragover="onDragOver"
    @dragenter="onDragEnter"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div v-if="isDragging" class="drop-overlay">
      <div class="drop-hint">释放以上传文件</div>
    </div>
    <div class="composer-inner">
      <div v-if="chatStore.replyTo" class="reply-indicator">
        <span class="reply-indicator-text">正在回复 <strong>{{ chatStore.replyTo.senderName }}</strong>：{{ chatStore.replyTo.preview }}</span>
        <NButton text size="tiny" @click="chatStore.clearReplyTo()">✕ 取消</NButton>
      </div>

      <div v-if="pendingFiles.length > 0" class="file-tags">
        <span
          v-for="f in pendingFiles"
          :key="f.id"
          class="file-tag"
          :class="{ 'file-tag-error': uploadErrors.has(f.id) }"
        >
          📄 {{ f.name }}
          <span class="file-tag-size">{{ formatSize(f.size) }}</span>
          <span class="file-tag-remove" @click="removeFile(f.id)">✕</span>
        </span>
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
            @paste="onPaste"
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

      <NButton text class="attach-btn" @click="$refs.fileInput.click()" title="添加文件">
        📎
      </NButton>
      <input
        ref="fileInput"
        type="file"
        multiple
        :accept="SUPPORTED_TYPES.join(',')"
        style="display:none"
        @change="handleFileInput"
      />

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
  position: relative;
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

.drop-overlay {
  position: absolute;
  inset: 0;
  background: rgba(46, 117, 182, 0.08);
  border: 2px dashed #2E75B6;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  pointer-events: none;
}
.drop-hint {
  font-size: 16px;
  color: #2E75B6;
  font-weight: 500;
}
.file-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  width: 100%;
  margin-bottom: 6px;
}
.file-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: #F0F4FF;
  border: 1px solid #C4D7FF;
  border-radius: 6px;
  padding: 3px 8px;
  font-size: 12px;
  color: #333;
}
.file-tag-error {
  background: #FFF3F0;
  border-color: #FFC4B8;
  color: #E74C3C;
}
.file-tag-size {
  color: #999;
  font-size: 11px;
}
.file-tag-remove {
  cursor: pointer;
  color: #999;
  margin-left: 2px;
}
.file-tag-remove:hover {
  color: #E74C3C;
}
.attach-btn {
  font-size: 18px;
  color: #999;
  padding: 4px 8px;
  flex-shrink: 0;
}
.attach-btn:hover {
  color: #2E75B6;
}
</style>
