# 回复链 + 回应展示 + 会话分页 — 实施计划

> **针对 agentic worker：** 需要子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按照任务项逐步实施此计划。步骤使用 checkbox（`- [ ]`）语法进行跟踪。

**目标：** 新增消息回应标签栏、回复链查看/创建，以及侧边栏会话分页（无限滚动）。

**架构：** 修改 8 个现有文件——无新增文件。后端在 ConversationService 中新增一个方法，修复 ConversationController 中的端点占位符。前端修复 messages API 中的 4 个 URL，为 chat store 新增回应状态 + 回复状态，为 conversation store 新增分页状态，为 4 个组件新增 UI（MessageBubble、Composer、ConversationSidebar、ChatView）。

**技术栈：** Vue 3 + Naive UI (NModal、NTimeLine、NImage、NSpin) + Pinia + Axios（前端），Spring Boot 3 + MyBatis-Plus（后端）

---

### 任务 1：修复前端 messages API 中的 URL 路径

**文件：**
- 修改：`AIagent_frontend/src/api/messages.js:1-25`

- [ ] **第 1 步：更新全部 4 个 API URL**

后端控制器挂载在 `/api/v1/conversations`，因此正确的消息子资源路径以 `/api/v1/conversations/messages/{id}/...` 为前缀。

```js
import apiClient from './client'

export function fetchMessageDetail(id) {
  return apiClient.get(`/api/v1/conversations/messages/${id}`)
}

export function regenerateMessage(id, message) {
  return apiClient.post(`/api/v1/conversations/messages/${id}/regenerate`, { message })
}

export function fetchReactions(id) {
  return apiClient.get(`/api/v1/conversations/messages/${id}/reactions`)
}

export function addReaction(id, reactionType) {
  return apiClient.post(`/api/v1/conversations/messages/${id}/reactions`, { reactionType })
}

export function removeReaction(id, reactionType) {
  return apiClient.delete(`/api/v1/conversations/messages/${id}/reactions/${reactionType}`)
}

export function fetchReplyChain(id) {
  return apiClient.get(`/api/v1/conversations/messages/${id}/reply-chain`)
}
```

- [ ] **第 2 步：验证文件语法**

运行：`cd AIagent_frontend && npx eslint src/api/messages.js --quiet 2>&1 || echo "ESLint 不可用，跳过"`
预期：无错误。（eslint 可能不可用；手动目视检查即可。）

- [ ] **第 3 步：提交**

```bash
git add AIagent_frontend/src/api/messages.js
git commit -m "fix: correct message API URLs to /api/v1/conversations/messages prefix"
```

---

### 任务 2：为消息回应新增 chat store 状态

**文件：**
- 修改：`AIagent_frontend/src/stores/chat.js:1-237`（导入、新增状态、修改 handleReaction、新增方法、新增导出）

- [ ] **第 1 步：在顶部附近新增 import（store 和 messages API）**

在 `import { addReaction, regenerateMessage } from '@/api/messages'`（第 5 行）后面，新增 `removeReaction` 和 `fetchReactions` 到已有导入中，并新增 auth store 导入：

```js
import { addReaction, removeReaction, fetchReactions, regenerateMessage } from '@/api/messages'
import { useAuthStore } from '@/stores/auth'
```

- [ ] **第 2 步：在 `defineStore` 回调函数内部、紧跟 `nextBeforeId`（第 22 行）之后新增 `messageReactionsMap` 声明**

```js
const messageReactionsMap = ref(new Map())
```

- [ ] **第 3 步：新增 `loadReactions` 函数（放在 `addMessageLocal` / `updateMessage` 之后）**

在第 47 行（`updateMessage` 的闭合花括号）之后新增：

```js
async function loadReactions(messageId) {
  try {
    const data = await fetchReactions(messageId)
    const authStore = useAuthStore()
    const myId = authStore.userId
    const list = []
    if (data && typeof data === 'object') {
      for (const [type, users] of Object.entries(data)) {
        const count = Array.isArray(users) ? users.length : 0
        const hasMyReaction = Array.isArray(users)
          ? users.some(u => (u.userId || u.id) === myId)
          : false
        if (count > 0) list.push({ reactionType: type, count, hasMyReaction })
      }
    }
    messageReactionsMap.value.set(messageId, list)
  } catch (err) {
    console.warn('Failed to load reactions:', err)
  }
}
```

- [ ] **第 4 步：新增 `getReactions` 函数**

```js
function getReactions(messageId) {
  return messageReactionsMap.value.get(messageId) || []
}
```

- [ ] **第 5 步：替换已有的 `handleReaction` 函数（第 198-207 行）**

将旧的纯 fire-and-forget 的 `handleReaction` 替换为切换逻辑：

```js
async function handleReaction(messageId, reactionType) {
  const list = messageReactionsMap.value.get(messageId) || []
  const item = list.find(r => r.reactionType === reactionType)
  try {
    if (item?.hasMyReaction) {
      await removeReaction(messageId, reactionType)
      item.count = Math.max(0, item.count - 1)
      item.hasMyReaction = false
    } else {
      await addReaction(messageId, reactionType)
      if (item) {
        item.count += 1
        item.hasMyReaction = true
      } else {
        list.push({ reactionType, count: 1, hasMyReaction: true })
      }
    }
    messageReactionsMap.value.set(messageId, list.filter(r => r.count > 0))
  } catch (err) {
    console.warn('Reaction toggle failed:', err)
  }
}
```

- [ ] **第 6 步：更新 store 的导出对象（第 230-236 行）**

```js
return {
  messages, conversationId, isStreaming, streamError,
  currentTurnId, hasMoreHistory, isEmpty,
  initConversation, loadMoreHistory, sendMessage, stopGeneration,
  handleReaction, handleRegenerate, clearMessages,
  addMessageLocal, updateMessage,
  messageReactionsMap, loadReactions, getReactions
}
```

- [ ] **第 7 步：提交**

```bash
git add AIagent_frontend/src/stores/chat.js
git commit -m "feat: add reactive reactions map, load/toggle/get reactions in chat store"
```

---

### 任务 3：新增回应标签栏并重做 MessageBubble 按钮

**文件：**
- 修改：`AIagent_frontend/src/components/chat/MessageBubble.vue:1-290`（整个文件）

- [ ] **第 1 步：更新导入——新增 `NImage`、`useChatStore`、定义 emoji 映射**

替换 `<script setup>` 代码块（第 1-52 行）：

```vue
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
```

- [ ] **第 2 步：将图片类型替换为 NImage（模板第 88-90 行）**

替换：
```html
      <div v-else-if="message.messageType === 'image'" class="msg-image">
        <img :src="message.content" alt="attachment" loading="lazy" />
      </div>
```
为：
```html
      <div v-else-if="message.messageType === 'image'" class="msg-image">
        <NImage :src="message.content" alt="attachment" style="max-width:320px;border-radius:14px" />
      </div>
```

- [ ] **第 3 步：为文件下载新增点击处理器（模板第 92-95 行）**

替换：
```html
      <div v-else-if="message.messageType === 'file'" class="msg-file">
        <span>📎 {{ message.content }}</span>
        <NButton size="tiny" quaternary>下载</NButton>
      </div>
```
为：
```html
      <div v-else-if="message.messageType === 'file'" class="msg-file">
        <span>📎 {{ message.content }}</span>
        <NButton size="tiny" quaternary @click="window.open(message.content, '_blank', 'noopener')">下载</NButton>
      </div>
```

- [ ] **第 4 步：新增回应标签栏 + 重新设计操作按钮（模板第 125-151 行）**

替换从 `<!-- 操作按钮行 -->` 开始的整个区块（第 128-151 行）为：

```html
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
```

- [ ] **第 5 步：在 `</style>` 结束标签前新增回应标签的 CSS**

在 `</style>` 结束标签前（第 289 行）新增：

```css
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
```

- [ ] **第 6 步：提交**

```bash
git add AIagent_frontend/src/components/chat/MessageBubble.vue
git commit -m "feat: add reaction tag bar, reply/chain buttons, NImage preview, file download"
```

---

### 任务 4：在 ChatArea 中透传 reply 和 show-reply-chain 事件

**文件：**
- 修改：`AIagent_frontend/src/components/chat/ChatArea.vue:18-24`（新增 emits）和 `:72-83`（新增监听器）

- [ ] **第 1 步：新增 `reply` 和 `showReplyChain` emits（第 18-24 行）**

将 `defineEmits` 数组替换为：
```js
const emit = defineEmits([
  'send', 'stop', 'regenerate', 'reaction', 'interrupt',
  'applyDiff', 'rejectDiff',
  'previewArtifact', 'editArtifact', 'deployArtifact', 'downloadArtifact',
  'cancelTask', 'retryTask',
  'pinMessage', 'unpinMessage',
  'reply', 'showReplyChain'
])
```

- [ ] **第 2 步：在 MessageBubble 组件上新增 `@reply` 和 `@show-reply-chain` 监听器**

在 MessageBubble 标签（第 67-84 行）上，在已有监听器之后新增两个：

```html
        @reply="msg => emit('reply', msg)"
        @show-reply-chain="id => emit('showReplyChain', id)"
```

在 `@unpin-message="id => emit('unpinMessage', id)"` 之后，在 `/>` 之前。

- [ ] **第 3 步：提交**

```bash
git add AIagent_frontend/src/components/chat/ChatArea.vue
git commit -m "feat: wire reply and showReplyChain events through ChatArea"
```

---

### 任务 5：新增回复链弹窗，并在 ChatView 中处理回复操作

**文件：**
- 修改：`AIagent_frontend/src/views/ChatView.vue:1-185`（新增脚本状态、模板中的弹窗）

- [ ] **第 1 步：新增导入——`NModal`、`NTimeLine`、`NTimeLineItem`、`fetchReplyChain`、`formatTime`、`renderMarkdown`**

在第 3 行（`import { useChatStore }`）之后新增：
```js
import { NModal, NTimeLine, NTimeLineItem } from 'naive-ui'
import { fetchReplyChain } from '@/api/messages'
import { renderMarkdown } from '@/composables/useMarkdown'
```

已有 `formatTime` 函数吗？用简单的内联即可：

在 `<script setup>` 尾部（第 136 行之后，`</script>` 之前）新增状态和处理函数：

- [ ] **第 2 步：新增回复链状态和处理器**

在脚本尾部（`handleUnpinMessage` 函数之后，`</script>` 之前）新增：

```js
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
  return `${h}:${m}`
}
```

- [ ] **第 3 步：新增 NModal 模板**

在 `</div>`（关闭 `.chat-view` 的 div，第 168 行）之后、`</template>` 之前新增弹窗：

```html
    <NModal v-model:show="showReplyChain" preset="card" title="回复链" style="max-width:560px">
      <NTimeLine>
        <NTimeLineItem
          v-for="item in replyChain"
          :key="item.id"
          :title="(item.senderAgentName || '你') + ' · ' + formatTimestamp(item.createdAt || item.createTime)"
          :color="item.role === 'user' ? '#1a73e8' : '#34a853'"
        >
          <div v-html="renderMarkdown((item.content || '').slice(0, 300))" />
        </NTimeLineItem>
      </NTimeLine>
    </NModal>
```

- [ ] **第 4 步：在 ChatArea 组件上新增 `@reply` 和 `@show-reply-chain` 监听器**

在 ChatArea 标签（第 144-166 行）上，在已有监听器之后新增：

```html
        @reply="handleReply"
        @show-reply-chain="handleShowReplyChain"
```

- [ ] **第 5 步：提交**

```bash
git add AIagent_frontend/src/views/ChatView.vue
git commit -m "feat: add reply chain modal with NTimeLine and reply handler in ChatView"
```

---

### 任务 6：新增 replyTo 状态并在 Composer 中显示回复指示条

**文件：**
- 修改：`AIagent_frontend/src/stores/chat.js`（新增 replyTo 状态）
- 修改：`AIagent_frontend/src/components/chat/Composer.vue`（回复指示条 UI）

- [ ] **第 1 步：在 chat store 中新增 replyTo 状态（紧跟 messageReactionsMap 声明之后）**

在 `const messageReactionsMap = ref(new Map())` 之后新增：

```js
const replyTo = ref(null) // { id, preview, senderName } | null

function setReplyTo(msg) {
  replyTo.value = {
    id: msg.id,
    preview: (msg.content || '').slice(0, 80),
    senderName: msg.senderAgentName || '你'
  }
}

function clearReplyTo() {
  replyTo.value = null
}
```

- [ ] **第 2 步：更新 chat store 的导出对象**

```js
return {
  messages, conversationId, isStreaming, streamError,
  currentTurnId, hasMoreHistory, isEmpty,
  initConversation, loadMoreHistory, sendMessage, stopGeneration,
  handleReaction, handleRegenerate, clearMessages,
  addMessageLocal, updateMessage,
  messageReactionsMap, loadReactions, getReactions,
  replyTo, setReplyTo, clearReplyTo
}
```

- [ ] **第 3 步：在 Composer 中新增回复指示条 UI**

修改 `AIagent_frontend/src/components/chat/Composer.vue`：

首先，在 `<script setup>` 中将 `useChatStore` 新增到导入中（当前 Composer 只导入 conversation store）：

在第 3 行之后新增：
```js
import { useChatStore } from '@/stores/chat'
```

在 `const convStore = useConversationStore()` 之后新增：
```js
const chatStore = useChatStore()
```

现在在 Composer 模板中将回复指示条放在 `<div class="composer-inner">` 内部、`<NPopover>` 之前：

```html
    <div class="composer-inner">
      <div v-if="chatStore.replyTo" class="reply-indicator">
        <span class="reply-indicator-text">正在回复 <strong>{{ chatStore.replyTo.senderName }}</strong>：{{ chatStore.replyTo.preview }}</span>
        <NButton text size="tiny" @click="chatStore.clearReplyTo()">✕ 取消</NButton>
      </div>
      <NPopover ...>
```

- [ ] **第 4 步：在 Composer 的 `<style scoped>` 中新增回复指示条 CSS**

```css
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
```

- [ ] **第 5 步：提交**

```bash
git add AIagent_frontend/src/stores/chat.js AIagent_frontend/src/components/chat/Composer.vue
git commit -m "feat: add replyTo state and reply indicator bar in Composer"
```

---

### 任务 7：在 conversation store 和侧边栏中新增会话分页

**文件：**
- 修改：`AIagent_frontend/src/stores/conversation.js:1-112`
- 修改：`AIagent_frontend/src/components/chat/ConversationSidebar.vue:1-319`

- [ ] **第 1 步：更新 conversation store 导入——新增 `fetchConversationsPage`**

在第 3-5 行，将 `fetchConversations` 替换为 `fetchConversationsPage`，保留 `fetchConversations` 作其他用途。或将导入改为：

```js
import {
  fetchConversations, fetchConversationsPage, fetchConversationDetail, fetchMessages,
  deleteConversation as deleteConvApi,
  toggleConversationPin, toggleConversationArchive,
  updateConversationTitle,
  createGroupConversation,
  fetchPinnedMessages
} from '@/api/conversations'
```

- [ ] **第 2 步：在声明中新增分页状态**

在 `const filter = ref('all')`（第 17 行）之后，`const pinnedMessages` 之前新增：

```js
const currentPage = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
```

- [ ] **第 3 步：修改 `loadList` 以使用分页端点**

将当前 `loadList`（第 60-70 行）替换为：

```js
async function loadList() {
  loading.value = true
  try {
    const params = { page: 1, size: 50 }
    if (filter.value !== 'all') params.conversationType = filter.value
    if (searchKeyword.value) params.keyword = searchKeyword.value
    const data = await fetchConversationsPage(params)
    conversations.value = data?.records || []
    currentPage.value = data?.current ?? 1
    hasMore.value = (data?.current ?? 0) < (data?.pages ?? 0)
  } catch (e) {
    console.warn('Failed to load conversations:', e)
  } finally {
    loading.value = false
  }
}
```

- [ ] **第 4 步：新增 `loadMore` 函数**

在 `loadList` 之后、`setActive` 之前新增：

```js
async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const params = { page: currentPage.value + 1, size: 50 }
    if (filter.value !== 'all') params.conversationType = filter.value
    if (searchKeyword.value) params.keyword = searchKeyword.value
    const data = await fetchConversationsPage(params)
    const records = data?.records || []
    conversations.value.push(...records)
    currentPage.value = data?.current ?? currentPage.value + 1
    hasMore.value = (data?.current ?? 0) < (data?.pages ?? 0)
  } catch (e) {
    console.warn('Failed to load more conversations:', e)
  } finally {
    loadingMore.value = false
  }
}
```

- [ ] **第 5 步：更新 store 的导出对象**

```js
return {
  conversations, activeId, loading, searchKeyword, filter,
  currentPage, hasMore, loadingMore,
  activeConversation, sortedConversations, filteredConversations, unreadTotal,
  loadList, loadMore, setActive, togglePin, toggleArchive, deleteConversation, createGroup,
  pinnedMessages, loadPinnedMessages
}
```

- [ ] **第 6 步：在 ConversationSidebar 中新增滚动处理器**

修改 `ConversationSidebar.vue`：

在 `<script setup>` 中新增滚动处理器（在第 79 行 `formatTime` 函数之后）：

```js
function handleScroll(e) {
  const { scrollHeight, scrollTop, clientHeight } = e.target
  if (scrollHeight - scrollTop - clientHeight < 40) {
    convStore.loadMore()
  }
}
```

- [ ] **第 7 步：在侧边栏列表上新增 `@scroll` 处理器**

将 `<div class="sidebar-list">`（第 133 行）改为：
```html
    <div class="sidebar-list" @scroll="handleScroll">
```

- [ ] **第 8 步：在对话列表底部新增加载指示器**

在 `.sidebar-list` 内部、`</NSpin>` 之前，在空列表 div 之后新增：

```html
        <div v-if="convStore.loadingMore" style="text-align:center;padding:12px">
          <NSpin size="small" />
        </div>
        <div v-else-if="!convStore.hasMore && convStore.filteredConversations.length > 0" style="text-align:center;padding:12px;color:#999;font-size:12px">
          已加载全部
        </div>
```

放在 `</NSpin>` 之前、空列表 `</div>` 之后。

- [ ] **第 9 步：提交**

```bash
git add AIagent_frontend/src/stores/conversation.js AIagent_frontend/src/components/chat/ConversationSidebar.vue
git commit -m "feat: add pagination with infinite scroll to conversation sidebar"
```

---

### 任务 8：实现后端 buildReplyChain 并修复控制器端点

**文件：**
- 修改：`mateclaw-dev/mateclaw-server/src/main/java/vip/mate/workspace/conversation/ConversationService.java`（在文件末尾、第 1387 行的 `}` 之前新增方法）
- 修改：`mateclaw-dev/mateclaw-server/src/main/java/vip/mate/workspace/conversation/controller/ConversationController.java:369-373`

- [ ] **第 1 步：在 ConversationService 末尾（第 1387 行之前）新增 `buildReplyChain` 方法**

在 `log.info("Cleaned attachment files...` 所在行之后、文件最后一个 `}`（第 1387 行的 `cleanAttachmentFiles` 辅助方法的闭合花括号，或第 1388 行类的闭合花括号）之前新增：

```java
/**
 * Build the reply chain for a message by walking replyToId links backward.
 * Capped at 50 depth with cycle detection.
 */
public List<MessageVO> buildReplyChain(Long messageId) {
    if (messageId == null) return List.of();
    List<MessageEntity> chain = new ArrayList<>();
    java.util.Set<Long> visited = new java.util.HashSet<>();
    Long cursor = messageId;
    int maxDepth = 50;
    while (cursor != null && !visited.contains(cursor) && maxDepth-- > 0) {
        visited.add(cursor);
        MessageEntity msg = messageMapper.selectById(cursor);
        if (msg == null) break;
        chain.add(msg);
        cursor = msg.getReplyToId();
    }
    Collections.reverse(chain);
    // Batch-fetch agent names
    java.util.Set<Long> agentIds = chain.stream()
            .map(MessageEntity::getSenderAgentId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    Map<Long, String> nameMap = agentIds.isEmpty()
            ? Map.of()
            : agentMapper.selectBatchIds(agentIds).stream()
                    .collect(Collectors.toMap(AgentEntity::getId, AgentEntity::getName));
    return chain.stream()
            .map(m -> MessageVO.from(m, this.parseMessageParts(m), this.renderMessageContent(m)))
            .peek(vo -> {
                if (vo.getSenderAgentId() != null) {
                    vo.setSenderAgentName(nameMap.get(vo.getSenderAgentId()));
                }
            })
            .toList();
}
```

注意：`java.util.Set`、`java.util.HashSet`、`java.util.Objects` 使用完全限定名以避免与已有导入冲突。若文件已有这些导入，则可以直接使用短名称。检查第 43 行——`List` 和 `Map` 已导入，`Set` 可能未导入。需要在文件顶部新增导入：

```java
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
```

若已存在则跳过。当前导入（第 33-45 行）显示 java.util 下仅有 `ArrayList`、`Collections`、`Comparator`、`List`、`Map`、`stream.Collectors`、`stream.Stream`。必须新增 `HashSet`、`Objects`、`Set` 导入，或在方法体中使用完全限定名。

- [ ] **第 2 步：修复 ConversationController 中 reply-chain 端点**

在 `ConversationController.java` 第 369-373 行，替换占位实现：

```java
@Operation(summary = "获取回复链")
@GetMapping("/messages/{id}/reply-chain")
public R<List<MessageVO>> replyChain(@PathVariable Long id) {
    return R.ok(conversationService.buildReplyChain(id));
}
```

注意：需要将 `MessageVO` 新增到控制器的导入中：
```java
import vip.mate.workspace.conversation.vo.MessageVO;
```

检查是否已导入——第 26 行附近。若已存在则跳过。

- [ ] **第 3 步：编译后端以验证**

运行：`cd mateclaw-dev && ./mvnw compile -q 2>&1 | tail -5`
预期：`BUILD SUCCESS`

- [ ] **第 4 步：提交**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/workspace/conversation/ConversationService.java mateclaw-dev/mateclaw-server/src/main/java/vip/mate/workspace/conversation/controller/ConversationController.java
git commit -m "feat: implement buildReplyChain service method and wire controller endpoint"
```

---

### 任务 9：集成验证及手动冒烟测试

- [ ] **第 1 步：启动后端并验证端点**

```bash
cd mateclaw-dev && ./mvnw spring-boot:run &
# 等待启动
curl -s http://localhost:18088/api/v1/conversations/messages/1/reply-chain | head -50
```
预期：返回 JSON，包含 `"code":"0000"`（消息 ID 1 存在时返回链；若不存在则返回空数组）。

- [ ] **第 2 步：构建前端开发版本并检查控制台错误**

```bash
cd AIagent_frontend && npm run dev &
# 在浏览器中打开 http://localhost:3000
```
在浏览器控制台中检查：
- 加载聊天页面时，reactions 无 404 错误（之前为 404）
- 在 AI 消息上显示 👍 回应按钮

- [ ] **第 3 步：手动测试**

1. 在浏览器中打开应用
2. 导航至对话
3. 点击 AI 消息上的回应标签👍——确认 emoji 高亮
4. 点击 AI 消息上的"回复"——确认 Composer 显示回复指示条
5. 发送一条消息给 Agent 并等待回复
6. 向下滚动侧边栏——确认加载更多对话
7. 在侧边栏中确认"已加载全部"消息（若对话少于 50 条）

---

## 文件清单（所有变更）

| 文件 | 变更 |
|------|------|
| `AIagent_frontend/src/api/messages.js` | 4 个 URL 路径修复（/api/v1/messages → /api/v1/conversations/messages） |
| `AIagent_frontend/src/stores/chat.js` | messageReactionsMap、loadReactions、getReactions、handleReaction 替换、replyTo、setReplyTo、clearReplyTo |
| `AIagent_frontend/src/stores/conversation.js` | 新增分页状态，loadList 使用分页端点，新增 loadMore |
| `AIagent_frontend/src/components/chat/MessageBubble.vue` | 回应标签栏，回复 + 查看回复链按钮，NImage，文件下载 onclick |
| `AIagent_frontend/src/components/chat/ChatArea.vue` | 新增 reply 和 showReplyChain 事件透传 |
| `AIagent_frontend/src/components/chat/Composer.vue` | 回复指示条 UI |
| `AIagent_frontend/src/components/chat/ConversationSidebar.vue` | 滚动处理器，加载中/全部已加载指示器 |
| `AIagent_frontend/src/views/ChatView.vue` | 回复链 NModal + NTimeLine，reply 处理器 |
| `mateclaw-dev/.../ConversationService.java` | 新增 buildReplyChain() 方法 |
| `mateclaw-dev/.../ConversationController.java` | 修复 reply-chain 端点（移除占位符） |
