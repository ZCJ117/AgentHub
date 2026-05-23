# Chat Card Events & @mention Design

Date: 2026-05-23
Status: approved

## Summary

Fix broken event chain for inline message cards (PlanCard, DiffViewCard, ArtifactPreviewCard) and add @mention support to the Composer. Currently card button clicks have no effect because events are emitted but never handled above ChatArea.

## Architecture: Direct Handler Pattern

Follow the existing ChatView pattern — handlers are plain functions in ChatView.vue that call stores/APIs directly. ChatArea.vue is a transparent forwarding layer.

**Event chain:** Inner Card → MessageBubble → ChatArea → ChatView → Store/API

---

## 1. Event Forwarding Chain

### 1.1 MessageBubble.vue (minor fixes)

PlanCard emits `cancel`/`retry` without args today. Pass IDs from `message.props`:

```js
// PlanCard bindings
@cancel="emit('cancelTask', message.props?.taskId)"
@retry="emit('retryTask', message.props?.taskId, message.props?.failedAssignmentIds || [])"
```

Other cards already emit correctly — no changes needed.

### 1.2 ChatArea.vue

Add 8 emits to `defineEmits` and wire in template:

```js
const emit = defineEmits([
  'send', 'stop', 'regenerate', 'reaction',
  'applyDiff', 'rejectDiff',
  'previewArtifact', 'editArtifact', 'deployArtifact', 'downloadArtifact',
  'cancelTask', 'retryTask'
])
```

```html
<MessageBubble
  @apply-diff="id => $emit('applyDiff', id)"
  @reject-diff="id => $emit('rejectDiff', id)"
  @preview-artifact="id => $emit('previewArtifact', id)"
  @edit-artifact="id => $emit('editArtifact', id)"
  @deploy-artifact="id => $emit('deployArtifact', id)"
  @download-artifact="id => $emit('downloadArtifact', id)"
  @cancel-task="taskId => $emit('cancelTask', taskId)"
  @retry-task="(taskId, ids) => $emit('retryTask', taskId, ids)"
/>
```

### 1.3 ChatView.vue

Add 8 handlers (see Section 2) and wire in template:

```html
<ChatArea
  @apply-diff="handleApplyDiff"
  @reject-diff="handleRejectDiff"
  @preview-artifact="handlePreviewArtifact"
  @edit-artifact="handleEditArtifact"
  @deploy-artifact="handleDeployArtifact"
  @download-artifact="handleDownloadArtifact"
  @cancel-task="handleCancelTask"
  @retry-task="handleRetryTask"
/>
```

---

## 2. Handler Implementations

All handlers live in ChatView.vue `<script setup>`.

### handleApplyDiff(messageId)
- Look up message in `chatStore.messages` by id, get `content`
- Call `chatStore.sendMessage("Apply the following diff:\n" + content, agentId)`
- This reuses existing SSE stream setup

### handleRejectDiff(messageId)
- Call `chatStore.updateMessage(messageId, { diffRejected: true })`
- UI flag on message object; no backend call

### handlePreviewArtifact(artifactId)
- `router.push(\`/artifacts/${artifactId}\`)`

### handleEditArtifact(artifactId)
- Set a ref `composerPrefillText.value = \`请修改 @artifact:${artifactId}\``
- Pass as prop through ChatArea → Composer
- Composer watches this prop and sets textarea value on change

### handleDeployArtifact(artifactId)
- `artifactStore.deploy(artifactId, {})`
- Optional: toast notification on result

### handleDownloadArtifact(artifactId)
- `window.open(\`/api/v1/artifacts/${artifactId}/download\`, '_blank')`

### handleCancelTask(taskId)
- `orchestratorStore.cancelTask(taskId)`

### handleRetryTask(taskId, assignmentIds)
- `orchestratorStore.retryAssignments(taskId, assignmentIds)`

---

## 3. Data Model: message.props

Messages carrying plan/artifact cards include a `props` field set by SSE events:

```json
{
  "id": "msg_123",
  "messageType": "plan_card",
  "content": "...",
  "props": {
    "taskId": "task_456",
    "assignmentIds": [1, 2, 3],
    "failedAssignmentIds": [2]
  }
}
```

The chat store preserves `props` through the message lifecycle. PlanCard reads from `message.props` to get IDs for emit.

---

## 4. Composer @mention

### 4.1 Behavior
- Trigger: `@` typed in textarea (only in group conversations)
- Group check: `convStore.activeConversation?.conversationType === 'group'`
- Data source: `convStore.activeConversation.members` — array of `{ agentId, agentName, avatarUrl }`
- Format: plain text `@AgentName ` (no rich text)

### 4.2 UI Components
- NPopover: positioned near textarea, shown above the input
- NList: displays filtered agent list inside popover
- NAvatar: agent avatar in each list item

### 4.3 Interaction
- Type `@` → popover opens with full member list
- Continue typing → filter by text after `@` (case-insensitive)
- ↑↓: navigate list (highlighted index)
- Enter: select highlighted agent, insert `@AgentName ` at cursor, close popover
- Escape: close popover, keep typed text
- Click: select agent, same as Enter
- Backspace after mention word boundary: re-open popover at previous `@`

### 4.4 State Tracking
- `mentionOpen` (ref boolean) — popover visibility
- `mentionQuery` (ref string) — filter text
- `mentionIndex` (ref number) — highlighted item (0-based)
- `mentionStartPos` (ref number) — cursor position at `@` trigger, for text replacement

### 4.5 Send Parsing
In `handleSendMessage`, before sending:
```js
// Extract @mentions from text
const mentionRegex = /@(\S+)/g
const mentionedNames = [...text.matchAll(mentionRegex)].map(m => m[1])
const members = convStore.activeConversation?.members || []
const mentionedAgentIds = members
  .filter(m => mentionedNames.includes(m.agentName))
  .map(m => m.agentId)
```
Append `mentionedAgentIds` to the chat request payload.

### 4.6 Limitations (YAGNI)
- No blue highlight visual (textarea limitation, pure text approach)
- No @everyone / @here special mentions
- No mention notifications (backend concern)

---

## 5. ConversationSidebar Type Filter

### 5.1 UI
Add `n-segment` between the action buttons and search input:

```html
<n-segment
  v-model:value="convStore.filter"
  :options="[
    { label: '全部', value: 'all' },
    { label: '单聊', value: 'direct' },
    { label: '群聊', value: 'group' }
  ]"
  size="small"
/>
```

### 5.2 Store Integration
- `convStore.filter` already exists with default `'all'`
- `filteredConversations` already filters by type
- `loadList()` already sends `conversationType` param
- Add watcher: `watch(() => convStore.filter, () => convStore.loadList())`

No store modifications needed.

---

## 6. Interrupt Button

### 6.1 UI
In Composer, when streaming, show alongside "停止":

```html
<n-button v-if="isStreaming" type="warning" ghost @click="$emit('interrupt')">
  打断
</n-button>
```

### 6.2 Props
Add `isStreaming` prop (separate from `disabled`) to Composer and ChatArea:
- `disabled` → textarea disabled state
- `isStreaming` → controls button visibility

### 6.3 Handler in ChatView
```js
async function handleInterrupt() {
  const convId = convStore.activeId
  if (!convId) return
  await interruptChat(convId, { message: '请暂停当前任务' })
  chatStore.stopGeneration()
}
```

### 6.4 Event Chain
```
Composer @interrupt → ChatArea @interrupt → ChatView handleInterrupt
```

---

## Files Changed

| File | Changes |
|------|---------|
| `src/views/ChatView.vue` | 8 handlers, composerPrefillText ref, handleInterrupt, imports |
| `src/components/chat/ChatArea.vue` | 8 new emits, template forwarding, isStreaming prop |
| `src/components/chat/MessageBubble.vue` | PlanCard emit args from message.props |
| `src/components/chat/Composer.vue` | @mention (NPopover+NList), interrupt button, isStreaming prop, prefillText prop |
| `src/components/chat/ConversationSidebar.vue` | n-segment filter, watch on filter |
| `src/components/chat/PlanCard.vue` | Optional: accept taskId/assignmentIds from message.props |
| `src/stores/chat.js` | No changes needed |
| `src/stores/conversation.js` | No changes needed |
| `src/stores/orchestrator.js` | No changes needed |
| `src/stores/artifact.js` | No changes needed |

---

## Test Validation

- [ ] PlanCard "重试" → orchestratorStore.retryAssignments called with correct IDs
- [ ] DiffViewCard "Apply" → new SSE stream started with diff content
- [ ] ArtifactPreviewCard "预览" → router navigates to /artifacts/:id
- [ ] Composer `@` → agent list popup appears (group chat only)
- [ ] Composer `@` → type filter narrows, Enter selects, Escape closes
- [ ] Send with @mentions → mentionedAgentIds in request payload
- [ ] Sidebar type filter → list filtered, reload triggered
- [ ] Interrupt button → interruptChat API called, SSE disconnected
