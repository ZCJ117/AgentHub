# Direct Chat Topic Input — Design Spec

## Overview

Add a "conversation topic" input to the AgentSelector modal for direct (single-agent) chats. The topic becomes the conversation title, persisted to the database before the first message is sent, so the conversation appears in the sidebar immediately and survives restarts.

## Problem

The current direct-chat creation flow (`ConversationSidebar.vue`) just sets the agent and navigates to `/chat` without creating a database record. The conversation row is only created reactively inside `getOrCreateConversation` when the first SSE message is sent, and its title is always "新对话". This makes it impossible to identify conversations in the sidebar, and there is a gap where the conversation doesn't appear until after the first message completes.

Group chats already have a proper creation API (`POST /api/v1/conversations/group`). Direct chats need the same pattern.

## Solution

### 1. Backend — New API endpoint

**`POST /api/v1/conversations`**

Request:
```json
{
  "agentId": 5,
  "title": "帮我写一个登录页面"
}
```

Response (201):
```json
{
  "code": 200,
  "data": {
    "conversationId": "a1b2c3d4-...",
    "title": "帮我写一个登录页面",
    "agentId": 5,
    "conversationType": "direct"
  }
}
```

Implementation:
- Generate UUID as `conversationId`
- Insert into `mate_conversation` with `conversationType = "direct"`, username from auth, workspaceId from header
- Validate title: 1-100 characters, non-blank

Files to change:
- `ConversationController.java` — add `@PostMapping` method
- `ConversationService.java` — add `createDirectConversation` method
- `api/conversations.js` — add `createDirectConversation(body)` function

### 2. Frontend — AgentSelector topic input

In the AgentSelector modal (direct mode), add an `NInput` above the agent list:

```
NInput v-model:value="topic" placeholder="输入对话主题"
```

`handleCreate` emits `{ agentId, mode: 'direct', topic }` instead of `{ agentId, mode: 'direct' }`.

### 3. Frontend — ConversationSidebar create flow

`handleCreateConversation` direct branch:
1. Call `POST /api/v1/conversations` with `{ agentId, title: topic }`
2. On success, `router.push(/chat/${conversationId})` and `convStore.loadList()`
3. SSE flow is unchanged — `getOrCreateConversation` finds the existing row and reuses it

### 4. SSE flow — no changes

`getOrCreateConversation` already handles the case where a conversation exists (select-then-return pattern). The title set by the creation API is preserved.

## Data Flow

```
User clicks "+" → AgentSelector opens
  → User selects agent + types topic → clicks "开始对话"
  → POST /api/v1/conversations { agentId, title }
  → DB insert, returns conversationId
  → navigate to /chat/{conversationId}
  → sidebar shows conversation with topic as title
  → User sends first message
  → SSE reuses existing conversation row
  → Messages are saved under that conversationId
```

## Files Changed

| File | Change |
|------|--------|
| `ConversationController.java` | Add `POST /` endpoint |
| `ConversationService.java` | Add `createDirectConversation` method |
| `AIagent_frontend/src/api/conversations.js` | Add `createDirectConversation` function |
| `AIagent_frontend/src/components/agent/AgentSelector.vue` | Add topic input + emit topic |
| `AIagent_frontend/src/components/chat/ConversationSidebar.vue` | Call creation API in direct flow |

## Constraints

- Title: 1-100 characters, non-blank
- conversationType: always "direct"
- Must require authentication (JWT) and workspace header
- No changes to SSE streaming logic
- No database schema changes needed
