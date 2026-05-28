# Group Chat @AgentName Format Match & Highlight Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two bugs: (1) Backend dispatcher regex expects colon separator but orchestrator outputs space-separated format, preventing agent spawn. (2) Frontend @AgentName highlighting greedily matches entire sentence instead of just the agent name.

**Architecture:** Single-char backend regex fix in `AgentMentionDispatcher` to match space-separated `@Name task` format. Frontend replaces generic regex with agent-name-aware matcher that accepts known agent names, wired through `ChatArea` → `MessageBubble` → `useMarkdown`.

**Tech Stack:** Java 21 (backend regex change), Vue 3 + JavaScript (frontend composable + component props)

---

## File Structure

| File | Role | Action |
|------|------|--------|
| `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/service/AgentMentionDispatcher.java` | Dispatcher regex — matches @AgentName lines from orchestrator stream | Modify: line 40 |
| `AIagent_frontend/src/composables/useMarkdown.js` | Highlighting composable — wraps @mentions in styled spans | Modify: lines 15-20 |
| `AIagent_frontend/src/components/chat/MessageBubble.vue` | Message renderer — calls highlightAgentMentions | Modify: props + line 43 |
| `AIagent_frontend/src/components/chat/ChatArea.vue` | Chat container — extracts agent names from conversation | Modify: add computed + template |

---

### Task 1: Fix Backend Dispatcher Regex

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/service/AgentMentionDispatcher.java:39-40`

- [ ] **Step 1: Change regex from colon-separated to space-separated**

The orchestrator system prompt (`agents.yml` line 45) outputs `@智能体名称 任务描述` (space-separated), but the dispatcher expects `@AgentName: task` (colon-separated). Change the pattern:

```java
// Before:
/** Regex: @AgentName: task content */
private static final Pattern AGENT_PATTERN = Pattern.compile("^@(\\S+):\\s*(.+)$");

// After:
/** Regex: @AgentName task content */
private static final Pattern AGENT_PATTERN = Pattern.compile("^@(\\S+)\\s+(.+)$");
```

The `\s+` (one-or-more whitespace) replaces `:\s*` (colon + optional whitespace). This is the root cause of Bug 2 — agents were never spawned because the regex never matched.

- [ ] **Step 2: Compile to verify no syntax errors**

```bash
cd mateclaw-dev && ./mvnw clean compile -pl mateclaw-server -am -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/service/AgentMentionDispatcher.java
git commit -m "fix: align @AgentName dispatcher regex with orchestrator output format"
```

---

### Task 2: Replace Greedy Highlighting Regex with Agent-Name Matcher

**Files:**
- Modify: `AIagent_frontend/src/composables/useMarkdown.js:12-26`

- [ ] **Step 1: Replace `highlightAgentMentions` implementation**

```js
// Remove:
const AGENT_MENTION_RE = /@([^\s,，。；;:：\n]+)/g

export function highlightAgentMentions(text) {
  if (!text) return ''
  return text.replace(AGENT_MENTION_RE, '<span class="agent-mention">$&</span>')
}

// Add:
function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function highlightAgentMentions(text, agentNames = []) {
  if (!text) return ''
  if (!agentNames || agentNames.length === 0) return text
  const sorted = [...agentNames].sort((a, b) => b.length - a.length)
  const escaped = sorted.map(escapeRegex)
  const pattern = new RegExp(`@(${escaped.join('|')})`, 'g')
  return text.replace(pattern, '<span class="agent-mention">$&</span>')
}
```

Key design decisions:
- `escapeRegex()` sanitizes agent names containing regex special chars (parentheses, plus signs, etc.)
- Length-descending sort prevents short name from matching within a longer name (e.g., `助手` inside `通用助手`)
- Empty `agentNames` array returns text unchanged — safe for non-group chats
- Match pattern `@(${name})` ensures only `@` + the exact agent name is highlighted

- [ ] **Step 2: Verify no build errors**

```bash
cd AIagent_frontend && npx vite build --mode development 2>&1 | tail -5
```
Expected: no errors related to useMarkdown

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/composables/useMarkdown.js
git commit -m "fix: replace greedy @AgentName regex with agent-name-aware matcher"
```

---

### Task 3: Wire Agent Names Through Components

**Files:**
- Modify: `AIagent_frontend/src/components/chat/MessageBubble.vue:14-15,42-43`
- Modify: `AIagent_frontend/src/components/chat/ChatArea.vue:38-42,107`

- [ ] **Step 1: Add `agentNames` prop to MessageBubble**

```js
// MessageBubble.vue — add to defineProps:
const props = defineProps({
  message: { type: Object, required: true },
  isPinned: { type: Boolean, default: false },
  agentNames: { type: Array, default: () => [] }  // NEW
})
```

- [ ] **Step 2: Pass `agentNames` to `highlightAgentMentions`**

```js
// MessageBubble.vue — line 43, updated call:
const renderedContent = computed(() => {
  if (props.message.messageType === 'text' || props.message.messageType === 'system') {
    const highlighted = highlightAgentMentions(props.message.content || '', props.agentNames)
    return renderMarkdown(highlighted)
  }
  return ''
})
```

- [ ] **Step 3: Compute `agentNames` in ChatArea and pass as prop**

```js
// ChatArea.vue — add computed (after headerSubtitle, before headerAvatar):
const agentNames = computed(() => {
  const members = props.conversation?.members
  if (!members || !Array.isArray(members)) return []
  return members.map(m => m.agentName).filter(Boolean)
})
```

```html
<!-- ChatArea.vue — template, add :agent-names binding to MessageBubble -->
<MessageBubble
  v-for="msg in messages"
  :key="msg.id"
  :message="msg"
  :is-pinned="pinnedIds.has(msg.id)"
  :agent-names="agentNames"
  ...
/>
```

- [ ] **Step 4: Verify build**

```bash
cd AIagent_frontend && npx vite build --mode development 2>&1 | tail -5
```
Expected: no errors

- [ ] **Step 5: Final commit**

```bash
git add AIagent_frontend/src/components/chat/MessageBubble.vue AIagent_frontend/src/components/chat/ChatArea.vue
git commit -m "fix: wire agent names through ChatArea to MessageBubble for @mention highlighting"
```

---

## Verification Checklist

After all tasks are complete, verify end-to-end:

1. **Backend regex**: Start backend, create a group chat with 2+ agents, send a message. The orchestrator's output containing `@AgentName task` should trigger the dispatcher. Check logs for `[Dispatcher] Agent X completed`.

2. **Frontend highlighting**: In a group chat with agent `通用助手`, when the orchestrator outputs `@通用助手 请完成XXX`, the rendered HTML should contain `<span class="agent-mention">@通用助手</span>` followed by plain text ` 请完成XXX`.

3. **Non-group chat regression**: In a 1:1 chat without agent names, messages should render without errors. `agentNames` is empty array, function returns text unmodified.

4. **Edge: special chars in name**: Create an agent named `测试Agent(v2)` and verify the regex escape handles the parentheses.
