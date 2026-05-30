# Message Pin UI — Design Spec

Date: 2026-05-23
Status: approved (pending spec review)

## Summary

已实现后端 pin/unpin/list 端点（`MessagePinService` + `ConversationController`），前端 API 函数（`conversations.js`）也已定义但未被调用。实现消息 Pin 的前端 UI：消息气泡 hover 显示 pin 按钮，右侧面板展示已 pin 消息列表，点击可滚动到对应消息。

## Architecture

```
ChatView (Provide scrollToMessage, 事件处理)
  ├── ConversationSidebar
  ├── ChatArea (Inject scrollToMessage, 转发 pin/unpin 事件)
  │     └── MessageBubble (isPinned prop, emit pin/unpin)
  └── DetailPanel (Inject scrollToMessage, 展示 pinned 列表)
```

## Changes

### 1. New dependency — `@vicons/ionicons5`

Icon library for `PushpinOutlined` / `PushpinFilled` components, compatible with Naive UI.

### 2. API layer (`src/api/conversations.js`)

Change `pinMessage` signature from positional params to object param:

```js
// before
export function pinMessage(conversationId, messageId, note)
// after  
export function pinMessage(conversationId, { messageId, note })
```

### 3. Store (`src/stores/conversation.js`)

New state and action:

```js
const pinnedMessages = ref([])

async function loadPinnedMessages(conversationId) {
  const res = await fetchPinnedMessages(conversationId)
  pinnedMessages.value = res || []
}
```

Import `fetchPinnedMessages` from API module. Export `pinnedMessages` and `loadPinnedMessages`.

### 4. MessageBubble (`src/components/chat/MessageBubble.vue`)

- New prop: `isPinned: Boolean` (default false)
- New emits: `pinMessage`, `unpinMessage`
- In `.msg-actions` area, add pin/unpin toggle button using `PushpinOutlined` (unpinned) / `PushpinFilled` (pinned) with `type="primary"`
- Only show pin actions when `!isUser && !isStreaming && !isError`

### 5. ChatArea (`src/components/chat/ChatArea.vue`)

- `defineExpose` a `scrollToMessage(messageId)` method that scrolls `messagesContainer` to the message element
- Compute `pinnedIds` Set from `convStore.pinnedMessages`
- Pass `:is-pinned` to each MessageBubble
- Forward `@pin-message` and `@unpin-message` emits

### 6. ChatView (`src/views/ChatView.vue`)

- `provide('scrollToMessage', fn)` — delegates to ChatArea's exposed `scrollToMessage`
- New handlers:
  - `handlePinMessage(messageId)` → `conversationApi.pinMessage(convId, { messageId, note: '' })` → `convStore.loadPinnedMessages(convId)`
  - `handleUnpinMessage(messageId)` → `conversationApi.unpinMessage(convId, messageId)` → `convStore.loadPinnedMessages(convId)`
- Wire handlers to ChatArea emits

### 7. DetailPanel (`src/components/layout/DetailPanel.vue`)

- `inject('scrollToMessage')` (via provide key)
- New `.pinned-section` area between conversation info and task progress sections
- Conditional render: only when `pinnedMessages.length > 0`
- Each pinned item shows: note (or placeholder text), message preview text, unpin button
- Click on item body calls `scrollToMessage(pin.messageId)`

### 8. ChatArea — scrollToMessage implementation

```js
function scrollToMessage(messageId) {
  const el = document.getElementById(`msg-${messageId}`)
  if (el && messagesContainer.value) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}
```

MessageBubble template root needs `:id="'msg-' + message.id"`.

## Data Flow

```
User clicks Pin → MessageBubble emit('pinMessage', msgId)
  → ChatArea emit('pinMessage', msgId)
    → ChatView handlePinMessage(msgId)
      → API POST /api/v1/conversations/{id}/pins { messageId, note }
      → store.loadPinnedMessages(convId)
        → pinnedMessages 更新 → ChatArea/DatailPanel 响应式刷新

User clicks unpin → same flow, calls API DELETE
```

## Edge Cases

- **空 pinned 列表**: DetailPanel 不渲染 `.pinned-section`
- **消息被删除**: 后端返回的 pin 列表引用不存在的消息时，skip 该条目
- **切换对话**: `watch(conversationId)` 自动重新加载 pinned messages
- **Pin 按钮仅对 AI 消息**: pin 按钮在 `.msg-actions` 中，该区域仅在 `!isUser && !isStreaming && !isError` 时渲染
- **重复 pin**: 后端应返回错误（已 pin），前端无需额外处理

## Non-goals

- 不支持 pin 备注编辑（当前后端 note 字段预留但无编辑 UI）
- 不实现消息右键菜单（pin 按钮放在 hover 操作栏中）
- 不处理群聊 pin 权限控制（依赖后端）
