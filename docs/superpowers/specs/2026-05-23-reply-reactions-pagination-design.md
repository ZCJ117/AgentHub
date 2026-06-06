# 回复链 + 回应展示 + 会话分页 — 设计文档

**日期**: 2026-05-23  
**状态**: 已确认

---

## 概述

在现有 IM 聊天系统上新增三项能力：

1. **消息回应系统** — 对任意消息添加表情回应（👍/👎/🔄/✅），可切换，带计数展示
2. **回复链** — 沿 `reply_to_id` 向后遍历展示完整对话脉络（时间线弹窗），同时在 Composer 中实现回复操作
3. **会话列表分页** — 侧边栏会话列表从全量加载改为无限滚动分页

附带两项细节修复：文件下载操作、图片点击放大。

---

## 1. 消息回应系统

### 1.1 URL 路由修复

**文件**：`AIagent_frontend/src/api/messages.js`

后端控制器挂载在 `/api/v1/conversations`，所有消息相关端点路径以 `/api/v1/conversations/messages/{id}/...` 为前缀。当前前端调用 `/api/v1/messages/{id}/...` 会导致 404。

修正 4 个 API 函数：

| 函数 | 当前路径 | 修正路径 |
|------|---------|---------|
| `fetchReactions(id)` | `/api/v1/messages/${id}/reactions` | `/api/v1/conversations/messages/${id}/reactions` |
| `addReaction(id, type)` | `/api/v1/messages/${id}/reactions` | `/api/v1/conversations/messages/${id}/reactions` |
| `removeReaction(id, type)` | `/api/v1/messages/${id}/reactions/${type}` | `/api/v1/conversations/messages/${id}/reactions/${type}` |
| `fetchReplyChain(id)` | `/api/v1/messages/${id}/reply-chain` | `/api/v1/conversations/messages/${id}/reply-chain` |

### 1.2 Store 状态管理

**文件**：`AIagent_frontend/src/stores/chat.js`

新增 `messageReactionsMap` — 一个 `ref(new Map())`，key 为 `messageId`，value 为数组 `[{ reactionType, count, hasMyReaction }]`。

新增方法：

- **`loadReactions(messageId)`**：调用 `messagesApi.fetchReactions(id)` → 后端返回格式 `{ "like": [{userId, username, createdAt}, ...], ... }` → 转换为前端格式：`count` = 数组长度，`hasMyReaction` = 当前用户 ID 是否存在于数组中 → 写入 `messageReactionsMap`
- **`handleReaction(messageId, reactionType)`**：从 map 中查找该项 → 若 `hasMyReaction` 为 true 则调用 `removeReaction`，将 `count` 减 1（最低为 0），将 `hasMyReaction` 设为 false → 否则调用 `addReaction`，将 `count` 加 1，将 `hasMyReaction` 设为 true → 过滤掉 `count === 0` 的条目
- **`getReactions(messageId)`**：返回 `messageReactionsMap.value.get(messageId) || []`

导出新增的 `messageReactionsMap`、`handleReaction`、`loadReactions`、`getReactions`。

获取当前用户 ID：`authStore.user?.id`（从 auth store 读取）。

### 1.3 UI — 回应标签栏

**文件**：`AIagent_frontend/src/components/chat/MessageBubble.vue`

移除现有静态 👍 按钮。在消息气泡底部新增独立回应标签行（方案 A 布局）：

```html
<div v-if="reactions.length > 0" class="msg-reactions">
  <span
    v-for="r in reactions"
    :key="r.reactionType"
    class="reaction-tag"
    :class="{ active: r.hasMyReaction }"
    @click="emit('reaction', props.message.id, r.reactionType)"
  >{{ reactionEmoji(r.reactionType) }} {{ r.count }}</span>
</div>
```

**Emoji 映射**：
```js
const EMOJI_MAP = { like: '👍', dislike: '👎', regenerate: '🔄', apply_diff: '✅' }
```

**可见性规则**：
- `like` / `dislike` — 所有消息均显示
- `regenerate` / `apply_diff` — 仅对 `role === 'assistant'` 的消息显示

**样式**：`<style scoped>`，圆角 8px 小标签，padding 4px 10px，active 态蓝色边框 (`var(--primary-color)`) + 浅蓝底色，hover 变色过渡。

**生命周期**：
- `onMounted`：对非用户消息调用 `chatStore.loadReactions(props.message.id)`

### 1.4 视图关联

**文件**：`AIagent_frontend/src/views/ChatView.vue`

已有 `@reaction` 监听。无需修改。

---

## 2. 回复链

### 2.1 后端实现

**文件**：`mateclaw-dev/mateclaw-server/src/main/java/vip/mate/workspace/conversation/ConversationService.java`

新增方法：

```java
public List<MessageVO> buildReplyChain(Long messageId) {
    if (messageId == null) return List.of();
    List<MessageEntity> chain = new ArrayList<>();
    Set<Long> visited = new HashSet<>();
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
    // Batch-fetch agent names (reuse existing agentMapper pattern)
    Set<Long> agentIds = chain.stream()
        .map(MessageEntity::getSenderAgentId)
        .filter(Objects::nonNull)
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

**文件**：`mateclaw-dev/mateclaw-server/src/main/java/vip/mate/workspace/conversation/controller/ConversationController.java`

替换 `GET /messages/{id}/reply-chain` 占位符（当前返回 `Collections.emptyList()`），改为调用 `conversationService.buildReplyChain(id)`。

### 2.2 回复操作 — Composer

**文件**：`AIagent_frontend/src/stores/chat.js`

新增：
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

导出 `replyTo`、`setReplyTo`、`clearReplyTo`。发送消息时将 `replyTo.id` 作为 `replyToId` 参数传入。

**文件**：`AIagent_frontend/src/components/chat/Composer.vue`

在 textarea 上方显示回复指示条（当 `replyTo` 非 null 时）：

```html
<div v-if="chatStore.replyTo" class="reply-indicator">
  <span>正在回复 <strong>{{ chatStore.replyTo.senderName }}</strong>：{{ chatStore.replyTo.preview }}</span>
  <NButton text @click="chatStore.clearReplyTo()">✕ 取消</NButton>
</div>
```

### 2.3 回复操作 — MessageBubble 按钮

**文件**：`AIagent_frontend/src/components/chat/MessageBubble.vue`

在操作按钮行新增：
- "回复"按钮（所有消息均有）：`emit('reply', props.message)` 
- "🔗 查看回复链"（仅当 `props.message.replyToId` 存在时显示）：`emit('showReplyChain', props.message.id)`

### 2.4 回复链弹窗

**文件**：`AIagent_frontend/src/views/ChatView.vue`

新增：
```js
const replyChain = ref([])
const showReplyChain = ref(false)

async function handleShowReplyChain(messageId) {
  const data = await messagesApi.fetchReplyChain(messageId)
  replyChain.value = Array.isArray(data) ? data : []
  showReplyChain.value = true
}

function handleReply(msg) {
  chatStore.setReplyTo(msg)
}
```

弹窗（方案 A — NTimeLine）：

```html
<NModal v-model:show="showReplyChain" preset="card" title="回复链" style="max-width:560px">
  <NTimeLine>
    <NTimeLineItem v-for="item in replyChain" :key="item.id"
      :title="(item.senderAgentName || '你') + ' · ' + formatTime(item.createdAt)"
      :color="item.role === 'user' ? '#1a73e8' : '#34a853'">
      <div v-html="renderMarkdown((item.content || '').slice(0, 300))" />
    </NTimeLineItem>
  </NTimeLine>
</NModal>
```

事件流：
```
MessageBubble @reply → ChatView.handleReply(msg) → chatStore.setReplyTo(msg)
MessageBubble @show-reply-chain → ChatView.handleShowReplyChain(id) → fetchReplyChain → NModal
```

---

## 3. 会话列表分页

### 3.1 Store

**文件**：`AIagent_frontend/src/stores/conversation.js`

修改 `loadList()`：将 `fetchConversations()` 替换为 `fetchConversationsPage({ page: 1, size: 50 })`，将 `data.records` 赋值给 `conversations`。

新增：
```js
const currentPage = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const data = await conversationsApi.fetchConversationsPage({
      page: currentPage.value + 1,
      size: 50,
      keyword: searchKeyword.value || undefined,
      conversationType: filter.value !== 'all' ? filter.value : undefined
    })
    const records = data?.records || []
    conversations.value.push(...records)
    currentPage.value = data?.current ?? currentPage.value + 1
    hasMore.value = (data?.current ?? 0) < (data?.pages ?? 0)
  } catch (e) {
    // 静默降级
  } finally {
    loadingMore.value = false
  }
}
```

`loadList()` 成功后设置 `hasMore.value = (data?.current ?? 0) < (data?.pages ?? 0)`。

导出 `hasMore`、`loadingMore`、`loadMore`。

### 3.2 UI

**文件**：`AIagent_frontend/src/components/chat/ConversationSidebar.vue`

在 `.sidebar-list` 添加 `@scroll="handleScroll"`：

```js
function handleScroll(e) {
  const { scrollHeight, scrollTop, clientHeight } = e.target
  if (scrollHeight - scrollTop - clientHeight < 40) {
    convStore.loadMore()
  }
}
```

列表底部：
```html
<div v-if="convStore.loadingMore" style="text-align:center;padding:12px">
  <NSpin size="small" />
</div>
<div v-else-if="!convStore.hasMore && convStore.conversations.length > 0" style="text-align:center;padding:12px;color:var(--text-tertiary);font-size:12px">
  已加载全部
</div>
```

---

## 4. 细节修复

### 4.1 文件下载

**文件**：`AIagent_frontend/src/components/chat/MessageBubble.vue`

当 `messageType === 'file'` 时，文件链接点击处理为 `window.open(url, '_blank', 'noopener')`，触发浏览器下载。

### 4.2 图片放大

**文件**：`AIagent_frontend/src/composables/useMarkdown.js`

在 `marked` 的自定义渲染器中覆盖 image 渲染，使 `<img>` 输出为带 `onclick` 的包装结构，点击时打开图片 URL 用于预览（Naive UI 预览通过新建 `<img>` 标签在新窗口查看，或利用现有 `NImage` 组件替代原始 img 的渲染结果）。

**文件**：`AIagent_frontend/src/components/chat/MessageBubble.vue`

将消息内容中的 `<img>` 标签替换为 `<NImage :src="url" style="max-width:320px;border-radius:14px" />`。由于 Markdown 渲染输出 HTML 字符串，通过自定义 marked renderer 在渲染阶段直接生成包含 `data-image-src` 属性的占位标记，然后在组件内解析并替换为 NImage 组件。若该方案过于复杂，备选方案为保持 `<img>` 标签并包裹 `<a>` 链接在新标签页中打开原图。

---

## 5. 受影响文件清单

| 文件 | 变更类型 |
|------|---------|
| `AIagent_frontend/src/api/messages.js` | 修改（4 个 URL 路径） |
| `AIagent_frontend/src/stores/chat.js` | 修改（新增 reactions 状态 + replyTo 状态） |
| `AIagent_frontend/src/stores/conversation.js` | 修改（新增分页状态 + loadMore） |
| `AIagent_frontend/src/components/chat/MessageBubble.vue` | 修改（回应标签栏、回复按钮、查看回复链按钮、图片/文件修复） |
| `AIagent_frontend/src/components/chat/Composer.vue` | 修改（新增回复指示条） |
| `AIagent_frontend/src/components/chat/ConversationSidebar.vue` | 修改（滚动处理器、加载指示器） |
| `AIagent_frontend/src/views/ChatView.vue` | 修改（回复链弹窗、回复处理） |
| `mateclaw-dev/.../ConversationService.java` | 修改（新增 buildReplyChain） |
| `mateclaw-dev/.../ConversationController.java` | 修改（修复 reply-chain 端点） |

## 6. 约定

- 全部 `<style scoped>`，不硬编码颜色——使用 CSS 变量
- API 调用 try/catch 静默降级，不 alert
- 不新建 composable 或 store，仅扩展现有文件
- 事件 emit 使用 camelCase，监听使用 kebab-case
