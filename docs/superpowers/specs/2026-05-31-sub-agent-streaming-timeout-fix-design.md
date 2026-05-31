# Sub-Agent Streaming Timeout Fix — Design Spec

**Date:** 2026-05-31
**Scope:** `AIagent_frontend/src/stores/chat.js` only

## Problem

In group chat mode, sub-agent messages show "生成失败" (Generation failed) after a fixed 90-second timeout (`MAX_STREAM_TIME`), even when the sub-agent CLI process is still actively generating output. The output eventually saves to DB (visible after page refresh), proving the agent completed successfully — the UI just gave up too early.

The streaming pipeline itself is already correct end-to-end:
- Backend `AgentMentionDispatcher` broadcasts token-level `content_delta` events with `agentName`
- Frontend `chat.js` routes them to per-agent bubbles via `agentStreams`

## Root Cause

`chat.js` line 183-190: `MAX_STREAM_TIME` = 90s fires and calls `cleanupAgentStreams('error')`, marking all active sub-agent messages as `error` regardless of whether they are still running.

## Design

### Principle

Sub-agent message status is **entirely backend-driven**. The frontend does not apply local timeouts to sub-agents. Only the SSE connection itself is monitored for liveness (via existing backend heartbeat).

### Changes (all in `chat.js`)

**1. `MAX_STREAM_TIME` — hard kill → renewable check**

Old:
```javascript
const maxTimeId = setTimeout(() => {
  if (isStreaming.value) {
    updateMessage(assistantId, { status: 'completed' })
    cleanupAgentStreams('error')
    isStreaming.value = false
    sse.disconnect()
  }
}, MAX_STREAM_TIME)
```

New:
```javascript
let maxTimeId
function scheduleMaxTimeCheck() {
  maxTimeId = setTimeout(() => {
    if (!isStreaming.value) return
    if (agentStreams.value.size > 0) {
      scheduleMaxTimeCheck()  // sub-agents still running, renew timer
      return
    }
    updateMessage(assistantId, { status: 'completed' })
    isStreaming.value = false
    sse.disconnect()
  }, MAX_STREAM_TIME)
}
scheduleMaxTimeCheck()
```

When `agentStreams` is non-empty, the check renews itself for another 90s instead of force-terminating.

**2. `NO_CONTENT_TIMEOUT` — remove sub-agent cleanup**

On `content_delta` reset path, remove `cleanupAgentStreams('error')` call. When no content arrives and no sub-agents are active, only the orchestrator bubble is marked as error. Sub-agent state continues to be driven by `agent_message_complete` events.

**3. `done` event — `cleanupAgentStreams('completed')` unchanged**

This is a defensive cleanup for the case where `agent_message_complete` events were somehow lost. It uses `'completed'` status, which is correct and harmless.

### State Machine (Group Chat)

```
agent_message_start        → sub-agent bubble status: "streaming" → "生成中..."
agent_message_complete
  { status: "completed" }  → sub-agent bubble status: "completed"
  { status: "error" }      → sub-agent bubble status: "error"     → "生成失败"
done                       → orchestrator bubble → "completed", SSE disconnect
```

No frontend-initiated timeout transitions for sub-agents.

### What Stays the Same

- `NO_CONTENT_TIMEOUT` = 30s still protects against orchestrator silence before any sub-agents spawn
- SSE reconnection (`useSSE.js` exponential backoff) handles actual connection drops
- Backend heartbeat (`ChatStreamTracker`) keeps the SSE connection from idling out

### Verification

1. Start a group chat, send a message that triggers a long-running sub-agent (>90s)
2. Sub-agent bubble should show "生成中..." throughout, with content streaming in real-time
3. When sub-agent completes, bubble should show "completed" state (or "生成失败" only if `agent_message_complete` with `status: "error"`)
4. Refresh page — content should match (no discrepancy)
