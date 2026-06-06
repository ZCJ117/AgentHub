# AgentHub Frontend Design Spec

**Date:** 2026-05-22  
**Version:** v1.0  
**Status:** Approved — awaiting implementation plan

---

## 1. Overview

Build the complete frontend for AgentHub — a multi-Agent collaboration platform with IM-style chat — on top of the existing `AIagent_frontend` Vue 3 codebase. The backend (`mateclaw-dev`, Spring Boot 3) is already built and must not be modified.

### 1.1 Key Design Decisions (from brainstorming)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Auth approach | Real JWT from `POST /auth/login` | Foundation for all API calls, no rework |
| Backend port | 18088 | Per API doc |
| Workspace | Auto-select first from `GET /workspaces` | Minimal viable, switcher deferred |
| API code format | `code: 200` (number) | Per new API doc |
| Component library | Naive UI | Apple-clean aesthetic, good Vue 3 support, covers data components |
| Architecture | Hybrid: Stores + Composables | Cross-component state in Pinia, reusable behaviors in composables |
| Build strategy | Phased: IM core → Orchestrator → Artifacts → Agent/Settings | Each phase fully working before next |

---

## 2. File Structure

```
AIagent_frontend/src/
├── main.js                              # unchanged
├── App.vue                              # simplified
├── api/
│   ├── client.js                        # REWRITE: JWT interceptor, code=200
│   ├── auth.js                          # NEW
│   ├── conversations.js                 # NEW
│   ├── chat.js                          # REWRITE: SSE stream
│   ├── messages.js                      # NEW
│   ├── agents.js                        # REWRITE
│   ├── orchestrator.js                  # NEW
│   ├── artifacts.js                     # NEW
│   ├── workspaces.js                    # NEW
│   └── dashboard.js                     # NEW
├── stores/
│   ├── auth.js                          # REWRITE: JWT
│   ├── workspace.js                     # NEW
│   ├── conversation.js                  # NEW
│   ├── chat.js                          # REWRITE: SSE
│   ├── agent.js                         # REWRITE
│   ├── orchestrator.js                  # NEW
│   └── artifact.js                      # NEW
├── composables/
│   ├── useSSE.js                        # NEW: EventSource + reconnect
│   ├── useMarkdown.js                   # NEW
│   ├── useVirtualScroll.js              # NEW
│   ├── useTextareaAutosize.js           # keep
│   └── useCookie.js                     # keep
├── router/
│   └── index.js                         # EXTEND: 6 routes
├── utils/
│   ├── html.js                          # keep
│   ├── time.js                          # EXTEND
│   └── token.js                         # NEW: JWT storage
├── views/
│   ├── LoginView.vue                    # REFACTOR
│   ├── ChatView.vue                     # MAJOR REFACTOR
│   ├── AgentManageView.vue              # NEW
│   ├── AgentDetailView.vue              # NEW
│   ├── ArtifactListView.vue             # NEW
│   ├── ArtifactDetailView.vue           # NEW
│   └── SettingsView.vue                 # NEW
├── components/
│   ├── chat/
│   │   ├── ConversationSidebar.vue      # NEW
│   │   ├── ChatArea.vue                 # REFACTOR
│   │   ├── ChatEmpty.vue                # keep
│   │   ├── Composer.vue                 # REFACTOR
│   │   ├── MessageBubble.vue            # MAJOR REFACTOR
│   │   ├── StatusBar.vue                # REFACTOR
│   │   ├── PlanCard.vue                 # NEW
│   │   ├── DiffViewCard.vue             # NEW
│   │   └── ArtifactPreviewCard.vue      # NEW
│   ├── layout/
│   │   ├── TopBar.vue                   # refine
│   │   ├── UserPill.vue                 # refine
│   │   └── DetailPanel.vue              # NEW
│   ├── common/
│   │   ├── AppButton.vue                # keep
│   │   ├── AppModal.vue                 # keep
│   │   ├── BrandMark.vue                # keep
│   │   └── PageTransition.vue           # keep
│   ├── agent/
│   │   ├── AgentCard.vue                # NEW
│   │   ├── AgentSelector.vue            # NEW
│   │   └── AgentForm.vue                # NEW
│   └── artifact/
│       ├── ArtifactCard.vue             # NEW
│       ├── VersionTimeline.vue          # NEW
│       └── DeployPanel.vue              # NEW
└── assets/styles/
    ├── variables.css                     # EXTEND
    ├── base.css                          # keep
    ├── animations.css                    # keep
    └── transitions.css                   # keep
```

---

## 3. API Client & Auth

### 3.1 API Client (`api/client.js`)

Axios instance, rewrite from scratch:

- **Base URL**: `import.meta.env.VITE_API_BASE` or proxy `/api/v1/`
- **Request interceptor**: attach `Authorization: Bearer <token>` and `X-Workspace-Id`
- **Response interceptor**: check `code === 200` → unwrap `data`; `code === 401` → redirect `/login`; network error → toast
- **Token refresh**: detect `X-New-Token` response header, silently update stored JWT
- **Pagination passthrough**: return `{ records, total, current, size, pages }` as-is

### 3.2 Auth Flow

1. `POST /api/v1/auth/login { username, password }` → `{ userId, token, username, nickname, role }`
2. Store JWT in `localStorage` with expiry timestamp (24h)
3. `GET /api/v1/workspaces` → pick first workspace, store `workspaceId`
4. Router guard: `/login` requires guest, all others require valid token
5. Logout: clear localStorage, reset all Pinia stores, redirect `/login`

### 3.3 Route Guards

```js
// router/index.js expanded
{ path: '/login',               meta: { requiresGuest } }
{ path: '/chat/:conversationId?', meta: { requiresAuth } }
{ path: '/agents',              meta: { requiresAuth } }
{ path: '/agents/:id',          meta: { requiresAuth } }
{ path: '/artifacts',           meta: { requiresAuth } }
{ path: '/artifacts/:id',       meta: { requiresAuth } }
{ path: '/settings',            meta: { requiresAuth } }
{ path: '/', redirect: '/chat' }
{ path: '/:pathMatch(.*)*', redirect: '/chat' }
```

---

## 4. Chat Architecture

### 4.1 SSE Client (`composables/useSSE.js`)

- Uses `fetch` + `ReadableStream` for POST-based SSE (`POST /chat/stream`). EventSource only supports GET, so `fetch` is required. `api/chat.js` uses `fetch` directly (not Axios) for streaming.
- Parses SSE text protocol: splits on `\n\n`, extracts `event:` and `data:` lines, parses JSON data
- Auto-reconnect with exponential backoff: 1s → 2s → 4s → 8s → max 30s
- `Last-Event-ID` for stream resumption via `X-Last-Event-Id` header
- Returns reactive `{ isConnected, error }`
- Exposes: `on(eventType, callback)`, `close()`

### 4.1.1 Markdown Rendering

- Use `marked` library for markdown-to-HTML, `DOMPurify` for HTML sanitization
- Code blocks rendered with NCode (Naive UI) via a custom marked renderer override

### 4.2 Chat Store (`stores/chat.js`)

```
State:
  conversationId, messages[], isStreaming, streamStatus, currentTurnId

Actions:
  initConversation(convId)       # load history, connect SSE
  sendMessage(text)              # POST /chat/stream
  stopGeneration()               # POST /chat/{id}/stop
  interrupt(message)             # POST /chat/{id}/interrupt
  addReaction(msgId, type)       # POST /messages/{id}/reactions
  regenerate(msgId)              # POST /messages/{id}/regenerate
  loadMoreHistory(beforeId)      # GET /conversations/{id}/messages?beforeId=

SSE event dispatching:
  text              → append delta to assistant bubble
  tool_call         → show tool indicator
  tool_result       → show result snippet
  orchestrator_plan → dispatch to orchestratorStore
  delegation_progress → dispatch to orchestratorStore
  artifact_preview  → dispatch to artifactStore
  done              → finalize message, record turnId
  error             → show inline error
```

### 4.3 Conversation Store (`stores/conversation.js`)

```
State:
  conversations[], activeId, loading, searchKeyword, filter

Actions:
  loadList()          # GET /conversations?conversationType=&keyword=
  createDirect(aid)   # implicit via first message
  createGroup(cfg)    # POST /conversations/group
  togglePin(id)       # PUT /conversations/{id}/pin
  toggleArchive(id)   # PUT /conversations/{id}/archive
  delete(id)          # DELETE /conversations/{id}
  setActive(id)       # switch + load messages

Computed:
  sortedConversations   # pinned first, then lastActiveAt DESC
  activeConversation    # full detail
  unreadTotal           # sum of unreadCount
```

### 4.4 Message Bubble Rendering

| messageType | Render |
|-------------|--------|
| `text` | Markdown rendered HTML |
| `code` | NCode + copy button |
| `diff` | DiffViewCard with apply/reject |
| `image` | Image with lightbox |
| `file` | File icon + name + size + download |
| `preview_card` | Sandboxed iframe inline |
| `plan_card` | PlanCard component |
| `system` | Blue-left-border system message |

---

## 5. Orchestrator & Group Chat

### 5.1 Orchestrator Store (`stores/orchestrator.js`)

```
State: currentTask, assignments[], taskHistory[]
Actions: loadTask, loadDetail, loadAssignments, retry, cancel
SSE-driven: orchestrator_plan → set task; delegation_progress → update assignments
Computed: progressPercent, activeAssignments, failedAssignments
```

### 5.2 PlanCard Component

Special message bubble showing:
- Task title, step list with agent avatar + name + goal + status icon
- Dependency arrows between sequential steps
- Progress bar (completed/total)
- Cancel / Retry-failed buttons
- Updates live via `delegation_progress` SSE events
- Sub-agent messages appear indented below the plan card

### 5.3 Group Chat Creation

1. Click [New Group] in sidebar
2. Modal: title, multi-select agents, orchestrator selection
3. Config: schedulingMode (auto/manual), failurePolicy, maxParallelTasks
4. `POST /conversations/group` → conversation created
5. Sidebar refreshes, navigates to new group

---

## 6. Artifact System

### 6.1 Artifact Store (`stores/artifact.js`)

```
State:
  artifacts[], current, versions[], diffResult, deployStatus, deployHistory[]

Actions:
  loadList, loadDetail, loadVersions, loadVersionDetail
  loadDiff(aid, from, to)       # GET /artifacts/{id}/versions/diff
  restoreVersion(aid, vid)      # POST restore
  deploy(aid, target, vid?)     # POST deploy
  loadDeployStatus, loadDeployHistory
  updateTags(aid, tags)         # PUT /artifacts/{id}/tags
```

### 6.2 Artifact List View

- Filter tabs by type (All | HTML | Code | Markdown | PDF | Image)
- Card grid or table, each card: type icon, name, creator agent, version, deploy status
- Search by name, sort by updatedAt DESC

### 6.3 Artifact Detail View

Three-panel layout:
- **Left**: Version timeline (click to select)
- **Center**: Preview based on type (iframe for HTML, NCode for code, rendered HTML for markdown, full image, embedded PDF viewer)
- **Right**: Metadata, deploy button, tag editor
- Diff mode: select two versions → unified diff with NCode diff highlighting
- Restore button on non-current versions

### 6.4 Artifact Preview Card (inline in chat)

- Triggered by `artifactRefs` in message / `artifact_preview` SSE event
- Mini preview + [Preview] [Edit] [Deploy] [Download] buttons
- Click "Preview" → expands DetailPanel

### 6.5 Deploy Flow

1. Click [Deploy]
2. `POST /artifacts/{id}/deploy { deployTarget, versionId? }`
3. Poll `GET /artifacts/{id}/deploy/status` every 3s
4. Show progress → URL on success / error log on failure

---

## 7. Agent Management & Settings

### 7.1 Agent Store (`stores/agent.js`)

```
State: agents[], current, stats, loading
Actions: loadAgents, loadDetail, create, update, delete, uploadAvatar, loadStats
Computed: enabledAgents (enabled=true)
```

### 7.2 Agent Manage View

- Card grid with filter tabs (All | ReAct | Plan-Execute | Orchestrator)
- Each card: NAvatar, name, NTag capability tags, status dot
- [新建 Agent] button, search bar
- Click card → AgentDetailView

### 7.3 Agent Detail View

- Header: back button, avatar (uploadable), name, description, status toggle
- Tabs: [配置] [技能] [工具] [统计]
  - 配置: system prompt textarea, model selection, capability tags (NDynamicTags)
  - 技能: skill list with bind/unbind
  - 工具: tool list with toggle
  - 统计: conversation count, messages, tokens, avg response time

### 7.4 Settings View

- Profile form: nickname, email (GET/PUT /auth/me)
- Password change (PUT /auth/users/{id}/password)
- PAT management: list, create, revoke
- System info from GET /system/health

---

## 8. Component Dependencies

### 8.1 Naive UI Components Used

NAvatar, NTag, NBadge, NCode, NDataTable, NTabs, NInput, NButton, NModal, NSelect, NTransfer, NPopover, NPopconfirm, NSpin, NProgress, NTimeline, NDynamicTags, NUpload, NCard, NTree, NLayout, NLayoutSider, NLayoutContent, NLayoutHeader, NSpace, NDivider, NGi, NGrid, NMessageProvider, NNotificationProvider, NDialogProvider

### 8.2 Naive UI Theme Override

Map existing CSS variables to Naive UI theme tokens:
- Primary color: `#2E75B6`
- Success: `#34C759`, Warning: `#FF9500`, Error: `#FF3B30`
- Font family: `system-ui, -apple-system, "SF Pro Text", "PingFang SC", sans-serif`
- Border radius: `14px` (md), `20px` (lg)
- Background: `#F5F5F7`

### 8.3 Existing Components Retained

All `components/common/` animation components (ScrollReveal, BlurRevealText, ParticleGrid, OrbitRing, StaggerReveal, AnimatedText, AppButton, AppModal, BrandMark, PageTransition), `components/login/` (HeroSection, LoginForm, FeatureItem), and `composables/` (useCookie, useTextareaAutosize, useHoverLift, useScrollReveal, etc.) are retained.

---

## 9. Phased Implementation Order

| Phase | Scope | Key Deliverables |
|-------|-------|-----------------|
| **Phase 1** | Foundation + IM Core | Auth (login, JWT, guards), API client rewrite, conversation sidebar, ChatView SSE streaming, direct chat, basic message types (text/code), composer |
| **Phase 2** | Group Chat + Orchestrator | Group creation, orchestrator store, PlanCard, delegation progress in chat, assignment tracking |
| **Phase 3** | Artifacts | Artifact list/detail views, version timeline, diff view, preview card inline, deploy flow |
| **Phase 4** | Agent Mgmt + Settings | Agent list/detail, create/edit form, stats, settings view, PAT management |

---

## 10. Error & Edge Case Handling

- **SSE disconnect**: auto-reconnect with backoff, show "Reconnecting..." indicator in StatusBar
- **401 Unauthorized**: clear token, redirect to `/login`
- **Empty states**: ChatEmpty for no conversations, empty list placeholders for agents/artifacts
- **Loading states**: NSpin skeleton for initial loads, NSkeleton for list items
- **Long messages**: markdown content capped at viewport, "Show more" expand
- **XSS**: all user/AI content rendered through `html.js` escape before markdown parsing
- **Responsive**: 1366px+ desktop (design spec target), sidebar collapsible below that
- **Token expiry**: check on app boot, force logout if expired; silent refresh via X-New-Token header
