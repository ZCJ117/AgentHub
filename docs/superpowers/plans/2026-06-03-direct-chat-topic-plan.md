# Direct Chat Topic Input — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add topic input to AgentSelector for direct chats, pre-create conversation with UUID via new API so it persists across restarts.

**Architecture:** New `POST /api/v1/conversations` endpoint creates a conversation row with `conversationType=direct` and a generated UUID before the first message is sent. Frontend AgentSelector gains a topic input field; ConversationSidebar calls the creation API before navigating to chat. SSE flow unchanged — `getOrCreateConversation` finds the pre-existing row.

**Tech Stack:** Java 21 + Spring Boot 3.5 + MyBatis Plus (backend), Vue 3 + Pinia + Naive UI (frontend)

---

## Root Cause of Existing Bug

In `ChatController.java:115`, when `conversationId` is null from the request:
```java
String conversationId = request.getConversationId() != null ? request.getConversationId() : "default";
```

All direct chats without a pre-created conversationId share `conversationId = "default"`, so only ONE row ever exists in `mate_conversation` for all those chats. The new API generates a real UUID before SSE starts.

---

### Task 1: Backend — Add createDirectConversation to ConversationService

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/conversation/ConversationService.java`

- [ ] **Step 1: Add import for UUID**

Add `import java.util.UUID;` to the imports block.

- [ ] **Step 2: Add createDirectConversation method**

After the existing `getOrCreateConversation` method, add:

```java
/**
 * Pre-create a direct-chat conversation before the first message is sent.
 * Generates a UUID so the conversation appears in the sidebar immediately
 * and the SSE flow can reuse it.
 *
 * <p>预创建单聊会话，在发送第一条消息前生成 UUID 写入数据库。
 */
@Transactional
public ConversationEntity createDirectConversation(Long agentId, String title,
                                                    String username, Long workspaceId) {
    if (title == null || title.isBlank() || title.length() > 100) {
        throw new IllegalArgumentException("标题不合法（1-100字符）");
    }
    ConversationEntity conv = new ConversationEntity();
    conv.setConversationId(UUID.randomUUID().toString());
    conv.setAgentId(agentId);
    conv.setTitle(title.trim());
    conv.setUsername(username != null ? username : "anonymous");
    conv.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
    conv.setConversationType("direct");
    conv.setMessageCount(0);
    conv.setLastActiveTime(LocalDateTime.now());
    conversationMapper.insert(conv);
    return conv;
}
```

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/conversation/ConversationService.java
git commit -m "feat: add createDirectConversation to ConversationService"
```

---

### Task 2: Backend — Add POST / endpoint to ConversationController

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/workspace/conversation/controller/ConversationController.java`

- [ ] **Step 1: Add imports for Map and LinkedHashMap**

Add `import java.util.LinkedHashMap;` and `import java.util.Map;` if not already present.

- [ ] **Step 2: Add the POST / endpoint**

Insert after the `@GetMapping` method and before the `@GetMapping("/page")` method:

```java
/**
 * 预创建单聊会话。
 * 在发送第一条消息前创建会话记录，生成 UUID 作为 conversationId，
 * 使会话立即出现在侧边栏并可持久化。
 */
@Operation(summary = "创建单聊会话")
@PostMapping
public R<Map<String, Object>> create(
        Authentication auth,
        @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
        @RequestBody Map<String, Object> body) {
    String username = auth != null ? auth.getName() : "anonymous";
    Long agentId = body.get("agentId") != null ? Long.valueOf(body.get("agentId").toString()) : null;
    String title = body.get("title") != null ? body.get("title").toString().trim() : "";
    if (agentId == null || title.isEmpty()) {
        return R.fail("agentId 和 title 必填");
    }
    if (title.length() > 100) {
        return R.fail("标题不能超过100字符");
    }
    var conv = conversationService.createDirectConversation(agentId, title, username, workspaceId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("conversationId", conv.getConversationId());
    data.put("title", conv.getTitle());
    data.put("agentId", conv.getAgentId());
    data.put("conversationType", conv.getConversationType());
    return R.ok(data);
}
```

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/workspace/conversation/controller/ConversationController.java
git commit -m "feat: add POST /conversations endpoint for direct chat creation"
```

---

### Task 3: Frontend — Add createDirectConversation API function

**Files:**
- Modify: `AIagent_frontend/src/api/conversations.js`

- [ ] **Step 1: Add the API function**

Add after the existing `createGroupConversation` function:

```js
export function createDirectConversation(body) {
  return apiClient.post('/api/v1/conversations', body)
}
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/api/conversations.js
git commit -m "feat: add createDirectConversation API function"
```

---

### Task 4: Frontend — Add topic input to AgentSelector

**Files:**
- Modify: `AIagent_frontend/src/components/agent/AgentSelector.vue`

- [ ] **Step 1: Add topic ref**

In `<script setup>`, after `const groupTitle = ref('')`, add:

```js
const topic = ref('')
```

- [ ] **Step 2: Update handleCreate to include topic and reset it**

Replace the direct mode section of `handleCreate`:

Current:
```js
if (props.mode === 'direct') {
    if (!selectedAgentId.value) return
    console.log('[AgentSelector] create direct chat, agentId:', selectedAgentId.value)
    emit('create', { agentId: selectedAgentId.value, mode: 'direct' })
  }
```

Replace with:
```js
if (props.mode === 'direct') {
    if (!selectedAgentId.value) return
    if (!topic.value.trim()) return
    console.log('[AgentSelector] create direct chat, agentId:', selectedAgentId.value, 'topic:', topic.value)
    emit('create', { agentId: selectedAgentId.value, mode: 'direct', topic: topic.value.trim() })
    topic.value = ''
  }
```

- [ ] **Step 3: Update close method to also reset topic**

In the `close` function, add `topic.value = ''`:

```js
function close() {
  selectedAgentId.value = null
  selectedAgentIds.value = []
  groupTitle.value = ''
  topic.value = ''
  emit('close')
}
```

- [ ] **Step 4: Add topic input to template**

In the direct mode template block, add the NInput between the selector title `h3` and the agent list:

```html
<!-- Direct mode: single agent selection with radio buttons -->
<div v-if="mode === 'direct'" class="direct-form">
  <NInput
    v-model:value="topic"
    placeholder="输入对话主题"
    maxlength="100"
    style="margin-bottom: 12px"
  />
  <div class="agent-list">
    <NRadioGroup v-model:value="selectedAgentId">
```

Note: Also add a closing `</div>` for `direct-form` after the `NRadioGroup`'s closing div. Wrap the agent list section with `class="direct-form"`.

- [ ] **Step 5: Update disabled condition for create button**

Update the disabled condition to require topic in direct mode:

```html
<NButton
  type="primary"
  :disabled="mode === 'direct' ? (!selectedAgentId || !topic.trim()) : selectedAgentIds.length < 2"
  @click="handleCreate"
>
  {{ mode === 'direct' ? '开始对话' : '创建群聊' }}
</NButton>
```

- [ ] **Step 6: Commit**

```bash
git add AIagent_frontend/src/components/agent/AgentSelector.vue
git commit -m "feat: add topic input to AgentSelector for direct chats"
```

---

### Task 5: Frontend — Wire up ConversationSidebar to call creation API

**Files:**
- Modify: `AIagent_frontend/src/components/chat/ConversationSidebar.vue`

- [ ] **Step 1: Import the new API function**

In the `<script setup>` imports, add `createDirectConversation`:

```js
import {
  fetchConversations, fetchConversationsPage, fetchConversationDetail, fetchMessages,
  deleteConversation as deleteConvApi,
  toggleConversationPin, toggleConversationArchive,
  updateConversationTitle,
  createGroupConversation,
  createDirectConversation,
  fetchPinnedMessages
} from '@/api/conversations'
```

Note: This import is in `stores/conversation.js`, not `ConversationSidebar.vue`. Instead, in `ConversationSidebar.vue`, import from the API module directly:

```js
import { createDirectConversation } from '@/api/conversations'
```

- [ ] **Step 2: Replace direct chat creation logic**

In `handleCreateConversation`, replace the direct branch:

Current:
```js
if (config.mode === 'direct') {
    agentStore.selectAgent(config.agentId)
    convStore.setActive(null)
    chatStore.clearMessages()
    router.replace('/chat')
  }
```

Replace with:
```js
if (config.mode === 'direct') {
    try {
      const result = await createDirectConversation({ agentId: config.agentId, title: config.topic })
      if (result?.conversationId) {
        await convStore.loadList()
        convStore.setActive(result.conversationId)
        router.push(`/chat/${result.conversationId}`)
      }
    } catch (err) {
      console.warn('Failed to create direct conversation:', err)
    }
  }
```

- [ ] **Step 3: Remove unused chatStore import (if now unused)**

Check if `chatStore` is still used elsewhere in the component. If only used in the old direct chat flow, remove the import:

```js
import { useChatStore } from '@/stores/chat'
```

and the declaration:
```js
const chatStore = useChatStore()
```

Note: `chatStore` line 13 `const chatStore = useChatStore()` — check usage. It's not used elsewhere in this file currently, so remove both the import and declaration.

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/components/chat/ConversationSidebar.vue
git commit -m "feat: call createDirectConversation API before navigating to chat"
```

---

### Task 6: Verification

- [ ] **Step 1: Start backend and verify API**

```bash
curl -X POST http://localhost:18088/api/v1/conversations \
  -H "Content-Type: application/json" \
  -H "X-Workspace-Id: 1" \
  -d '{"agentId": 1, "title": "测试主题"}'
```

Expected: 200 response with `conversationId` (UUID), `title`, `agentId`, `conversationType: "direct"`.

- [ ] **Step 2: Verify validation**

```bash
curl -X POST http://localhost:18088/api/v1/conversations \
  -H "Content-Type: application/json" \
  -H "X-Workspace-Id: 1" \
  -d '{"agentId": 1, "title": ""}'
```

Expected: error response "agentId 和 title 必填".

- [ ] **Step 3: Frontend manual test**

1. Start frontend dev server
2. Click "+" → "新建单聊"
3. Select an agent, type a topic, click "开始对话"
4. Verify: sidebar immediately shows the new conversation with the topic as title
5. Send a message, verify SSE streaming works
6. Refresh browser, verify conversation persists in sidebar

- [ ] **Step 4: Restart test**

1. Stop and restart backend
2. Refresh frontend
3. Verify the conversation with the custom topic title still appears in sidebar
4. Click into it, verify message history loads correctly
