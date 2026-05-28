# Group Chat — @AgentName Format Match & Highlight Fix

**Date:** 2026-05-28
**Status:** Implemented
**Topic:** Fix format mismatch between Orchestrator output and Dispatcher regex, and fix frontend @AgentName highlighting

## Problem Summary

Two bugs prevent group chat @AgentName dispatch from working:

1. **Backend format mismatch** — The Orchestrator's system prompt (`agents.yml`) instructs it to output `@Agent名称 任务描述` (space-separated), but `AgentMentionDispatcher` expects `@Agent名称: 任务描述` (colon-separated). The dispatcher regex never matches, so member agents are never spawned.

2. **Frontend highlighting too greedy** — `useMarkdown.js` uses a generic regex `@([^\s,，。；;:：\n]+)` that matches everything after `@` until whitespace or punctuation, without distinguishing agent name from task text. When the LLM omits the space between agent name and task description, the entire line gets highlighted as a mention.

## Root Causes

### Bug 1 — Regex Format Mismatch

| Component | Expected Format | Source |
|-----------|----------------|--------|
| `agents.yml` system prompt (line 45) | `@智能体名称 任务描述` | Space-separated |
| `AgentMentionDispatcher.java` (line 40) | `^@(\S+):\s*(.+)$` | Colon-separated |

The colon was specified in the original 2026-05-27 design doc but was never adopted by the system prompt.

### Bug 2 — Greedy Regex Without Agent Name Knowledge

`useMarkdown.js:15` regex `@([^\s,，。；;:：\n]+)` matches `@` then consumes all non-delimiter characters. When output is `@通用助手请回应问候语"你好"` (no space), the entire string matches. Chinese full-width quotes `""` are not in the exclusion set, compounding the issue.

## Design

### 1. Backend — Fix Dispatcher Regex

**File:** `AgentMentionDispatcher.java`

Change the regex from colon-separated to space-separated to match the system prompt:

```
Before: ^@(\S+):\s*(.+)$
After:  ^@(\S+)\s+(.+)$
```

This is a one-character change (`:` → ` `). The `\s+` quantifier also ensures at least one whitespace between agent name and task content, preventing false matches.

### 2. Frontend — Agent-Name-Aware Highlighting

**File:** `useMarkdown.js`

Replace the generic regex with a known-agent-name matcher. The `highlightAgentMentions` function now accepts an `agentNames` array:

```js
function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function highlightAgentMentions(text, agentNames = []) {
  if (!text) return ''
  if (!agentNames || agentNames.length === 0) return text
  // Sort by length descending to prevent short names matching long names first
  const sorted = [...agentNames].sort((a, b) => b.length - a.length)
  const escaped = sorted.map(escapeRegex)
  const pattern = new RegExp(`@(${escaped.join('|')})`, 'g')
  return text.replace(pattern, '<span class="agent-mention">$&</span>')
}
```

Design decisions:
- **Length-descending sort** — prevents `助手` from matching before `通用助手`
- **Regex escaping** — agent names may contain special chars like `(` `)` `+`
- **Empty array fallback** — non-group chats pass no agent names, function returns text unchanged
- **Match `@${name}` pattern** — only the `@` + exact agent name gets highlighted, not surrounding text

### 3. Frontend — Wire Agent Names Through Components

**`ChatArea.vue`:**
- Add computed `agentNames` extracting member names from `conversation.members`
- Pass `:agent-names="agentNames"` to `MessageBubble`

**`MessageBubble.vue`:**
- Add `agentNames` prop
- Pass to `highlightAgentMentions(content, agentNames)`

### Data Flow

```
ChatArea.vue
  └── conversation.members[] → agentNames (computed)
       └── MessageBubble.vue (:agent-names prop)
            └── renderedContent (computed)
                 └── highlightAgentMentions(text, agentNames)
                      └── <span class="agent-mention">@通用助手</span>
                 └── renderMarkdown(result)
                      └── v-html output
```

## Files Changed

| File | Change |
|------|--------|
| `AgentMentionDispatcher.java` | Regex: colon → space separator |
| `useMarkdown.js` | Replace greedy regex with agent-name-based matching |
| `MessageBubble.vue` | Add `agentNames` prop, wire to highlight function |
| `ChatArea.vue` | Compute agent names from conversation members, pass to MessageBubble |

## Edge Cases

| Scenario | Handling |
|----------|----------|
| Agent name contains regex special chars | `escapeRegex()` sanitizes before building pattern |
| Short agent name is substring of longer one | Length-descending sort ensures longer names match first |
| Non-group chat (no agent names) | Empty array → function returns text unmodified |
| Agent name not in the message | No matches, text returned as-is |
| Orchestrator outputs no space after @name | Dispatcher won't match (strict `\s+`), line shown as plain text |
| Same @mention repeated in one message | Global regex flag handles all occurrences |
