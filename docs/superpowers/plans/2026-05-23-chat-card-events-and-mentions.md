# Chat Card Events & @mention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the broken event chain from PlanCard/DiffViewCard/ArtifactPreviewCard through ChatArea to ChatView handlers, add @mention support to Composer, add conversation type filter, and add interrupt button.

**Architecture:** Direct handler pattern — ChatView.vue holds 8 handler functions that call stores/APIs. ChatArea.vue is a transparent event-forwarding layer. Composer gets incremental @mention with NPopover+NList and plain-text `@AgentName` insertion. No store modifications needed.

**Tech Stack:** Vue 3 `<script setup>`, Pinia stores, Naive UI (NPopover, NList, NAvatar, NSegment), Axios API client

---

### Task 1: Fix MessageBubble PlanCard event args

**Files:**
- Modify: `AIagent_frontend/src/components/chat/MessageBubble.vue:93-98`

- [ ] **Step 1: Update PlanCard event bindings to pass IDs from message.props**

Replace lines 93-98:
```html
<PlanCard
  v-else-if="message.messageType === 'plan_card'"
  :message="message"
  @cancel="emit('cancelTask')"
  @retry="emit('retryTask')"
/>
```

With:
```html
<PlanCard
  v-else-if="message.messageType === 'plan_card'"
  :message="message"
  @cancel="emit('cancelTask', message.props?.taskId)"
  @retry="emit('retryTask', message.props?.taskId, message.props?.failedAssignmentIds || [])"
/>
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/components/chat/MessageBubble.vue
git commit -m "fix: pass taskId and assignmentIds from message.props in PlanCard events"
```

---

### Task 2: Add event forwarding in ChatArea.vue

**Files:**
- Modify: `AIagent_frontend/src/components/chat/ChatArea.vue`

- [ ] **Step 1: Add 8 new emits and props to script setup**

Replace the script block (lines 1-33):

```vue
<script setup>
import { ref, watch, nextTick } from 'vue'
import MessageBubble from './MessageBubble.vue'
import ChatEmpty from './ChatEmpty.vue'
import Composer from './Composer.vue'
import StatusBar from './StatusBar.vue'

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
  'cancelTask', 'retryTask'
])

const messagesContainer = ref(null)

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
```

- [ ] **Step 2: Update MessageBubble template to forward card events**

Replace the MessageBubble element in template (lines 46-52):

```html
<MessageBubble
  v-for="msg in messages"
  :key="msg.id"
  :message="msg"
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
/>
```

- [ ] **Step 3: Update Composer in template to forward interrupt and new props**

Replace the Composer element in template (lines 55-60):

```html
<Composer
  :disabled="isStreaming"
  :is-streaming="isStreaming"
  :prefill-text="prefillText"
  :placeholder="conversation ? '输入消息...' : '选择一个 Agent 开始对话...'"
  @send="emit('send', $event)"
  @stop="emit('stop')"
  @interrupt="emit('interrupt')"
/>
```

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/components/chat/ChatArea.vue
git commit -m "feat: forward card events, interrupt, and prefillText through ChatArea"
```

---

### Task 3: Add handlers in ChatView.vue

**Files:**
- Modify: `AIagent_frontend/src/views/ChatView.vue`

- [ ] **Step 1: Add imports and handler logic**

Replace the script block (lines 1-41):

```vue
<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useConversationStore } from '@/stores/conversation'
import { useChatStore } from '@/stores/chat'
import { useAgentStore } from '@/stores/agent'
import { useOrchestratorStore } from '@/stores/orchestrator'
import { useArtifactStore } from '@/stores/artifact'
import { interruptChat } from '@/api/chat'
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

watch(
  () => route.params.conversationId,
  async (id) => {
    const convId = id ? Number(id) : null
    convStore.setActive(convId)
    await chatStore.initConversation(convId)
  },
  { immediate: true }
)

function getEffectiveAgentId() {
  return convStore.activeConversation?.agentId || agentStore.selectedAgentId
}

function handleSendMessage(text) {
  const agentId = getEffectiveAgentId()
  if (!agentId) {
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
}

function handleStopGeneration() {
  chatStore.stopGeneration()
}

async function handleInterrupt() {
  const convId = convStore.activeId
  if (!convId) return
  await interruptChat(convId, { message: '请暂停当前任务' })
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

async function handleRetryTask(taskId, assignmentIds) {
  if (!taskId) return
  await orchStore.retryAssignments(taskId, assignmentIds || [])
}
</script>
```

- [ ] **Step 2: Update template to wire new events and props**

Replace the template (lines 44-61):

```html
<template>
  <div class="chat-view">
    <ConversationSidebar />
    <div class="chat-main">
      <TopBar />
      <ChatArea
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
        @retry-task="handleRetryTask"
      />
    </div>
    <DetailPanel />
  </div>
</template>
```

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/views/ChatView.vue
git commit -m "feat: add card event handlers, interrupt, and @mention parsing in ChatView"
```

---

### Task 4: Add @mention, interrupt button, and prefill to Composer.vue

**Files:**
- Modify: `AIagent_frontend/src/components/chat/Composer.vue`

- [ ] **Step 1: Replace entire script block with @mention logic, new props, and interrupt**

Replace lines 1-29:

```vue
<script setup>
import { ref, watch, computed, nextTick } from 'vue'
import { NButton, NPopover, NList, NListItem, NAvatar } from 'naive-ui'
import { useConversationStore } from '@/stores/conversation'
import { useTextareaAutosize } from '@/composables/useTextareaAutosize'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  isStreaming: { type: Boolean, default: false },
  placeholder: { type: String, default: '输入消息...' },
  prefillText: { type: String, default: '' }
})

const emit = defineEmits(['send', 'stop', 'interrupt'])

const convStore = useConversationStore()
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
})

function getLastAtIndex(str, pos) {
  // Find the @ before cursor position
  for (let i = pos - 1; i >= 0; i--) {
    if (str[i] === '@') {
      const before = str[i - 1]
      if (!before || /\s/.test(before) || before === '') {
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
  text.value = ''
}

function handleInterrupt() {
  emit('interrupt')
}
</script>
```

- [ ] **Step 2: Replace template with mention popover and interrupt button**

Replace lines 31-63:

```html
<template>
  <div class="composer">
    <div class="composer-inner">
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
                <NAvatar :size="28" round>
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
```

- [ ] **Step 3: Update style block — append mention popover styles**

At the end of the `<style scoped>` block, append:

```css
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
```

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/components/chat/Composer.vue
git commit -m "feat: add @mention popover, interrupt button, and prefillText to Composer"
```

---

### Task 5: Add conversation type filter to ConversationSidebar.vue

**Files:**
- Modify: `AIagent_frontend/src/components/chat/ConversationSidebar.vue`

- [ ] **Step 1: Add watch import and segment filter between buttons and search**

In script setup (line 1), replace `import { ref, onMounted } from 'vue'`:

```js
import { ref, watch, onMounted } from 'vue'
```

After the `onMounted` block (lines 16-18), add the watch:

```js
watch(() => convStore.filter, () => {
  convStore.loadList()
})
```

In the template, add the segment between the button row and the search input (after line 103, before line 104):

```html
<n-segment
  v-model:value="convStore.filter"
  :options="[
    { label: '全部', value: 'all' },
    { label: '单聊', value: 'direct' },
    { label: '群聊', value: 'group' }
  ]"
  size="small"
  style="width: 100%"
/>
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/components/chat/ConversationSidebar.vue
git commit -m "feat: add conversation type filter segment to sidebar"
```

---

### Task 6: Update chat store sendMessage to accept extra options

**Files:**
- Modify: `AIagent_frontend/src/stores/chat.js:88`

- [ ] **Step 1: Add options parameter to sendMessage and pass mentionedAgentIds in request body**

Change the `sendMessage` function signature from `async function sendMessage(text, agentId)` to accept an optional options object. Replace lines 88-89:

```js
async function sendMessage(text, agentId, options = {}) {
```

Replace the `sse.connect` call body at lines 166-170:

```js
sse.connect((signal) => streamChat({
  agentId,
  message: text,
  conversationId: conversationId.value || null,
  ...options
}, signal))
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/stores/chat.js
git commit -m "feat: support extra options (mentionedAgentIds) in chatStore.sendMessage"
```

---

### Task 7: Type check and verify

**Files:**
- Affected: all modified files

- [ ] **Step 1: Run Vite build check**

```bash
cd AIagent_frontend && npx vite build --emptyOutDir false 2>&1 | head -40
```

Expected: no compilation errors. The build should succeed.

- [ ] **Step 2: Manual verification (if dev server running)**

- Open a group chat conversation
- Click PlanCard "重试" button → verify `orchestratorStore.retryAssignments` is called (check network tab)
- Find a DiffViewCard message → click "应用" → verify new SSE stream starts
- Find an ArtifactPreviewCard → click "预览" → verify route changes to `/artifacts/:id`
- In Composer, type `@` → verify agent list popup appears (group chat only)
- Type `@fr` → verify list filters, press ↓↑ to navigate, Enter to select
- Send message with `@AgentName` → verify `mentionedAgentIds` in request payload
- Click sidebar type filter segment → verify list filters and reloads
- During streaming → verify "打断" and "停止" buttons both appear

- [ ] **Step 3: Commit any fixes**

If any issues found, fix and commit.
