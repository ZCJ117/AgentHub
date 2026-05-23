# AgentDetailView Skills / Tools / Stats Tabs Design

**Date:** 2026-05-23  
**Status:** Approved

## Overview

Replace the three "开发中" placeholder tabs in `AgentDetailView.vue` (Skills, Tools, Stats) with functional content backed by existing backend APIs.

## Backend API reference

| Endpoint | Method | Request Body | Response |
|----------|--------|-------------|----------|
| `/api/v1/agents/{id}/skills` | GET | — | `List<AgentSkillBinding>` (id, agentId, skillId, enabled) |
| `/api/v1/agents/{id}/skills` | PUT | `List<Long>` (plain array of skill IDs) | `R<Void>` |
| `/api/v1/agents/{id}/tools` | GET | — | `List<AgentToolBinding>` (id, agentId, toolName, enabled) |
| `/api/v1/agents/{id}/tools` | PUT | `List<String>` (plain array of tool names) | `R<Void>` |
| `/api/v1/agents/{id}/provider-preferences` | GET | — | `List<AgentProviderPreference>` |
| `/api/v1/agents/{id}/provider-preferences` | PUT | `List<String>` (plain array of provider IDs) | `R<Void>` |
| `/api/v1/agents/{id}/stats` | GET | — | `AgentStatsVO` |
| `/api/v1/skills/enabled` | GET | — | `List<SkillEntity>` (id, name, nameZh, nameEn, description) |
| `/api/v1/tools/available` | GET | — | `List<AvailableToolDTO>` (name, description, source, group) |

### AgentStatsVO fields

`agentId`, `totalConversations`, `totalMessages`, `totalTokens`, `avgResponseTimeMs`, `lastActiveAt`

## Files

| File | Action |
|------|--------|
| `AIagent_frontend/src/api/agent-bindings.js` | **New** — binding + stats API functions |
| `AIagent_frontend/src/api/agent.js` | Add `fetchSkills()` and `fetchAvailableTools()` catalog functions |
| `AIagent_frontend/src/views/AgentDetailView.vue` | Replace 3 tab placeholders with real content |

## 1. API module: `src/api/agent-bindings.js`

Six functions using the existing `apiClient`:

- `fetchAgentSkills(agentId)` — GET bound skills
- `updateAgentSkills(agentId, skillIds)` — PUT plain `Long[]`
- `fetchAgentTools(agentId)` — GET bound tools
- `updateAgentTools(agentId, toolNames)` — PUT plain `String[]`
- `fetchProviderPreferences(agentId)` — GET provider prefs
- `updateProviderPreferences(agentId, providerIds)` — PUT plain `String[]`
- `fetchAgentStats(agentId)` — GET stats

Body format: plain arrays, not wrapped in objects — matches backend `@RequestBody List<...>`.

## 2. Skills Tab

**Data flow:**
1. Fetch agent's bound skills → `fetchAgentSkills(agentId)` returns `AgentSkillBinding[]`
2. Fetch skill catalog → `GET /api/v1/skills/enabled` returns `SkillEntity[]` (id, name, description)
3. Pre-select checkboxes: skill is checked if its `id` is in the bound skill IDs set
4. Save: send `selectedSkillIds` as plain `Long[]`, refresh bindings on success

**UI:** Checkbox group with each skill showing name (bold) and description (muted). Empty state shows `n-empty` with "暂未绑定技能". Save button with loading state.

## 3. Tools Tab

**Data flow:**
1. Fetch agent's bound tools → `fetchAgentTools(agentId)` returns `AgentToolBinding[]`
2. Fetch tool catalog → `GET /api/v1/tools/available` returns `AvailableToolDTO[]` (name, description, source, group)
3. Pre-select checkboxes: tool is checked if its `name` matches a bound `toolName`
4. Save: send `selectedToolNames` as plain `String[]`, refresh bindings on success

**UI:** Same checkbox pattern as Skills. Each tool shows name, description, and a source badge (`builtin` / `mcp` / `channel`). Grouped by `group` field with section headers.

## 4. Stats Tab

**Data flow:** Fetch `fetchAgentStats(agentId)` on tab activation, display with `n-spin` while loading.

**Display fields:**

| Label | Field | Format |
|-------|-------|--------|
| 总会话数 | `totalConversations` | number |
| 总消息数 | `totalMessages` | number |
| Token 用量 | `totalTokens` | number |
| 平均响应时间 | `avgResponseTimeMs` | `(ms / 1000).toFixed(2) + ' s'` |
| 最后活跃 | `lastActiveAt` | datetime string (formatted via `time.js` util) |

Layout: `n-grid` 3 columns × 2 rows, each cell an `n-statistic`.

## 5. AgentDetailView.vue changes

- Import new API functions and add reactive state for each tab
- Replace `<p class="tab-placeholder">` in each `n-tab-pane` with the real template
- Add `onMounted` fetch logic (lazy: fetch only when tab is activated)
- Handle "new agent" case: hide Skills/Tools/Stats tabs (only show Config)

## Edge cases

- **New agent (`agentId === 'new'`):** Only show Config tab, hide Skills/Tools/Stats tabs
- **Empty bindings:** Show empty state with `n-empty`
- **Save error:** Log warning, keep current selection (don't revert)
- **Network error:** Handled by existing Axios interceptor
- **New agent id detection:** Use `String(route.params.id) === 'new'` per existing pattern

## Verification

1. Navigate to an existing agent → Skills tab shows checkbox list of available skills
2. Check/uncheck skills, click save → refresh → selections persist
3. Tools tab same behavior with tool names
4. Stats tab shows numeric values without errors
5. New agent page only shows Config tab
