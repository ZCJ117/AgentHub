# Message Pin UI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement message pin (钉选) UI — pin button on message hover, pinned messages list in DetailPanel, click-to-scroll.

**Architecture:** ChatView provides `scrollToMessage` via Vue inject/provide; MessageBubble emits pin/unpin events up through ChatArea to ChatView handlers; DetailPanel reads pinned messages from conversation store and calls injected scrollToMessage on click.

**Tech Stack:** Vue 3 (Composition API), Pinia, Naive UI 2.x, @vicons/ionicons5, Axios

---

### Task 1: Install @vicons/ionicons5

**Files:**
- Modify: `AIagent_frontend/package.json`

- [ ] **Step 1: Install dependency**

Run:
```bash
cd AIagent_frontend && npm install @vicons/ionicons5
```

Expected: package.json updated with `@vicons/ionicons5` in dependencies.

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/package.json AIagent_frontend/package-lock.json
git commit -m "chore: add @vicons/ionicons5 for pin icons"
```

---

### Task 2: Update pinMessage API signature

**Files:**
- Modify: `AIagent_frontend/src/api/conversations.js:43-45`

- [ ] **Step 1: Change pinMessage to accept object parameter**

Replace the existing `pinMessage` function:

```js
export function pinMessage(conversationId, { messageId, note }) {
  return apiClient.post(`/api/v1/conversations/${conversationId}/pins`, { messageId, note })
}
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/api/conversations.js
git commit -m "refactor: change pinMessage API signature to accept object param"
```

---

### Task 3: Add pinnedMessages to conversation store

**Files:**
- Modify: `AIagent_frontend/src/stores/conversation.js`

- [ ] **Step 1: Import fetchPinnedMessages**

Add to the existing import from `@/api/conversations`:

```js
import {
  fetchConversations, fetchConversationDetail, fetchMessages,
  deleteConversation as deleteConvApi,
  toggleConversationPin, toggleConversationArchive,
  updateConversationTitle,
  createGroupConversation,
  fetchPinnedMessages
} from '@/api/conversations'
```

- [ ] **Step 2: Add state and action**

Add after the `searchKeyword` / `filter` declarations (before `activeConversation` computed):

```js
const pinnedMessages = ref([])

async function loadPinnedMessages(conversationId) {
  const res = await fetchPinnedMessages(conversationId)
  pinnedMessages.value = res || []
}
```

- [ ] **Step 3: Export pinnedMessages and loadPinnedMessages**

Add to the return object:

```js
return {
  conversations, activeId, loading, searchKeyword, filter,
  activeConversation, sortedConversations, filteredConversations, unreadTotal,
  loadList, setActive, togglePin, toggleArchive, deleteConversation, createGroup,
  pinnedMessages, loadPinnedMessages
}
```

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/stores/conversation.js
git commit -m "feat: add pinnedMessages state and loadPinnedMessages to conversation store"
```

---

### Task 4: Add isPinned prop and pin buttons to MessageBubble

**Files:**
- Modify: `AIagent_frontend/src/components/chat/MessageBubble.vue`

- [ ] **Step 1: Update imports**

Add `NIcon` to the Naive UI import:

```js
import { NAvatar, NButton, NCode, NTag, NIcon } from 'naive-ui'
```

Add icon imports:

```js
import { PushpinOutlined, PushpinFilled } from '@vicons/ionicons5'
```

- [ ] **Step 2: Add isPinned prop**

Modify `defineProps`:

```js
const props = defineProps({
  message: { type: Object, required: true },
  isPinned: { type: Boolean, default: false }
})
```

- [ ] **Step 3: Add pinMessage and unpinMessage emits**

Add to `defineEmits` array:

```js
const emit = defineEmits([
  'regenerate', 'reaction',
  'cancelTask', 'retryTask',
  'applyDiff', 'rejectDiff',
  'previewArtifact', 'editArtifact', 'deployArtifact', 'downloadArtifact',
  'pinMessage', 'unpinMessage'
])
```

- [ ] **Step 4: Add pin buttons in template**

Insert inside the `.msg-actions` div (currently at line 125-129), after the reaction button:

```html
<div v-if="!isUser && !isStreaming && !isError" class="msg-actions">
  <NButton size="tiny" quaternary @click="navigator.clipboard?.writeText(message.content)">复制</NButton>
  <NButton size="tiny" quaternary @click="emit('regenerate', message.id)">重新生成</NButton>
  <NButton size="tiny" quaternary @click="emit('reaction', message.id, 'like')">👍</NButton>
  <NButton
    v-if="!isPinned"
    size="tiny"
    quaternary
    @click.stop="emit('pinMessage', message.id)"
    title="钉选消息"
  >
    <NIcon><PushpinOutlined /></NIcon>
  </NButton>
  <NButton
    v-else
    size="tiny"
    quaternary
    type="primary"
    @click.stop="emit('unpinMessage', message.id)"
    title="取消钉选"
  >
    <NIcon><PushpinFilled /></NIcon>
  </NButton>
</div>
```

- [ ] **Step 5: Add message id to template root element**

Change the root `<div class="message-row">` to:

```html
<div class="message-row" :class="{ 'is-user': isUser }" :id="'msg-' + message.id">
```

- [ ] **Step 6: Commit**

```bash
git add AIagent_frontend/src/components/chat/MessageBubble.vue
git commit -m "feat: add isPinned prop and pin/unpin buttons to MessageBubble"
```

---

### Task 5: Update ChatArea — scrollToMessage, isPinned forwarding, pin event forwarding

**Files:**
- Modify: `AIagent_frontend/src/components/chat/ChatArea.vue`

- [ ] **Step 1: Import conversation store and add computed**

Replace the `<script setup>` block entirely:

```js
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
  'pinMessage', 'unpinMessage'
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
```

- [ ] **Step 2: Update template — pass isPinned, forward pin events**

Replace the MessageBubble usage in template:

```html
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
/>
```

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/components/chat/ChatArea.vue
git commit -m "feat: add scrollToMessage, isPinned forwarding, and pin event forwarding to ChatArea"
```

---

### Task 6: Update ChatView — provide scrollToMessage, pin/unpin handlers

**Files:**
- Modify: `AIagent_frontend/src/views/ChatView.vue`

- [ ] **Step 1: Add imports**

Add `provide`, `ref` to vue import:

```js
import { ref, watch, provide } from 'vue'
```

Add API imports:

```js
import { pinMessage, unpinMessage } from '@/api/conversations'
```

- [ ] **Step 2: Add ChatArea template ref and provide scrollToMessage**

Add after `composerPrefillText` declaration:

```js
const chatAreaRef = ref(null)

provide('scrollToMessage', (messageId) => {
  chatAreaRef.value?.scrollToMessage(messageId)
})
```

- [ ] **Step 3: Add pin/unpin event handlers**

Add before the closing `</script>` tag (before line 114):

```js
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
```

- [ ] **Step 4: Add loadPinnedMessages call to conversation watch**

Update the existing `watch` on `route.params.conversationId`:

```js
watch(
  () => route.params.conversationId,
  async (id) => {
    const convId = id ? Number(id) : null
    convStore.setActive(convId)
    await chatStore.initConversation(convId)
    if (convId) {
      await convStore.loadPinnedMessages(convId)
    }
  },
  { immediate: true }
)
```

- [ ] **Step 5: Wire pin/unpin handlers and add ref to ChatArea in template**

Update ChatArea in template:

```html
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
  @retry-task="handleRetryTask"
  @pin-message="handlePinMessage"
  @unpin-message="handleUnpinMessage"
/>
```

- [ ] **Step 6: Commit**

```bash
git add AIagent_frontend/src/views/ChatView.vue
git commit -m "feat: add pin/unpin handlers and scrollToMessage provide in ChatView"
```

---

### Task 7: Add Pinned Messages section to DetailPanel

**Files:**
- Modify: `AIagent_frontend/src/components/layout/DetailPanel.vue`

- [ ] **Step 1: Add inject import**

Add `inject` to the vue import:

```js
import { computed, inject } from 'vue'
```

- [ ] **Step 2: Inject scrollToMessage and get pinnedMessages from store**

Add after `const orchStore = useOrchestratorStore()`:

```js
const scrollToMessage = inject('scrollToMessage', null)
```

- [ ] **Step 3: Add pinnedMessages computed**

Add after `const conversation = computed(...)`:

```js
const pinnedMessages = computed(() => convStore.pinnedMessages || [])
```

- [ ] **Step 4: Add emit for unpinMessage**

Add `defineEmits`:

```js
const emit = defineEmits(['unpinMessage'])
```

Wait — DetailPanel currently has no `defineEmits`. Need to add it. Update after the imports section:

```js
const emit = defineEmits(['unpinMessage'])
```

- [ ] **Step 5: Add Pinned Messages section in template**

Insert between the conversation info section and the orchestrator task section:

```html
<div class="panel-section" v-if="pinnedMessages.length > 0">
  <h4>钉选消息 ({{ pinnedMessages.length }})</h4>
  <div
    v-for="pin in pinnedMessages"
    :key="pin.id"
    class="pinned-item"
    @click="scrollToMessage?.(pin.messageId)"
  >
    <p class="pin-note">{{ pin.note || '(无备注)' }}</p>
    <p class="pin-preview">{{ pin.messagePreview || '' }}</p>
    <NButton
      size="tiny"
      text
      type="error"
      @click.stop="emit('unpinMessage', pin.messageId)"
    >
      取消
    </NButton>
  </div>
</div>
```

Place this right after the conversation info `.panel-section` closing `</div>` (after line 40) and before the orchestrator task section.

- [ ] **Step 6: Add styles**

Add to the `<style scoped>` block:

```css
.pinned-item {
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #FFFFFF;
  border-radius: 12px;
  cursor: pointer;
  transition: box-shadow 0.15s;
}

.pinned-item:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.pin-note {
  font-size: 13px;
  font-weight: 500;
  color: #1D1D1F;
  margin: 0 0 4px 0;
}

.pin-preview {
  font-size: 12px;
  color: #999;
  margin: 0 0 8px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 240px;
}
```

- [ ] **Step 7: Commit**

```bash
git add AIagent_frontend/src/components/layout/DetailPanel.vue
git commit -m "feat: add Pinned Messages section to DetailPanel with scroll-to and unpin"
```

---

### Task 8: Wire DetailPanel unpin to ChatView

**Files:**
- Modify: `AIagent_frontend/src/views/ChatView.vue`

- [ ] **Step 1: Add @unpin-message handler on DetailPanel**

Update the DetailPanel in ChatView template:

```html
<DetailPanel @unpin-message="handleUnpinMessage" />
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/views/ChatView.vue
git commit -m "feat: wire DetailPanel unpin event to ChatView"
```

---

### Verification Checklist

After all tasks complete, verify manually:

- [ ] Hover over an AI message → pin button (outlined pushpin icon) appears in the actions bar
- [ ] Click pin button → POST `/api/v1/conversations/{id}/pins` is called, icon changes to filled primary style
- [ ] DetailPanel shows "钉选消息" section with the pinned message
- [ ] Click a pinned message in DetailPanel → chat scrolls to that message
- [ ] Click "取消" on a pinned message in DetailPanel → DELETE is called, item removed from list
- [ ] Switch conversations → pinned messages reload for the new conversation
