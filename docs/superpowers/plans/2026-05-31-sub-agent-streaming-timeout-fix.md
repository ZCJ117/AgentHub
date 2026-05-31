# Sub-Agent Streaming Timeout Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix group chat sub-agent messages incorrectly showing "生成失败" when the agent is still running, by making `MAX_STREAM_TIME` renewable and removing `cleanupAgentStreams('error')` from timeout paths.

**Architecture:** Single-file frontend change in `chat.js`. Replace the hard 90s `MAX_STREAM_TIME` with a `scheduleMaxTimeCheck()` function that renews itself when `agentStreams` is non-empty. Remove `cleanupAgentStreams('error')` calls from timeout code paths so sub-agent status is driven entirely by backend `agent_message_complete` events.

**Tech Stack:** Vue 3 + Pinia (Composition API)

**Spec:** `docs/superpowers/specs/2026-05-31-sub-agent-streaming-timeout-fix-design.md`

---

### Task 1: Make MAX_STREAM_TIME renewable when sub-agents are active

**Files:**
- Modify: `AIagent_frontend/src/stores/chat.js:183-190`

- [ ] **Step 1: Replace hard MAX_STREAM_TIME with renewable check function**

Replace lines 183-190:

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

With:

```javascript
    let maxTimeId
    function scheduleMaxTimeCheck() {
      maxTimeId = setTimeout(() => {
        if (!isStreaming.value) return
        if (agentStreams.value.size > 0) {
          scheduleMaxTimeCheck()
          return
        }
        updateMessage(assistantId, { status: 'completed' })
        isStreaming.value = false
        sse.disconnect()
      }, MAX_STREAM_TIME)
    }
    scheduleMaxTimeCheck()
```

- [ ] **Step 2: Update the `done` event handler to clear the renewable timeout properly**

In the `done` event handler (line 306), `clearTimeout(maxTimeId)` still works because `maxTimeId` now holds the latest timeout ID set by `scheduleMaxTimeCheck()`.

The existing code at line 305-306:
```javascript
      clearTimeout(contentTimeoutId)
      clearTimeout(maxTimeId)
```

Already works correctly with the new `let maxTimeId` declaration. No change needed here.

- [ ] **Step 3: Verify `clearTimeout` calls in `error` and `message_complete` handlers still work**

- `error` handler (line 356): `clearTimeout(maxTimeId)` — works with `let maxTimeId`
- `message_complete` fallback (line 379): `clearTimeout(maxTimeId)` — works with `let maxTimeId`

No changes needed in either handler.

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/stores/chat.js
git commit -m "fix: make MAX_STREAM_TIME renewable when sub-agents are active"
```

---

### Task 2: Remove cleanupAgentStreams('error') from NO_CONTENT_TIMEOUT paths

**Files:**
- Modify: `AIagent_frontend/src/stores/chat.js:173-204`

- [ ] **Step 1: Remove `cleanupAgentStreams('error')` from initial timeout (lines 173-181)**

Replace:
```javascript
    let contentTimeoutId = setTimeout(() => {
      if (!contentReceived && isStreaming.value && agentStreams.value.size === 0) {
        streamError.value = 'Agent 响应超时，请重试'
        updateMessage(assistantId, { status: 'error' })
        cleanupAgentStreams('error')
        isStreaming.value = false
        sse.disconnect()
      }
    }, NO_CONTENT_TIMEOUT)
```

With:
```javascript
    let contentTimeoutId = setTimeout(() => {
      if (!contentReceived && isStreaming.value && agentStreams.value.size === 0) {
        streamError.value = 'Agent 响应超时，请重试'
        updateMessage(assistantId, { status: 'error' })
        isStreaming.value = false
        sse.disconnect()
      }
    }, NO_CONTENT_TIMEOUT)
```

Only change: remove `cleanupAgentStreams('error')` line.

- [ ] **Step 2: Remove `cleanupAgentStreams('error')` from content_delta timeout reset (lines 196-203)**

Replace:
```javascript
      contentTimeoutId = setTimeout(() => {
        if (isStreaming.value && agentStreams.value.size === 0) {
          streamError.value = 'Agent 响应超时，请重试'
          updateMessage(assistantId, { status: 'error' })
          cleanupAgentStreams('error')
          isStreaming.value = false
          sse.disconnect()
        }
      }, NO_CONTENT_TIMEOUT)
```

With:
```javascript
      contentTimeoutId = setTimeout(() => {
        if (isStreaming.value && agentStreams.value.size === 0) {
          streamError.value = 'Agent 响应超时，请重试'
          updateMessage(assistantId, { status: 'error' })
          isStreaming.value = false
          sse.disconnect()
        }
      }, NO_CONTENT_TIMEOUT)
```

Only change: remove `cleanupAgentStreams('error')` line.

- [ ] **Step 3: Verify `cleanupAgentStreams` is only called with `'completed'` status**

Grep the file for `cleanupAgentStreams`. After changes, the only call site should be in the `done` event handler (line 323):

```javascript
      cleanupAgentStreams('completed')
```

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/stores/chat.js
git commit -m "fix: remove cleanupAgentStreams('error') from timeout paths"
```

---

### Task 3: Manual verification

- [ ] **Step 1: Start frontend dev server**

```bash
cd AIagent_frontend && npm run dev
```

- [ ] **Step 2: Verify non-group chat still works**

Send a message in a regular (non-group) conversation. Verify:
- Streaming output appears in real-time
- Message completes normally with no error
- If you stop generation, it stops cleanly

- [ ] **Step 3: Verify group chat sub-agent streaming**

Send a message in a group chat that will trigger multiple sub-agents. Verify:
- Orchestrator ("任务分配智能体") streams its plan in real-time
- When sub-agents spawn, each gets its own bubble with "生成中..." status
- Sub-agent content appears in real-time in their respective bubbles
- If a sub-agent takes >90s, its bubble stays "生成中..." (not "生成失败")
- When sub-agents complete, their bubbles show completed state
- The final `done` event fires after the last sub-agent finishes
- SSE disconnects cleanly

- [ ] **Step 4: Verify refresh consistency**

After all agents complete, refresh the page. Verify all sub-agent message content matches what was seen during streaming.
