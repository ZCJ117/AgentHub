# Group Chat — @AgentName Multi-Agent Dispatch Design

**Date:** 2026-05-27
**Status:** Approved
**Topic:** Group chat reimplementation with dynamic CLAUDE.md generation and @AgentName streaming dispatch

## Problem Summary

Current group chat only spawns the Orchestrator's CLI instance. Other selected agents never get invoked. The Orchestrator receives a group context prompt via `buildGroupMemberContextPrompt()` prepended to the user message, but it just replies directly without dispatching tasks to member agents. Each agent lacks its own CLAUDE.md with role-specific behavior instructions.

## Goals

1. **Group creation generates per-agent CLAUDE.md files** — Orchestrator gets agent list + dispatch rules; each member gets its role definition + @name response behavior
2. **Orchestrator uses `@Agent名: 任务` text format** to assign tasks in its streaming output
3. **Backend parses @AgentName lines in real-time** from the orchestrator's stream
4. **Member agents are spawned on-demand** when @mentioned, each with its own CLAUDE.md
5. **All agent responses stream to the frontend** as separate messages with sender attribution

## Design

### 1. CLAUDE.md Generation (Group Creation Time)

When `POST /api/v1/conversations/group` is called, `GroupConversationService.createGroup()` generates CLAUDE.md files:

**Orchestrator CLAUDE.md:**
```
你是一个 Orchestrator 调度者。你的任务是根据用户的输入，为我提供的多个 Agent 分派任务。
你必须使用 "@Agent名称" 的形式来指派任务。
在输出中，你对每个 Agent 的指派必须独占一行，格式严格为：@Agent名: 任务内容
你不需要自己完成具体工作，你只负责分解、分配和协调。

可用 Agent 列表：
· Agent名称: {name1}
  能力: {description1}
· Agent名称: {name2}
  能力: {description2}
```

**Member Agent CLAUDE.md (one per agent):**
```
你是 {agentName}，你的角色是 {description}。
你正在一个多 Agent 群聊中与其他 Agent 协作。
对话中，当看到以 "@{agentName}" 开头的消息时，那是指派给你的任务。
你应当只回复与任务相关的内容，用第一人称的专家口吻。
在回复时，先简要确认你收到了任务，然后给出你的专业意见或产出。
不要模拟其他 Agent 的回复。
```

**Storage:** Temp files at `{tempDir}/claude-md/{conversationId}/{agentName}.md`

**Env var:** `CLAUDE_MD_PATH` passed to `LocalCliProcessManager.spawn()`

### 2. @AgentName Streaming Dispatch

New class: `AgentMentionDispatcher` in `vip.mate.group.service`

```
AgentMentionDispatcher
├── lineBuffer: StringBuilder          // accumulates streaming text
├── agentPattern: Pattern              // regex: ^@(\S+):\s*(.+)$
├── agentNameMap: Map<String, Agent>   // name → agent lookup (built at creation)
├── semaphore: Semaphore(maxParallelTasks)
├── dispatchIfComplete(line: String): boolean
│   // Tests pattern, spawns agent on match, returns true if dispatched
└── spawnAgentAndStream(name, task, convId): void
    // 1. Lookup agent, acquire semaphore
    // 2. Spawn CLI with agent's CLAUDE.md via LocalCliProcessManager
    // 3. Subscribe to agent's Flux<StreamDelta>
    // 4. Broadcast agent_message_start → content_delta* → agent_message_complete
    // 5. Release semaphore
```

**Flow in ChatController.chatStream():**
1. Detect group conversation → wrap orchestrator Flux with interceptor
2. Interceptor appends each text delta to line buffer
3. On newline character → test `agentPattern` against complete line
4. If match → call `dispatcher.dispatchIfComplete(line)`
5. Forward all deltas to SSE unchanged (orchestrator's @ lines display normally)

### 3. SSE Event Extensions

New event types for multi-agent messages:

| Event | Payload | Purpose |
|-------|---------|---------|
| `agent_message_start` | `{ agentName, agentId, taskDescription }` | New agent message bubble begins |
| `content_delta` | `{ delta, agentName? }` | Content for a specific agent (or orchestrator if absent) |
| `agent_message_complete` | `{ agentName, status, error? }` | Agent message finalized |

Backward compatible: existing `content_delta` without `agentName` routes to orchestrator's message.

### 4. Frontend Changes

**stores/chat.js** — the only file needing significant change:
- On `agent_message_start`: create a new streaming message with `senderAgentName`
- On `content_delta`: route to correct message bubble by `agentName` (or orchestrator if absent)
- On `agent_message_complete`: finalize and save agent message

**Existing components that work without changes:**
- `MessageBubble.vue` — already renders `senderAgentName` label
- `AgentSelector.vue` — already has group mode with agent checkboxes
- `ConversationSidebar.vue` — already has group filter and "新建群聊" button
- `ChatArea.vue` — already shows group member count in header

### 5. Error Handling

| Scenario | Handling |
|----------|----------|
| @Agent name not found | Defensive check; log warning; show text as-is |
| Agent CLI spawn fails | SSE `agent_message_complete { status: 'error' }`, red bubble in UI |
| Agent response timeout | Semaphore timeout (180s); mark failed, release slot |
| User stops mid-stream | Cancel orchestrator Flux → cascade cancel all agent sub-Fluxes → kill CLI processes |
| Orchestrator outputs no @ lines | Valid: orchestrator reply shown as normal message |
| Concurrent agent limit hit | Semaphore queueing; later @ lines wait for slots |

### 6. Files to Change

#### Backend (mateclaw-dev)

| File | Change |
|------|--------|
| `GroupConversationService.java` | Add `generateClaudeMd()` methods for orchestrator + members; build agentNameMap |
| **`AgentMentionDispatcher.java`** | **NEW** — Parse @AgentName, spawn agents, multiplex SSE |
| `ChatController.java` | Wire dispatcher into group chat stream path; new SSE event emission |
| `LocalCliProcessManager.java` | Accept `CLAUDE_MD_PATH` env var; support multi-agent session tracking per conversation |
| `ChatStreamTracker.java` | Broadcast `agent_message_start` / `agent_message_complete` event types |

#### Frontend (AIagent_frontend)

| File | Change |
|------|--------|
| `stores/chat.js` | Handle `agent_message_start` / `agent_message_complete`; route `content_delta` by `agentName` |
| `stores/orchestrator.js` | Update to consume new @AgentName events (minor) |
