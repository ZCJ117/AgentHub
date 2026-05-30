# AgentHub Frontend — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Foundation + IM Core of the AgentHub multi-Agent collaboration platform: JWT auth, conversation sidebar, SSE streaming direct chat.

**Architecture:** Hybrid Stores + Composables pattern. Pinia stores hold cross-component shared state (auth, workspace, conversation, chat, agent). Composables handle reusable behaviors (SSE, markdown, virtual scroll). Naive UI provides data components. Existing animation components retained. All API calls flow through the rewritten Axios client with JWT interceptor.

**Tech Stack:** Vue 3.4 + Pinia 2 + Vue Router 4 + Vite 5 + Axios + Naive UI + marked + DOMPurify

**Phase 1 Scope:** Auth (login, JWT, guards), API client rewrite, conversation sidebar, ChatView SSE streaming, direct chat, text/code message types, composer.

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `package.json` | Modify | Add naive-ui, marked, dompurify |
| `vite.config.js` | Modify | Change proxy target to port 18088 |
| `src/main.js` | Modify | Register Naive UI, update imports |
| `src/App.vue` | Rewrite | Simplify to RouterView + PageTransition |
| `src/utils/token.js` | Create | JWT localStorage get/set/clear/expiry check |
| `src/api/client.js` | Rewrite | Axios instance: JWT header, workspace header, code=200 unwrap |
| `src/api/auth.js` | Create | POST login, GET me, PUT me, PUT password |
| `src/api/workspaces.js` | Create | GET list |
| `src/api/agents.js` | Rewrite | GET list (new endpoint), GET detail |
| `src/api/conversations.js` | Create | GET list, GET detail, GET messages, DELETE, PUT title/pin/archive |
| `src/api/chat.js` | Rewrite | POST stream (fetch-based SSE), POST stop, POST interrupt |
| `src/api/messages.js` | Create | GET detail, POST regenerate, reactions |
| `src/stores/auth.js` | Rewrite | JWT login/logout, token lifecycle |
| `src/stores/workspace.js` | Create | Active workspace auto-select |
| `src/stores/agent.js` | Rewrite | Agent list/detail loading |
| `src/stores/conversation.js` | Create | Conversation list, active, CRUD, pin/archive |
| `src/stores/chat.js` | Rewrite | SSE streaming, messages[], send/stop/regenerate |
| `src/composables/useSSE.js` | Create | fetch + ReadableStream SSE parsing + reconnect |
| `src/composables/useMarkdown.js` | Create | marked + DOMPurify + NCode override |
| `src/router/index.js` | Extend | Add routes, JWT-based guards |
| `src/views/LoginView.vue` | Refactor | Connect to POST /auth/login |
| `src/views/ChatView.vue` | Rewrite | Three-panel layout: sidebar + chat + optional panel |
| `src/components/chat/ConversationSidebar.vue` | Create | Conversation list, search, new direct/group, pin/archive/delete |
| `src/components/chat/ChatArea.vue` | Refactor | Message list with virtual scroll, auto-scroll |
| `src/components/chat/MessageBubble.vue` | Rewrite | Multi-type rendering (text/code/diff/plan_card/preview_card) |
| `src/components/chat/Composer.vue` | Refactor | Textarea with autosize, send button, placeholder |
| `src/components/chat/StatusBar.vue` | Refactor | SSE connection status indicator |
| `src/components/chat/ChatEmpty.vue` | Keep | Unchanged |
| `src/assets/styles/variables.css` | Extend | New color tokens for Naive UI theme |
| `src/components/layout/TopBar.vue` | Refine | Workspace badge, nav links |
| `src/components/layout/UserPill.vue` | Refine | Real user info |

---

## Project Notes

- These existing components are retained as-is: `components/common/*` (all animation and UI components), `components/login/*` (HeroSection, LoginForm, FeatureItem), `composables/useCookie.js`, `composables/useTextareaAutosize.js`, `composables/useHoverLift.js`, `composables/useScrollReveal.js`, etc.
- The old `api/session.js` and `api/agent.js` will be replaced; `api/chat.js` will be rewritten.
- Delete `utils/cookie.js` is NOT deleted — the composable still uses it. But auth moves to `utils/token.js`.
- Backend runs on port 18088, the vite proxy `/api` → `http://127.0.0.1:18088`.

---

## Group A: Project Setup

### Task A1: Install dependencies and update configs

**Files:**
- Modify: `AIagent_frontend/package.json`
- Modify: `AIagent_frontend/vite.config.js`
- Modify: `AIagent_frontend/src/main.js`
- Create: `AIagent_frontend/src/assets/styles/naive-theme.js`

- [ ] **Step 1: Install new dependencies**

```bash
cd AIagent_frontend
npm install naive-ui marked dompurify@3 @types/dompurify
```

- [ ] **Step 2: Update package.json**

Ensure `package.json` has all dependencies listed. After install it should include:
```json
{
  "dependencies": {
    "axios": "^1.7.2",
    "dompurify": "^3.x",
    "marked": "^x.x",
    "naive-ui": "^x.x",
    "pinia": "^2.1.7",
    "vue": "^3.4.27",
    "vue-router": "^4.3.2"
  }
}
```

- [ ] **Step 3: Update vite.config.js — proxy target to port 18088**

File: `AIagent_frontend/vite.config.js`
```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18088',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets'
  }
})
```

- [ ] **Step 4: Create Naive UI theme config**

File: `AIagent_frontend/src/assets/styles/naive-theme.js`
```js
import { createTheme } from 'naive-ui'

export const naiveTheme = createTheme({
  common: {
    primaryColor: '#2E75B6',
    primaryColorHover: '#3A8FD4',
    primaryColorPressed: '#2568A0',
    primaryColorSuppl: '#2E75B6',
    successColor: '#34C759',
    warningColor: '#FF9500',
    errorColor: '#FF3B30',
    fontFamily: 'system-ui, -apple-system, "SF Pro Text", "PingFang SC", sans-serif',
    fontFamilyMono: '"SF Mono", "Fira Code", monospace',
    borderRadius: '14px',
    bodyColor: '#F5F5F7',
    cardColor: '#FFFFFF',
    textColor1: '#1D1D1F',
    textColor2: '#666666',
    textColor3: '#999999',
    dividerColor: '#E5E5EA',
    inputColor: '#FFFFFF',
    modalColor: '#FFFFFF',
    popoverColor: '#FFFFFF',
    actionColor: '#F5F5F7'
  }
})
```

- [ ] **Step 5: Update src/main.js to register Naive UI**

File: `AIagent_frontend/src/main.js`
```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import naive from 'naive-ui'
import { naiveTheme } from './assets/styles/naive-theme'
import './assets/styles/base.css'
import './assets/styles/animations.css'
import './assets/styles/transitions.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(naive, { theme: naiveTheme })
app.mount('#app')
```

- [ ] **Step 6: Verify setup**

Run: `cd AIagent_frontend && npm run dev`
Expected: Dev server starts on port 3000 without errors. Page loads (may show login due to auth guard).

---

## Group B: API Layer

### Task B1: Create JWT token utility

**Files:**
- Create: `AIagent_frontend/src/utils/token.js`

- [ ] **Step 1: Write token.js**

File: `AIagent_frontend/src/utils/token.js`
```js
const TOKEN_KEY = 'ai_agent_token'
const EXPIRY_KEY = 'ai_agent_token_expiry'

export function getToken() {
  const token = localStorage.getItem(TOKEN_KEY)
  const expiry = localStorage.getItem(EXPIRY_KEY)
  if (!token) return null
  if (expiry && Date.now() > Number(expiry)) {
    clearToken()
    return null
  }
  return token
}

export function setToken(token, expiresInMs = 24 * 60 * 60 * 1000) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(EXPIRY_KEY, String(Date.now() + expiresInMs))
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(EXPIRY_KEY)
}

export function isTokenValid() {
  return !!getToken()
}
```

### Task B2: Rewrite API client

**Files:**
- Rewrite: `AIagent_frontend/src/api/client.js`

- [ ] **Step 1: Rewrite client.js with JWT + workspace interceptor**

File: `AIagent_frontend/src/api/client.js`
```js
import axios from 'axios'
import { getToken, clearToken } from '@/utils/token'

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || undefined,
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' }
})

// Request interceptor — attach auth + workspace headers
apiClient.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  const wsId = localStorage.getItem('ai_agent_workspace_id')
  if (wsId) {
    config.headers['X-Workspace-Id'] = wsId
  }
  return config
})

// Response interceptor — unwrap envelope, handle auth errors
apiClient.interceptors.response.use(
  (response) => {
    // Check for new token header
    const newToken = response.headers['x-new-token']
    if (newToken) {
      const { setToken } = require('@/utils/token')
      setToken(newToken)
    }

    const data = response.data

    // Success: code === 200
    if (data && data.code === 200) {
      return data.data
    }

    // Application error
    const err = new Error(data?.message || 'Request failed')
    err.code = data?.code
    return Promise.reject(err)
  },
  (error) => {
    if (error.response?.status === 401) {
      clearToken()
      localStorage.removeItem('ai_agent_workspace_id')
      window.location.hash = '#/login'
      return Promise.reject(error)
    }

    const msg = error.message || ''
    const isNetworkError =
      msg.includes('Network Error') ||
      msg.includes('Failed to fetch') ||
      !error.response

    if (isNetworkError) {
      // Connection lost — will be handled by StatusBar/chat store
      error.isNetworkError = true
    }

    return Promise.reject(error)
  }
)

export default apiClient
```

**Note:** The `require('@/utils/token')` dynamic import will be replaced with a direct import at the top once we refactor to avoid circular deps. Use ES module import:

Actually, fix this properly — import at the top:

```js
import axios from 'axios'
import { getToken, clearToken, setToken as saveToken } from '@/utils/token'

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || undefined,
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' }
})

apiClient.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  const wsId = localStorage.getItem('ai_agent_workspace_id')
  if (wsId) {
    config.headers['X-Workspace-Id'] = wsId
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => {
    const newToken = response.headers['x-new-token']
    if (newToken) {
      saveToken(newToken)
    }
    const data = response.data
    if (data && data.code === 200) {
      return data.data
    }
    const err = new Error(data?.message || 'Request failed')
    err.code = data?.code
    return Promise.reject(err)
  },
  (error) => {
    if (error.response?.status === 401) {
      clearToken()
      localStorage.removeItem('ai_agent_workspace_id')
      window.location.hash = '#/login'
      return Promise.reject(error)
    }
    const msg = error.message || ''
    const isNetworkError =
      msg.includes('Network Error') ||
      msg.includes('Failed to fetch') ||
      !error.response
    if (isNetworkError) {
      error.isNetworkError = true
    }
    return Promise.reject(error)
  }
)

export default apiClient
```

### Task B3: Create API modules

**Files:**
- Create: `AIagent_frontend/src/api/auth.js`
- Create: `AIagent_frontend/src/api/workspaces.js`
- Rewrite: `AIagent_frontend/src/api/agents.js`
- Create: `AIagent_frontend/src/api/conversations.js`
- Rewrite: `AIagent_frontend/src/api/chat.js`
- Create: `AIagent_frontend/src/api/messages.js`

- [ ] **Step 1: Create api/auth.js**

File: `AIagent_frontend/src/api/auth.js`
```js
import apiClient from './client'

/** POST /api/v1/auth/login */
export function login(username, password) {
  return apiClient.post('/api/v1/auth/login', { username, password })
}

/** GET /api/v1/auth/me */
export function getProfile() {
  return apiClient.get('/api/v1/auth/me')
}

/** PUT /api/v1/auth/me */
export function updateProfile(body) {
  return apiClient.put('/api/v1/auth/me', body)
}

/** PUT /api/v1/auth/users/{id}/password */
export function changePassword(id, oldPassword, newPassword) {
  return apiClient.put(`/api/v1/auth/users/${id}/password`, { oldPassword, newPassword })
}
```

- [ ] **Step 2: Create api/workspaces.js**

File: `AIagent_frontend/src/api/workspaces.js`
```js
import apiClient from './client'

/** GET /api/v1/workspaces */
export function fetchWorkspaces() {
  return apiClient.get('/api/v1/workspaces')
}
```

- [ ] **Step 3: Rewrite api/agents.js**

File: `AIagent_frontend/src/api/agents.js`
```js
import apiClient from './client'

/** GET /api/v1/agents */
export function fetchAgents(params = {}) {
  return apiClient.get('/api/v1/agents', { params })
}

/** GET /api/v1/agents/{id} */
export function fetchAgentDetail(id) {
  return apiClient.get(`/api/v1/agents/${id}`)
}

/** POST /api/v1/agents */
export function createAgent(body) {
  return apiClient.post('/api/v1/agents', body)
}

/** PUT /api/v1/agents/{id} */
export function updateAgent(id, body) {
  return apiClient.put(`/api/v1/agents/${id}`, body)
}

/** DELETE /api/v1/agents/{id} */
export function deleteAgent(id) {
  return apiClient.delete(`/api/v1/agents/${id}`)
}
```

- [ ] **Step 4: Create api/conversations.js**

File: `AIagent_frontend/src/api/conversations.js`
```js
import apiClient from './client'

/** GET /api/v1/conversations */
export function fetchConversations(params = {}) {
  return apiClient.get('/api/v1/conversations', { params })
}

/** GET /api/v1/conversations/page */
export function fetchConversationsPage(params = {}) {
  return apiClient.get('/api/v1/conversations/page', { params })
}

/** GET /api/v1/conversations/{id} */
export function fetchConversationDetail(id) {
  return apiClient.get(`/api/v1/conversations/${id}`)
}

/** GET /api/v1/conversations/{id}/messages */
export function fetchMessages(id, params = {}) {
  return apiClient.get(`/api/v1/conversations/${id}/messages`, { params })
}

/** DELETE /api/v1/conversations/{id} */
export function deleteConversation(id) {
  return apiClient.delete(`/api/v1/conversations/${id}`)
}

/** PUT /api/v1/conversations/{id}/title */
export function updateConversationTitle(id, title) {
  return apiClient.put(`/api/v1/conversations/${id}/title`, { title })
}

/** PUT /api/v1/conversations/{id}/pin */
export function toggleConversationPin(id, pinned) {
  return apiClient.put(`/api/v1/conversations/${id}/pin`, { pinned })
}

/** PUT /api/v1/conversations/{id}/archive */
export function toggleConversationArchive(id, archived) {
  return apiClient.put(`/api/v1/conversations/${id}/archive`, { archived })
}

/** POST /api/v1/conversations/group */
export function createGroupConversation(body) {
  return apiClient.post('/api/v1/conversations/group', body)
}

/** GET /api/v1/conversations/{id}/pins */
export function fetchPinnedMessages(id) {
  return apiClient.get(`/api/v1/conversations/${id}/pins`)
}

/** POST /api/v1/conversations/{id}/pins */
export function pinMessage(conversationId, messageId, note) {
  return apiClient.post(`/api/v1/conversations/${conversationId}/pins`, { messageId, note })
}

/** DELETE /api/v1/conversations/{id}/pins/{messageId} */
export function unpinMessage(conversationId, messageId) {
  return apiClient.delete(`/api/v1/conversations/${conversationId}/pins/${messageId}`)
}
```

- [ ] **Step 5: Rewrite api/chat.js**

File: `AIagent_frontend/src/api/chat.js`
```js
import { getToken } from '@/utils/token'

const BASE = import.meta.env.VITE_API_BASE || '/api/v1'

/**
 * POST /api/v1/chat/stream
 * Returns a ReadableStream for SSE consumption.
 * Must use fetch directly (Axios doesn't support streaming).
 */
export function streamChat(body, signal) {
  const wsId = localStorage.getItem('ai_agent_workspace_id') || ''
  return fetch(`${BASE}/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getToken()}`,
      'X-Workspace-Id': wsId,
      Accept: 'text/event-stream'
    },
    body: JSON.stringify(body),
    signal
  })
}

/** POST /api/v1/chat/{conversationId}/stop */
export function stopChat(conversationId) {
  const apiClient = require('./client').default
  return apiClient.post(`/api/v1/chat/${conversationId}/stop`)
}

/** POST /api/v1/chat/{conversationId}/interrupt */
export function interruptChat(conversationId, message) {
  const apiClient = require('./client').default
  return apiClient.post(`/api/v1/chat/${conversationId}/interrupt`, { message })
}
```

**Fix:** Replace require() with ES import:

```js
import { getToken } from '@/utils/token'
import apiClient from './client'

const BASE = ''

export function streamChat(body, signal) {
  const wsId = localStorage.getItem('ai_agent_workspace_id') || ''
  const token = getToken()
  return fetch(`${BASE}/api/v1/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'X-Workspace-Id': wsId,
      Accept: 'text/event-stream'
    },
    body: JSON.stringify(body),
    signal
  })
}

export function stopChat(conversationId) {
  return apiClient.post(`/api/v1/chat/${conversationId}/stop`)
}

export function interruptChat(conversationId, message) {
  return apiClient.post(`/api/v1/chat/${conversationId}/interrupt`, { message })
}
```

- [ ] **Step 6: Create api/messages.js**

File: `AIagent_frontend/src/api/messages.js`
```js
import apiClient from './client'

/** GET /api/v1/messages/{id} */
export function fetchMessageDetail(id) {
  return apiClient.get(`/api/v1/messages/${id}`)
}

/** POST /api/v1/messages/{id}/regenerate */
export function regenerateMessage(id, message) {
  return apiClient.post(`/api/v1/messages/${id}/regenerate`, { message })
}

/** GET /api/v1/messages/{id}/reactions */
export function fetchReactions(id) {
  return apiClient.get(`/api/v1/messages/${id}/reactions`)
}

/** POST /api/v1/messages/{id}/reactions */
export function addReaction(id, reactionType) {
  return apiClient.post(`/api/v1/messages/${id}/reactions`, { reactionType })
}

/** DELETE /api/v1/messages/{id}/reactions/{reactionType} */
export function removeReaction(id, reactionType) {
  return apiClient.delete(`/api/v1/messages/${id}/reactions/${reactionType}`)
}

/** GET /api/v1/messages/{id}/reply-chain */
export function fetchReplyChain(id) {
  return apiClient.get(`/api/v1/messages/${id}/reply-chain`)
}
```

- [ ] **Step 7: Verify API layer compiles**

Run: `cd AIagent_frontend && npx vite build --mode development 2>&1 | head -20`
Expected: No import errors. May fail on store rewrites (expected, stores not yet written).

---

## Group C: Auth System

### Task C1: Rewrite auth store

**Files:**
- Rewrite: `AIagent_frontend/src/stores/auth.js`

- [ ] **Step 1: Rewrite auth.js with JWT**

File: `AIagent_frontend/src/stores/auth.js`
```js
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getToken, setToken, clearToken, isTokenValid } from '@/utils/token'
import { login as loginApi, getProfile } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const userId = ref(null)
  const username = ref('')
  const nickname = ref('')
  const role = ref('')
  const isChecking = ref(true)
  const loginError = ref('')

  const isLoggedIn = computed(() => !!userId.value && isTokenValid())

  function checkLogin() {
    const token = getToken()
    if (!token) {
      isChecking.value = false
      return false
    }
    // Try to restore session from stored data
    const stored = localStorage.getItem('ai_agent_user')
    if (stored) {
      try {
        const u = JSON.parse(stored)
        userId.value = u.userId
        username.value = u.username
        nickname.value = u.nickname
        role.value = u.role
      } catch { /* ignore corrupt data */ }
    }
    isChecking.value = false
    return isTokenValid()
  }

  async function login(usernameInput, password) {
    loginError.value = ''
    try {
      const data = await loginApi(usernameInput.trim(), password)
      setToken(data.token)
      localStorage.setItem('ai_agent_user', JSON.stringify({
        userId: data.userId,
        username: data.username,
        nickname: data.nickname,
        role: data.role
      }))
      userId.value = data.userId
      username.value = data.username
      nickname.value = data.nickname
      role.value = data.role
      return { ok: true }
    } catch (err) {
      loginError.value = err.message || 'Login failed'
      return { ok: false, error: err.message || 'Login failed' }
    }
  }

  async function refreshProfile() {
    try {
      const data = await getProfile()
      nickname.value = data.nickname
      role.value = data.role
      localStorage.setItem('ai_agent_user', JSON.stringify({
        userId: userId.value,
        username: username.value,
        nickname: data.nickname,
        role: data.role
      }))
    } catch { /* silent fail */ }
  }

  function logout() {
    clearToken()
    localStorage.removeItem('ai_agent_user')
    localStorage.removeItem('ai_agent_workspace_id')
    userId.value = null
    username.value = ''
    nickname.value = ''
    role.value = ''
  }

  return {
    userId, username, nickname, role,
    isChecking, loginError, isLoggedIn,
    checkLogin, login, logout, refreshProfile
  }
})
```

### Task C2: Create workspace store

**Files:**
- Create: `AIagent_frontend/src/stores/workspace.js`

- [ ] **Step 1: Write workspace store**

File: `AIagent_frontend/src/stores/workspace.js`
```js
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchWorkspaces } from '@/api/workspaces'

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaces = ref([])
  const activeId = ref(null)

  const activeWorkspace = computed(() =>
    workspaces.value.find(w => w.id === activeId.value) || null
  )

  async function loadAndSelect() {
    const stored = localStorage.getItem('ai_agent_workspace_id')
    try {
      const data = await fetchWorkspaces()
      // Response may be an array or { records: [...] }
      const list = Array.isArray(data) ? data : (data?.records || [])
      workspaces.value = list
      if (list.length > 0) {
        // Restore stored or pick first
        const id = stored && list.some(w => String(w.id) === String(stored))
          ? Number(stored)
          : list[0].id
        activeId.value = id
        localStorage.setItem('ai_agent_workspace_id', String(id))
      }
    } catch {
      // If no workspaces, workspace 1 as fallback
      if (stored) {
        activeId.value = Number(stored)
      }
    }
  }

  return { workspaces, activeId, activeWorkspace, loadAndSelect }
})
```

### Task C3: Refactor LoginView

**Files:**
- Refactor: `AIagent_frontend/src/views/LoginView.vue`
- Keep: `AIagent_frontend/src/components/login/LoginForm.vue` (may need minor refactor)
- Keep: `AIagent_frontend/src/components/login/HeroSection.vue`

- [ ] **Step 1: Refactor LoginView.vue to use real auth**

The LoginView currently uses the old hardcoded login. Update to call the new auth store's `login()` action, show real error messages, and redirect on success.

File: `AIagent_frontend/src/views/LoginView.vue`
```vue
<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import HeroSection from '@/components/login/HeroSection.vue'
import LoginForm from '@/components/login/LoginForm.vue'

const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()

const error = ref('')
const loading = ref(false)

async function handleLogin({ username, password }) {
  error.value = ''
  loading.value = true
  try {
    const result = await authStore.login(username, password)
    if (result.ok) {
      // Load workspaces after login
      await workspaceStore.loadAndSelect()
      router.replace({ name: 'Chat' })
    } else {
      error.value = result.error || 'Login failed'
    }
  } catch (err) {
    error.value = err.message || 'Network error'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-view">
    <HeroSection />
    <LoginForm
      :error="error"
      :loading="loading"
      @submit="handleLogin"
    />
  </div>
</template>

<style scoped>
.login-view {
  display: flex;
  min-height: 100vh;
  background: var(--bg-primary, #f5f5f7);
}
</style>
```

- [ ] **Step 2: Verify LoginForm emits the right event shape**

Read existing LoginForm to check it emits `{ username, password }` on submit. If the existing LoginForm directly calls the old auth store, refactor it to emit an event instead.

File: `AIagent_frontend/src/components/login/LoginForm.vue` — update its `<script setup>` to emit:

```vue
<script setup>
import { ref } from 'vue'

const props = defineProps({
  error: { type: String, default: '' },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['submit'])

const username = ref('')
const password = ref('')

function onSubmit() {
  emit('submit', { username: username.value, password: password.value })
}
</script>
```

(Keep existing template and styles unchanged — only refactor the script section.)

### Task C4: Update router guards

**Files:**
- Extend: `AIagent_frontend/src/router/index.js`

- [ ] **Step 1: Extend router with new routes and JWT guards**

File: `AIagent_frontend/src/router/index.js`
```js
import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/',
    redirect: '/chat'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { requiresGuest: true }
  },
  {
    path: '/chat/:conversationId?',
    name: 'Chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/agents',
    name: 'Agents',
    component: () => import('@/views/AgentManageView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/agents/:id',
    name: 'AgentDetail',
    component: () => import('@/views/AgentDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/artifacts',
    name: 'Artifacts',
    component: () => import('@/views/ArtifactListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/artifacts/:id',
    name: 'ArtifactDetail',
    component: () => import('@/views/ArtifactDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/chat'
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach((to, from, next) => {
  const auth = useAuthStore()

  if (auth.isChecking) {
    auth.checkLogin()
  }

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return next({ name: 'Login', replace: true })
  }

  if (to.meta.requiresGuest && auth.isLoggedIn) {
    return next({ name: 'Chat', replace: true })
  }

  next()
})

export default router
```

**Note:** The views referenced by lazy imports (AgentManageView, AgentDetailView, ArtifactListView, ArtifactDetailView, SettingsView) don't exist yet. Create placeholder `.vue` files for each so the router doesn't fail:

- [ ] **Step 2: Create placeholder views for routes not yet built**

Each placeholder is a minimal Vue component:

`AIagent_frontend/src/views/AgentManageView.vue`:
```vue
<template><div class="page-placeholder">Agent Manage — Coming Soon</div></template>
```

`AIagent_frontend/src/views/AgentDetailView.vue`:
```vue
<template><div class="page-placeholder">Agent Detail — Coming Soon</div></template>
```

`AIagent_frontend/src/views/ArtifactListView.vue`:
```vue
<template><div class="page-placeholder">Artifacts — Coming Soon</div></template>
```

`AIagent_frontend/src/views/ArtifactDetailView.vue`:
```vue
<template><div class="page-placeholder">Artifact Detail — Coming Soon</div></template>
```

`AIagent_frontend/src/views/SettingsView.vue`:
```vue
<template><div class="page-placeholder">Settings — Coming Soon</div></template>
```

---

## Group D: Conversation Infrastructure

### Task D1: Create conversation store

**Files:**
- Create: `AIagent_frontend/src/stores/conversation.js`

- [ ] **Step 1: Write conversation store**

File: `AIagent_frontend/src/stores/conversation.js`
```js
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  fetchConversations, fetchConversationDetail, fetchMessages,
  deleteConversation as deleteConvApi,
  toggleConversationPin, toggleConversationArchive,
  updateConversationTitle,
  createGroupConversation
} from '@/api/conversations'

export const useConversationStore = defineStore('conversation', () => {
  const conversations = ref([])
  const activeId = ref(null)
  const loading = ref(false)
  const searchKeyword = ref('')
  const filter = ref('all') // 'all' | 'direct' | 'group'

  const activeConversation = computed(() =>
    conversations.value.find(c => c.id === activeId.value) || null
  )

  const sortedConversations = computed(() => {
    const list = [...conversations.value]
    // Pinned first, then by lastActiveAt
    list.sort((a, b) => {
      if (a.pinnedAt && !b.pinnedAt) return -1
      if (!a.pinnedAt && b.pinnedAt) return 1
      const aTime = a.lastActiveAt || ''
      const bTime = b.lastActiveAt || ''
      return bTime.localeCompare(aTime)
    })
    return list
  })

  const filteredConversations = computed(() => {
    let list = sortedConversations.value
    if (filter.value === 'direct') list = list.filter(c => c.conversationType === 'direct')
    if (filter.value === 'group') list = list.filter(c => c.conversationType === 'group')
    if (searchKeyword.value) {
      const kw = searchKeyword.value.toLowerCase()
      list = list.filter(c =>
        (c.title || '').toLowerCase().includes(kw) ||
        (c.agentName || '').toLowerCase().includes(kw)
      )
    }
    return list
  })

  const unreadTotal = computed(() =>
    conversations.value.reduce((sum, c) => sum + (c.unreadCount || 0), 0)
  )

  async function loadList() {
    loading.value = true
    try {
      const params = {}
      if (filter.value !== 'all') params.conversationType = filter.value
      const data = await fetchConversations(params)
      conversations.value = Array.isArray(data) ? data : (data?.records || [])
    } finally {
      loading.value = false
    }
  }

  function setActive(id) {
    activeId.value = id
  }

  async function togglePin(id) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    const newState = !conv.pinnedAt
    await toggleConversationPin(id, newState)
    conv.pinnedAt = newState ? new Date().toISOString() : null
  }

  async function toggleArchive(id) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    const newState = !conv.archived
    await toggleConversationArchive(id, newState)
    conv.archived = newState
  }

  async function deleteConversation(id) {
    await deleteConvApi(id)
    conversations.value = conversations.value.filter(c => c.id !== id)
    if (activeId.value === id) {
      activeId.value = null
    }
  }

  async function createGroup(config) {
    const data = await createGroupConversation(config)
    await loadList()
    return data
  }

  return {
    conversations, activeId, loading, searchKeyword, filter,
    activeConversation, sortedConversations, filteredConversations, unreadTotal,
    loadList, setActive, togglePin, toggleArchive, deleteConversation, createGroup
  }
})
```

### Task D2: Create ConversationSidebar component

**Files:**
- Create: `AIagent_frontend/src/components/chat/ConversationSidebar.vue`

- [ ] **Step 1: Write ConversationSidebar.vue**

File: `AIagent_frontend/src/components/chat/ConversationSidebar.vue`
```vue
<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useConversationStore } from '@/stores/conversation'
import { useAgentStore } from '@/stores/agent'
import { NAvatar, NTag, NBadge, NInput, NButton, NPopconfirm, NDropdown, NSpace, NSpin } from 'naive-ui'

const router = useRouter()
const convStore = useConversationStore()
const agentStore = useAgentStore()

onMounted(async () => {
  await Promise.all([convStore.loadList(), agentStore.loadAgents()])
})

function selectConversation(id) {
  convStore.setActive(id)
  router.push(`/chat/${id}`)
}

function newDirectChat() {
  convStore.setActive(null)
  router.push('/chat')
}

function handlePin(id) {
  convStore.togglePin(id)
}

function handleArchive(id) {
  convStore.toggleArchive(id)
}

function handleDelete(id) {
  convStore.deleteConversation(id)
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const diffMs = now - d
  if (diffMs < 60000) return '刚刚'
  if (diffMs < 3600000) return `${Math.floor(diffMs / 60000)}分钟前`
  if (diffMs < 86400000) return `${Math.floor(diffMs / 3600000)}小时前`
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

function contextMenuOptions(conv) {
  return [
    { label: conv.pinnedAt ? '取消置顶' : '置顶', key: 'pin' },
    { label: conv.archived ? '取消归档' : '归档', key: 'archive' },
    { label: '删除', key: 'delete' }
  ]
}

function handleContextMenu(key, conv) {
  if (key === 'pin') handlePin(conv.id)
  else if (key === 'archive') handleArchive(conv.id)
  else if (key === 'delete') handleDelete(conv.id)
}
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-header">
      <NSpace vertical :size="8" style="width: 100%">
        <NButton type="primary" block @click="newDirectChat">
          + 新建对话
        </NButton>
        <NInput
          v-model:value="convStore.searchKeyword"
          placeholder="搜索对话..."
          clearable
          size="small"
        />
      </NSpace>
    </div>

    <div class="sidebar-list">
      <NSpin :show="convStore.loading">
        <div
          v-for="conv in convStore.filteredConversations"
          :key="conv.id"
          class="conv-item"
          :class="{
            active: conv.id === convStore.activeId,
            pinned: conv.pinnedAt
          }"
          @click="selectConversation(conv.id)"
        >
          <NDropdown
            trigger="manual"
            :options="contextMenuOptions(conv)"
            @select="(key) => handleContextMenu(key, conv)"
          >
            <div class="conv-content">
              <NAvatar
                :size="40"
                :src="conv.agentAvatarUrl"
                round
              >
                {{ (conv.title || conv.agentName || '?')[0] }}
              </NAvatar>
              <div class="conv-info">
                <div class="conv-top">
                  <span class="conv-title">
                    {{ conv.title || conv.agentName || '未命名对话' }}
                  </span>
                  <span class="conv-time">{{ formatTime(conv.lastActiveAt) }}</span>
                </div>
                <div class="conv-bottom">
                  <span class="conv-preview">{{ conv.lastMessagePreview || '暂无消息' }}</span>
                  <NBadge
                    v-if="conv.unreadCount > 0"
                    :value="conv.unreadCount"
                    :max="99"
                    type="error"
                    size="tiny"
                  />
                  <NTag
                    v-if="conv.conversationType === 'group'"
                    size="tiny"
                    :bordered="false"
                  >
                    群聊
                  </NTag>
                </div>
              </div>
            </div>
          </NDropdown>
        </div>

        <div v-if="!convStore.loading && convStore.filteredConversations.length === 0" class="empty-list">
          暂无对话
        </div>
      </NSpin>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 320px;
  min-width: 260px;
  max-width: 400px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #F5F5F7;
  border-right: 1px solid #E5E5EA;
  overflow: hidden;
}

.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid #E5E5EA;
}

.sidebar-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.conv-item {
  padding: 10px 12px;
  border-radius: 14px;
  cursor: pointer;
  transition: background 0.15s;
  margin-bottom: 2px;
}

.conv-item:hover {
  background: rgba(0,0,0,0.04);
}

.conv-item.active {
  background: rgba(46,117,182,0.1);
}

.conv-content {
  display: flex;
  align-items: center;
  gap: 12px;
}

.conv-info {
  flex: 1;
  min-width: 0;
}

.conv-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 2px;
}

.conv-title {
  font-size: 14px;
  font-weight: 500;
  color: #1D1D1F;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  font-size: 11px;
  color: #999;
  flex-shrink: 0;
  margin-left: 8px;
}

.conv-bottom {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conv-preview {
  font-size: 12px;
  color: #999;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.empty-list {
  text-align: center;
  color: #999;
  padding: 40px 0;
  font-size: 14px;
}
</style>
```

---

## Group E: Chat Core

### Task E1: Create SSE composable

**Files:**
- Create: `AIagent_frontend/src/composables/useSSE.js`

- [ ] **Step 1: Write useSSE.js**

File: `AIagent_frontend/src/composables/useSSE.js`
```js
import { ref, onUnmounted } from 'vue'

/**
 * SSE client using fetch + ReadableStream.
 * Parses SSE text protocol and calls registered callbacks per event type.
 */
export function useSSE() {
  const isConnected = ref(false)
  const error = ref(null)
  const lastEventId = ref(null)

  let abortController = null
  let reader = null
  const listeners = new Map() // eventType → Set<callback>

  function on(eventType, callback) {
    if (!listeners.has(eventType)) {
      listeners.set(eventType, new Set())
    }
    listeners.get(eventType).add(callback)
    return () => listeners.get(eventType)?.delete(callback)
  }

  function emit(eventType, data) {
    const cbs = listeners.get(eventType)
    if (cbs) {
      cbs.forEach(cb => {
        try { cb(data) } catch (e) { console.warn('SSE listener error:', e) }
      })
    }
    // Also emit to '*' catch-all
    const all = listeners.get('*')
    if (all) {
      all.forEach(cb => {
        try { cb(eventType, data) } catch (e) { console.warn('SSE listener error:', e) }
      })
    }
  }

  async function connect(fetchFn, options = {}) {
    const { onDisconnect } = options
    disconnect()

    abortController = new AbortController()
    isConnected.value = true
    error.value = null

    try {
      const response = await fetchFn(abortController.signal)

      if (!response.ok) {
        throw new Error(`SSE connection failed: ${response.status}`)
      }

      const contentType = response.headers.get('content-type') || ''
      if (contentType.includes('application/json')) {
        // Non-streaming response — treat as single done event
        const json = await response.json()
        emit('done', json)
        return
      }

      reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // Parse SSE frames
        const parts = buffer.split('\n\n')
        buffer = parts.pop() // Keep incomplete frame for next iteration

        for (const part of parts) {
          if (!part.trim()) continue
          const lines = part.split('\n')
          let eventType = 'message'
          let dataStr = ''

          for (const line of lines) {
            if (line.startsWith('event: ')) {
              eventType = line.slice(7).trim()
            } else if (line.startsWith('data: ')) {
              dataStr = line.slice(6)
            } else if (line.startsWith('id: ')) {
              lastEventId.value = line.slice(4).trim()
            }
          }

          if (dataStr) {
            try {
              const data = JSON.parse(dataStr)
              emit(eventType, data)
            } catch {
              emit(eventType, dataStr)
            }
          }
        }
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        // Intentional disconnect — no error
      } else {
        error.value = err.message
        emit('error', { message: err.message })
        if (onDisconnect) onDisconnect(err)
      }
    } finally {
      isConnected.value = false
      reader = null
    }
  }

  function disconnect() {
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    isConnected.value = false
  }

  onUnmounted(() => {
    disconnect()
  })

  return { isConnected, error, lastEventId, on, emit, connect, disconnect }
}
```

### Task E2: Create markdown composable

**Files:**
- Create: `AIagent_frontend/src/composables/useMarkdown.js`

- [ ] **Step 1: Write useMarkdown.js**

File: `AIagent_frontend/src/composables/useMarkdown.js`
```js
import { marked } from 'marked'
import DOMPurify from 'dompurify'

// Configure marked
marked.setOptions({
  breaks: true,
  gfm: true
})

export function renderMarkdown(text) {
  if (!text) return ''
  const raw = marked.parse(text)
  return DOMPurify.sanitize(raw)
}
```

### Task E3: Rewrite chat store

**Files:**
- Rewrite: `AIagent_frontend/src/stores/chat.js`

- [ ] **Step 1: Write SSE-driven chat store**

File: `AIagent_frontend/src/stores/chat.js`
```js
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { streamChat, stopChat, interruptChat } from '@/api/chat'
import { fetchMessages } from '@/api/conversations'
import { addReaction, regenerateMessage } from '@/api/messages'
import { useSSE } from '@/composables/useSSE'

let msgIdCounter = 0
function nextLocalId() {
  return `local_${++msgIdCounter}_${Date.now()}`
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const conversationId = ref(null)
  const isStreaming = ref(false)
  const streamError = ref('')
  const currentTurnId = ref(null)
  const hasMoreHistory = ref(false)
  const nextBeforeId = ref(null)

  // SSE instance — created per conversation
  let sse = null

  const isEmpty = computed(() => messages.value.length === 0)

  function addMessageLocal(role, content, extra = {}) {
    const id = nextLocalId()
    messages.value.push({
      id,
      role,
      content,
      messageType: extra.messageType || 'text',
      senderAgentId: extra.senderAgentId || null,
      senderAgentName: extra.senderAgentName || null,
      senderAgentAvatarUrl: extra.senderAgentAvatarUrl || null,
      artifactRefs: extra.artifactRefs || null,
      replyToId: extra.replyToId || null,
      status: extra.status || 'pending',
      tokenUsage: null,
      createTime: new Date().toISOString(),
      ...extra
    })
    return id
  }

  function updateMessage(id, updates) {
    const idx = messages.value.findIndex(m => m.id === id)
    if (idx !== -1) {
      messages.value[idx] = { ...messages.value[idx], ...updates }
    }
  }

  async function initConversation(convId) {
    conversationId.value = convId
    messages.value = []

    if (!convId) return

    try {
      const data = await fetchMessages(convId, { limit: 50 })
      const records = data?.records || []
      messages.value = records.reverse() // oldest first
      hasMoreHistory.value = data?.hasMore || false
      nextBeforeId.value = data?.nextBeforeId || null
    } catch (err) {
      console.warn('Failed to load message history:', err)
    }
  }

  async function loadMoreHistory() {
    if (!hasMoreHistory.value || !nextBeforeId.value || !conversationId.value) return

    try {
      const data = await fetchMessages(conversationId.value, {
        beforeId: nextBeforeId.value,
        limit: 50
      })
      const records = data?.records || []
      messages.value = [...records.reverse(), ...messages.value]
      hasMoreHistory.value = data?.hasMore || false
      nextBeforeId.value = data?.nextBeforeId || null
    } catch (err) {
      console.warn('Failed to load more history:', err)
    }
  }

  async function sendMessage(text, agentId) {
    if (!text.trim() || isStreaming.value) return

    streamError.value = ''

    // Add user message
    addMessageLocal('user', text)

    // Add assistant placeholder
    const assistantId = addMessageLocal('assistant', '', { status: 'streaming' })

    isStreaming.value = true

    // Setup SSE
    sse = useSSE()

    sse.on('text', (data) => {
      const msg = messages.value.find(m => m.id === assistantId)
      if (msg) {
        updateMessage(assistantId, { content: (msg.content || '') + (data.delta || '') })
      }
    })

    sse.on('tool_call', (data) => {
      // Append tool call indicator
      const msg = messages.value.find(m => m.id === assistantId)
      if (msg) {
        const indicator = `\n\n> 🔧 调用工具: **${data.toolName}**...\n`
        updateMessage(assistantId, { content: (msg.content || '') + indicator })
      }
    })

    sse.on('tool_result', (data) => {
      const msg = messages.value.find(m => m.id === assistantId)
      if (msg) {
        const result = data.output
          ? `\n> ✅ 工具结果: ${data.output.slice(0, 200)}${data.output.length > 200 ? '...' : ''}\n`
          : '\n> ✅ 工具执行完成\n'
        updateMessage(assistantId, { content: (msg.content || '') + result })
      }
    })

    sse.on('done', (data) => {
      updateMessage(assistantId, {
        status: 'completed',
        tokenUsage: data.tokenUsage || null
      })
      currentTurnId.value = data.turnId || null
      isStreaming.value = false
      sse.disconnect()
    })

    sse.on('error', (data) => {
      streamError.value = data.message || 'Stream error'
      updateMessage(assistantId, { status: 'error' })
      isStreaming.value = false
      sse.disconnect()
    })

    sse.connect((signal) => streamChat({
      agentId,
      message: text,
      conversationId: conversationId.value || null
    }, signal))
  }

  function stopGeneration() {
    if (sse) {
      sse.disconnect()
      isStreaming.value = false
    }
    if (conversationId.value) {
      stopChat(conversationId.value).catch(() => {})
    }
  }

  async function handleReaction(messageId, reactionType) {
    // Find the actual server-side message ID
    const msg = messages.value.find(m => m.id === messageId)
    if (!msg || msg.role !== 'assistant') return

    // If it's a local ID, we need the server ID — use conversation lookup
    try {
      await addReaction(messageId, reactionType)
    } catch (err) {
      console.warn('Reaction failed:', err)
    }
  }

  async function handleRegenerate(messageId) {
    const msg = messages.value.find(m => m.id === messageId)
    if (!msg) return

    try {
      await regenerateMessage(messageId)
      // The backend will generate a new message; reload conversation
      if (conversationId.value) {
        await initConversation(conversationId.value)
      }
    } catch (err) {
      console.warn('Regenerate failed:', err)
    }
  }

  function clearMessages() {
    messages.value = []
    streamError.value = ''
    if (sse) sse.disconnect()
    isStreaming.value = false
  }

  return {
    messages, conversationId, isStreaming, streamError,
    currentTurnId, hasMoreHistory, isEmpty,
    initConversation, loadMoreHistory, sendMessage, stopGeneration,
    handleReaction, handleRegenerate, clearMessages,
    addMessageLocal, updateMessage
  }
})
```

### Task E4: Rewrite MessageBubble

**Files:**
- Rewrite: `AIagent_frontend/src/components/chat/MessageBubble.vue`

- [ ] **Step 1: Write multi-type MessageBubble**

File: `AIagent_frontend/src/components/chat/MessageBubble.vue`
```vue
<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '@/composables/useMarkdown'
import { NAvatar, NButton, NCode, NSpace, NTag, NPopover } from 'naive-ui'

const props = defineProps({
  message: { type: Object, required: true }
})

const emit = defineEmits(['regenerate', 'reaction'])

const isUser = computed(() => props.message.role === 'user')
const isStreaming = computed(() => props.message.status === 'streaming')
const isError = computed(() => props.message.status === 'error')

const renderedContent = computed(() => {
  if (props.message.messageType === 'text' || props.message.messageType === 'system') {
    return renderMarkdown(props.message.content || '')
  }
  return ''
})

const codeLanguage = computed(() => {
  // Extract language from code blocks if present
  const match = (props.message.content || '').match(/^```(\w+)/)
  return match ? match[1] : 'text'
})

const codeContent = computed(() => {
  let content = props.message.content || ''
  // Strip code fences
  content = content.replace(/^```\w*\n?/, '').replace(/\n?```$/, '')
  return content
})
</script>

<template>
  <div class="message-row" :class="{ 'is-user': isUser }">
    <!-- Agent avatar (left side) -->
    <NAvatar
      v-if="!isUser"
      :size="32"
      :src="message.senderAgentAvatarUrl"
      round
      class="msg-avatar"
    >
      {{ (message.senderAgentName || 'AI')[0] }}
    </NAvatar>

    <!-- Message body -->
    <div class="msg-body" :class="[`msg-type-${message.messageType}`]">
      <!-- Agent name tag for group chat -->
      <div v-if="!isUser && message.senderAgentName" class="msg-agent-name">
        {{ message.senderAgentName }}
        <NTag v-if="message.senderAgentId" size="tiny" :bordered="false" style="margin-left: 4px">
          Agent
        </NTag>
      </div>

      <!-- TEXT / SYSTEM message -->
      <div
        v-if="message.messageType === 'text' || message.messageType === 'system'"
        class="msg-text markdown-body"
        :class="{ 'is-system': message.messageType === 'system' }"
        v-html="renderedContent"
      />

      <!-- CODE message -->
      <div v-else-if="message.messageType === 'code'" class="msg-code">
        <NCode :code="codeContent" :language="codeLanguage" />
        <NButton size="tiny" quaternary @click="navigator.clipboard?.writeText(codeContent)">
          复制
        </NButton>
      </div>

      <!-- IMAGE message -->
      <div v-else-if="message.messageType === 'image'" class="msg-image">
        <img :src="message.content" alt="attachment" loading="lazy" @click="/* lightbox */" />
      </div>

      <!-- FILE message -->
      <div v-else-if="message.messageType === 'file'" class="msg-file">
        <span>📎 {{ message.content }}</span>
        <NButton size="tiny" quaternary>下载</NButton>
      </div>

      <!-- DIFF message — placeholder, Phase 3 full component -->
      <div v-else-if="message.messageType === 'diff'" class="msg-diff">
        <NCode :code="message.content" language="diff" />
      </div>

      <!-- PLAN_CARD — placeholder, Phase 2 full component -->
      <div v-else-if="message.messageType === 'plan_card'" class="msg-plan">
        <div class="plan-placeholder">📋 任务拆解计划 (实现中)</div>
      </div>

      <!-- PREVIEW_CARD — placeholder, Phase 3 full component -->
      <div v-else-if="message.messageType === 'preview_card'" class="msg-preview">
        <iframe v-if="message.content" :srcdoc="message.content" sandbox="allow-scripts" class="preview-iframe" />
      </div>

      <!-- Fallback -->
      <div v-else class="msg-text">
        {{ message.content }}
      </div>

      <!-- Status markers -->
      <div v-if="isStreaming" class="msg-status streaming">生成中...</div>
      <div v-else-if="isError" class="msg-status error">生成失败</div>

      <!-- Action buttons (assistant messages only, after completion) -->
      <div v-if="!isUser && !isStreaming && !isError" class="msg-actions">
        <NButton size="tiny" quaternary @click="navigator.clipboard?.writeText(message.content)">复制</NButton>
        <NButton size="tiny" quaternary @click="emit('regenerate', message.id)">重新生成</NButton>
        <NButton size="tiny" quaternary @click="emit('reaction', message.id, 'like')">👍</NButton>
      </div>

      <!-- Token usage -->
      <div v-if="message.tokenUsage" class="msg-tokens">
        {{ message.tokenUsage.totalTokens }} tokens
      </div>
    </div>

    <!-- User avatar spacer -->
    <div v-if="isUser" class="msg-avatar-spacer" />
  </div>
</template>

<style scoped>
.message-row {
  display: flex;
  gap: 12px;
  padding: 8px 16px;
  max-width: 900px;
  margin: 0 auto;
}

.message-row.is-user {
  flex-direction: row-reverse;
}

.msg-avatar {
  flex-shrink: 0;
  margin-top: 2px;
}

.msg-avatar-spacer {
  width: 32px;
  flex-shrink: 0;
}

.msg-body {
  flex: 1;
  min-width: 0;
}

.msg-agent-name {
  font-size: 12px;
  color: #666;
  margin-bottom: 4px;
}

.msg-text {
  background: #FFFFFF;
  border-radius: 14px;
  padding: 12px 16px;
  font-size: 14px;
  line-height: 1.6;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

.msg-text.is-system {
  border-left: 3px solid #2E75B6;
  background: #F0F4FA;
}

.is-user .msg-text {
  background: #2E75B6;
  color: #FFFFFF;
}

.msg-code {
  background: #FFFFFF;
  border-radius: 14px;
  padding: 12px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

.msg-image img {
  max-width: 320px;
  border-radius: 14px;
  cursor: pointer;
}

.msg-status {
  font-size: 12px;
  margin-top: 4px;
}

.msg-status.streaming {
  color: #2E75B6;
}

.msg-status.error {
  color: #FF3B30;
}

.msg-actions {
  display: flex;
  gap: 4px;
  margin-top: 4px;
  opacity: 0;
  transition: opacity 0.15s;
}

.message-row:hover .msg-actions {
  opacity: 1;
}

.msg-tokens {
  font-size: 11px;
  color: #999;
  margin-top: 2px;
}

.preview-iframe {
  width: 100%;
  min-height: 240px;
  border: 1px solid #E5E5EA;
  border-radius: 14px;
}

/* Markdown body styling */
.markdown-body :deep(pre) {
  background: #1D1D1F;
  color: #F5F5F7;
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 13px;
}

.markdown-body :deep(code) {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 13px;
}

.markdown-body :deep(blockquote) {
  border-left: 3px solid #2E75B6;
  padding-left: 12px;
  color: #666;
  margin: 8px 0;
}
</style>
```

### Task E5: Refactor Composer

**Files:**
- Refactor: `AIagent_frontend/src/components/chat/Composer.vue`

- [ ] **Step 1: Refactor Composer.vue**

File: `AIagent_frontend/src/components/chat/Composer.vue`
```vue
<script setup>
import { ref, watch, nextTick } from 'vue'
import { NButton, NInput } from 'naive-ui'
import { useTextareaAutosize } from '@/composables/useTextareaAutosize'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  placeholder: { type: String, default: '输入消息...' }
})

const emit = defineEmits(['send', 'stop'])

const text = ref('')
const { textarea } = useTextareaAutosize()

function handleSend() {
  const val = text.value.trim()
  if (!val || props.disabled) return
  emit('send', val)
  text.value = ''
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <div class="composer">
    <div class="composer-inner">
      <textarea
        ref="textarea"
        v-model="text"
        class="composer-input"
        :placeholder="placeholder"
        :disabled="disabled"
        rows="1"
        @keydown="handleKeydown"
      />
      <NButton
        v-if="!disabled"
        type="primary"
        :disabled="!text.trim()"
        @click="handleSend"
        class="send-btn"
      >
        发送
      </NButton>
      <NButton
        v-else
        type="error"
        @click="emit('stop')"
        class="send-btn"
      >
        停止
      </NButton>
    </div>
  </div>
</template>

<style scoped>
.composer {
  padding: 16px;
  background: #FFFFFF;
  border-top: 1px solid #E5E5EA;
}

.composer-inner {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  max-width: 900px;
  margin: 0 auto;
}

.composer-input {
  flex: 1;
  resize: none;
  border: 1px solid #E5E5EA;
  border-radius: 14px;
  padding: 10px 16px;
  font-size: 14px;
  line-height: 1.5;
  font-family: inherit;
  outline: none;
  background: #F5F5F7;
  transition: border-color 0.15s;
  min-height: 42px;
  max-height: 160px;
}

.composer-input:focus {
  border-color: #2E75B6;
  background: #FFFFFF;
}

.send-btn {
  flex-shrink: 0;
  border-radius: 14px;
  height: 42px;
}
</style>
```

### Task E6: Refactor StatusBar

**Files:**
- Refactor: `AIagent_frontend/src/components/chat/StatusBar.vue`

- [ ] **Step 1: Refactor StatusBar.vue**

File: `AIagent_frontend/src/components/chat/StatusBar.vue`
```vue
<script setup>
defineProps({
  isConnected: { type: Boolean, default: true },
  isStreaming: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
</script>

<template>
  <div v-if="!isConnected || error" class="status-bar" :class="{ error: !!error }">
    <span v-if="error" class="status-text">{{ error }}</span>
    <span v-else-if="!isConnected" class="status-text">连接已断开，正在重连...</span>
  </div>
</template>

<style scoped>
.status-bar {
  padding: 6px 16px;
  background: #FFF3CD;
  color: #856404;
  text-align: center;
  font-size: 12px;
}

.status-bar.error {
  background: #FFEBEE;
  color: #FF3B30;
}
</style>
```

---

## Group F: ChatView Assembly

### Task F1: Rewrite ChatView

**Files:**
- Rewrite: `AIagent_frontend/src/views/ChatView.vue`
- Refactor: `AIagent_frontend/src/components/chat/ChatArea.vue`

- [ ] **Step 1: Rewrite ChatView.vue — three-panel layout**

File: `AIagent_frontend/src/views/ChatView.vue`
```vue
<script setup>
import { watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useConversationStore } from '@/stores/conversation'
import { useChatStore } from '@/stores/chat'
import { useAgentStore } from '@/stores/agent'
import ConversationSidebar from '@/components/chat/ConversationSidebar.vue'
import ChatArea from '@/components/chat/ChatArea.vue'
import TopBar from '@/components/layout/TopBar.vue'

const route = useRoute()
const router = useRouter()
const convStore = useConversationStore()
const chatStore = useChatStore()
const agentStore = useAgentStore()

// When route changes, switch active conversation and load messages
watch(
  () => route.params.conversationId,
  async (id) => {
    const convId = id ? Number(id) : null
    convStore.setActive(convId)
    await chatStore.initConversation(convId)
  },
  { immediate: true }
)

function handleSendMessage(text) {
  // For a new conversation (no convId yet), we need an agent
  // The user should pick an agent from the empty state
  const agentId = convStore.activeConversation?.agentId || agentStore.selectedAgentId
  if (!agentId) {
    // Fallback: use first available agent
    if (agentStore.agents.length > 0) {
      agentStore.selectAgent(agentStore.agents[0].id)
      chatStore.sendMessage(text, agentStore.agents[0].id)
    }
    return
  }
  chatStore.sendMessage(text, agentId)
}

function handleStopGeneration() {
  chatStore.stopGeneration()
}
</script>

<template>
  <div class="chat-view">
    <ConversationSidebar />
    <div class="chat-main">
      <TopBar />
      <ChatArea
        :messages="chatStore.messages"
        :is-streaming="chatStore.isStreaming"
        :conversation="convStore.activeConversation"
        @send="handleSendMessage"
        @stop="handleStopGeneration"
        @regenerate="chatStore.handleRegenerate"
        @reaction="chatStore.handleReaction"
      />
    </div>
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  height: 100vh;
  background: #F5F5F7;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #FFFFFF;
}
</style>
```

- [ ] **Step 2: Refactor ChatArea.vue**

File: `AIagent_frontend/src/components/chat/ChatArea.vue`
```vue
<script setup>
import { ref, watch, nextTick } from 'vue'
import MessageBubble from './MessageBubble.vue'
import ChatEmpty from './ChatEmpty.vue'
import Composer from './Composer.vue'
import StatusBar from './StatusBar.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  isStreaming: { type: Boolean, default: false },
  conversation: { type: Object, default: null }
})

const emit = defineEmits(['send', 'stop', 'regenerate', 'reaction'])

const messagesContainer = ref(null)

// Auto-scroll to bottom when new messages arrive
watch(
  () => props.messages.length,
  async () => {
    await nextTick()
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  },
  { flush: 'post' }
)
</script>

<template>
  <div class="chat-area">
    <!-- Title bar -->
    <div class="chat-title">
      <span class="title-text">
        {{ conversation?.title || conversation?.agentName || '新对话' }}
      </span>
    </div>

    <!-- Messages -->
    <div ref="messagesContainer" class="messages-container">
      <ChatEmpty v-if="messages.length === 0" />

      <MessageBubble
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
        @regenerate="emit('regenerate', $event)"
        @reaction="emit('reaction', $event[0], $event[1])"
      />
    </div>

    <!-- Composer -->
    <Composer
      :disabled="isStreaming"
      :placeholder="conversation ? '输入消息...' : '选择一个 Agent 开始对话...'"
      @send="emit('send', $event)"
      @stop="emit('stop')"
    />
  </div>
</template>

<style scoped>
.chat-area {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.chat-title {
  padding: 12px 16px;
  border-bottom: 1px solid #E5E5EA;
  background: rgba(255,255,255,0.8);
  backdrop-filter: blur(20px);
}

.title-text {
  font-size: 16px;
  font-weight: 600;
  color: #1D1D1F;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 16px 0;
}
</style>
```

---

## Group G: Agent Integration

### Task G1: Rewrite agent store

**Files:**
- Rewrite: `AIagent_frontend/src/stores/agent.js`

- [ ] **Step 1: Write agent store**

File: `AIagent_frontend/src/stores/agent.js`
```js
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchAgents, fetchAgentDetail } from '@/api/agent'

const LAST_AGENT_KEY = 'ai_agent_last_agent'

export const useAgentStore = defineStore('agent', () => {
  const agents = ref([])
  const selectedAgentId = ref(null)
  const isLoading = ref(false)
  const detailCache = ref(new Map())

  const selectedAgent = computed(
    () => agents.value.find(a => String(a.id) === String(selectedAgentId.value)) || null
  )

  const enabledAgents = computed(() => agents.value.filter(a => a.enabled !== false))

  function selectAgent(id) {
    selectedAgentId.value = id || null
    if (id) {
      localStorage.setItem(LAST_AGENT_KEY, String(id))
    }
  }

  async function loadAgents(params = { enabled: true }) {
    isLoading.value = true
    try {
      const data = await fetchAgents(params)
      const list = Array.isArray(data) ? data : (data?.records || [])
      agents.value = list

      // Restore last selected
      const lastId = localStorage.getItem(LAST_AGENT_KEY)
      if (lastId && list.some(a => String(a.id) === lastId)) {
        selectedAgentId.value = lastId
      } else if (list.length > 0) {
        selectedAgentId.value = String(list[0].id)
      }
    } finally {
      isLoading.value = false
    }
  }

  async function loadDetail(id) {
    if (detailCache.value.has(id)) return detailCache.value.get(id)
    const data = await fetchAgentDetail(id)
    detailCache.value.set(id, data)
    return data
  }

  return {
    agents, selectedAgentId, isLoading,
    selectedAgent, enabledAgents,
    selectAgent, loadAgents, loadDetail
  }
})
```

### Task G2: Simplify App.vue

**Files:**
- Rewrite: `AIagent_frontend/src/App.vue`

- [ ] **Step 1: Simplify App.vue**

File: `AIagent_frontend/src/App.vue`
```vue
<script setup>
import PageTransition from '@/components/common/PageTransition.vue'
</script>

<template>
  <PageTransition />
</template>
```

Remove the old agentStore.backendDown modal dependency. Connection errors are now handled via StatusBar inside ChatView.

---

## Group H: Extend CSS Variables

### Task H1: Update variables.css

**Files:**
- Extend: `AIagent_frontend/src/assets/styles/variables.css`

- [ ] **Step 1: Add new color tokens**

Append to the existing `variables.css`:

```css
/* AgentHub extended tokens */
:root {
  --color-primary: #2E75B6;
  --color-primary-hover: #3A8FD4;
  --color-primary-pressed: #2568A0;
  --color-success: #34C759;
  --color-warning: #FF9500;
  --color-danger: #FF3B30;
  --color-bg-primary: #F5F5F7;
  --color-bg-card: #FFFFFF;
  --color-bg-sidebar: #F5F5F7;
  --color-text-primary: #1D1D1F;
  --color-text-secondary: #666666;
  --color-text-tertiary: #999999;
  --color-border: #E5E5EA;

  --radius-sm: 8px;
  --radius-md: 14px;
  --radius-lg: 20px;
  --radius-full: 999px;

  --font-sans: system-ui, -apple-system, "SF Pro Text", "PingFang SC", sans-serif;
  --font-mono: "SF Mono", "Fira Code", monospace;
  --font-size-msg: 14px;
  --font-size-sm: 12px;
  --font-size-lg: 18px;
  --line-height-msg: 1.6;

  --sidebar-width: 320px;
  --sidebar-min: 260px;
  --sidebar-max: 400px;
}
```

---

## Verification Checklist

After completing all Phase 1 tasks, verify:

- [ ] `npm run dev` starts on port 3000 without errors
- [ ] Visiting `http://localhost:3000` redirects to `/#/login`
- [ ] Login with admin/admin calls `POST /api/v1/auth/login` and succeeds
- [ ] After login, workspace auto-selected, JWT stored in localStorage
- [ ] Conversation sidebar loads list from `GET /api/v1/conversations`
- [ ] Clicking "新建对话" clears the active conversation
- [ ] Typing a message and hitting Enter sends it
- [ ] `POST /api/v1/chat/stream` is called, SSE events are received
- [ ] Agent response streams in real-time (text deltas append to bubble)
- [ ] "停止" button appears during streaming and stops generation
- [ ] Token usage shown after completion
- [ ] Copy / Regenerate / Reaction buttons appear on hover
- [ ] Pin / Archive / Delete work from sidebar context menu
- [ ] Route `/chat/42` loads that conversation's message history
- [ ] Network disconnect shows "Reconnecting..." in StatusBar
- [ ] 401 response clears token and redirects to login

---

**Next Phase:** Phase 2 (Group Chat + Orchestrator) — Plans for PlanCard component, delegations, group creation flow after Phase 1 verification passes.
