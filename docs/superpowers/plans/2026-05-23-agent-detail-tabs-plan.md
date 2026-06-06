# AgentDetailView Skills / Tools / Stats Tabs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three "开发中" placeholder tabs in AgentDetailView with functional Skills, Tools, and Stats tabs backed by existing backend APIs.

**Architecture:** New `agent-bindings.js` API module with 7 functions (skill/tool catalog, agent bindings CRUD, stats). AgentDetailView.vue gains reactive state per tab and lazy-fetches data on tab activation. New agent page (`agentId === 'new'`) hides Skills/Tools/Stats tabs.

**Tech Stack:** Vue 3 Composition API, Pinia, Naive UI (n-checkbox-group, n-statistic, n-grid, n-spin, n-empty, n-tag), Axios via apiClient

---

### File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `AIagent_frontend/src/api/agent-bindings.js` | **Create** | 7 API functions: skill catalog, tool catalog, agent skill/tool bindings CRUD, provider prefs, stats |
| `AIagent_frontend/src/views/AgentDetailView.vue` | **Modify** | Replace 3 placeholder tabs, add reactive state, lazy-fetch logic, hide tabs for new agent |

---

### Task 1: Create the agent-bindings API module

**Files:**
- Create: `AIagent_frontend/src/api/agent-bindings.js`

- [ ] **Step 1: Write the module**

```js
import apiClient from './client'

// ── Skill catalog ──
export function fetchEnabledSkills() {
  return apiClient.get('/api/v1/skills/enabled')
}

// ── Tool catalog ──
export function fetchAvailableTools() {
  return apiClient.get('/api/v1/tools/available')
}

// ── Agent skill bindings ──
export function fetchAgentSkills(agentId) {
  return apiClient.get(`/api/v1/agents/${agentId}/skills`)
}

export function updateAgentSkills(agentId, skillIds) {
  return apiClient.put(`/api/v1/agents/${agentId}/skills`, skillIds)
}

// ── Agent tool bindings ──
export function fetchAgentTools(agentId) {
  return apiClient.get(`/api/v1/agents/${agentId}/tools`)
}

export function updateAgentTools(agentId, toolNames) {
  return apiClient.put(`/api/v1/agents/${agentId}/tools`, toolNames)
}

// ── Provider preferences ──
export function fetchProviderPreferences(agentId) {
  return apiClient.get(`/api/v1/agents/${agentId}/provider-preferences`)
}

export function updateProviderPreferences(agentId, providerIds) {
  return apiClient.put(`/api/v1/agents/${agentId}/provider-preferences`, providerIds)
}

// ── Stats ──
export function fetchAgentStats(agentId) {
  return apiClient.get(`/api/v1/agents/${agentId}/stats`)
}
```

- [ ] **Step 2: Verify file exists and has no syntax errors**

Run: `node -e "import('./AIagent_frontend/src/api/agent-bindings.js').then(m => console.log(Object.keys(m)))"`  
Expected: Lists the 9 exported function names (or a module parse error if run from wrong dir — just check syntax with `node --check`)

Actually, verify syntax with:

Run: `cd AIagent_frontend && node --check src/api/agent-bindings.js`  
Expected: No output (no errors)

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/api/agent-bindings.js
git commit -m "feat: add agent-bindings API module (skills, tools, stats)"
```

---

### Task 2: Add reactive state and tab logic to AgentDetailView

**Files:**
- Modify: `AIagent_frontend/src/views/AgentDetailView.vue`

- [ ] **Step 1: Update imports — add new API functions and Naive UI components**

Replace the current `<script setup>` imports (lines 1-8):

Old:
```js
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'
import { NAvatar, NTag, NButton, NInput, NTabs, NTabPane, NSpin, NSpace, NSwitch, NDynamicTags, NSelect, NIcon } from 'naive-ui'
import { updateAgent, deleteAgent } from '@/api/agents'
import { CameraOutline } from '@vicons/ionicons5'
import { getToken } from '@/utils/token'
```

New:
```js
import { ref, onMounted, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'
import { NAvatar, NTag, NButton, NInput, NTabs, NTabPane, NSpin, NSpace, NSwitch, NDynamicTags, NSelect, NIcon, NCheckboxGroup, NCheckbox, NEmpty, NGrid, NGridItem, NStatistic, NText } from 'naive-ui'
import { updateAgent, deleteAgent } from '@/api/agents'
import { CameraOutline } from '@vicons/ionicons5'
import { getToken } from '@/utils/token'
import { formatTime } from '@/utils/time'
import {
  fetchEnabledSkills,
  fetchAvailableTools,
  fetchAgentSkills,
  updateAgentSkills,
  fetchAgentTools,
  updateAgentTools,
  fetchAgentStats
} from '@/api/agent-bindings'
```

- [ ] **Step 2: Add reactive state for Skills, Tools, and Stats tabs**

Insert after line 26 (`const modelName = ref('')`) and before the `agentTypes` array:

```js
// ── Skills tab state ──
const availableSkills = ref([])
const selectedSkillIds = ref([])
const loadingSkills = ref(false)
const savingSkills = ref(false)

// ── Tools tab state ──
const availableTools = ref([])
const selectedToolNames = ref([])
const loadingTools = ref(false)
const savingTools = ref(false)

// ── Stats tab state ──
const stats = ref(null)
const loadingStats = ref(false)
```

- [ ] **Step 3: Add lazy-fetch functions for each tab**

Insert after the `agentTypes` array (after line 31, before `onMounted`):

```js
async function loadSkillsTab() {
  if (agentId.value === 'new' || loadingSkills.value) return
  loadingSkills.value = true
  try {
    const [bindings, allSkills] = await Promise.all([
      fetchAgentSkills(Number(agentId.value)),
      fetchEnabledSkills()
    ])
    availableSkills.value = Array.isArray(allSkills) ? allSkills : (allSkills?.records || [])
    // Pre-select skills that are already bound
    const boundIds = new Set((bindings || []).map(b => b.skillId))
    selectedSkillIds.value = availableSkills.value
      .filter(s => boundIds.has(s.id))
      .map(s => s.id)
  } finally {
    loadingSkills.value = false
  }
}

async function saveSkills() {
  if (agentId.value === 'new') return
  savingSkills.value = true
  try {
    await updateAgentSkills(Number(agentId.value), selectedSkillIds.value)
  } catch (err) {
    console.warn('Save skills failed:', err)
  } finally {
    savingSkills.value = false
  }
}

async function loadToolsTab() {
  if (agentId.value === 'new' || loadingTools.value) return
  loadingTools.value = true
  try {
    const [bindings, allTools] = await Promise.all([
      fetchAgentTools(Number(agentId.value)),
      fetchAvailableTools()
    ])
    availableTools.value = Array.isArray(allTools) ? allTools : (allTools?.records || [])
    // Pre-select tools that are already bound (match by name string)
    const boundNames = new Set((bindings || []).map(b => b.toolName))
    selectedToolNames.value = availableTools.value
      .filter(t => boundNames.has(t.name))
      .map(t => t.name)
  } finally {
    loadingTools.value = false
  }
}

async function saveTools() {
  if (agentId.value === 'new') return
  savingTools.value = true
  try {
    await updateAgentTools(Number(agentId.value), selectedToolNames.value)
  } catch (err) {
    console.warn('Save tools failed:', err)
  } finally {
    savingTools.value = false
  }
}

async function loadStatsTab() {
  if (agentId.value === 'new' || loadingStats.value) return
  loadingStats.value = true
  try {
    stats.value = await fetchAgentStats(Number(agentId.value))
  } catch (err) {
    console.warn('Load stats failed:', err)
    stats.value = null
  } finally {
    loadingStats.value = false
  }
}
```

- [ ] **Step 4: Add tab-change watcher for lazy loading**

Insert after the `onMounted` block (after line 47):

```js
// Lazy-load tab content on switch
watch(activeTab, (tab) => {
  if (tab === 'skills') loadSkillsTab()
  else if (tab === 'tools') loadToolsTab()
  else if (tab === 'stats') loadStatsTab()
})

// Also load on first mount for existing agents
onMounted(() => {
  if (agentId.value && agentId.value !== 'new') {
    if (activeTab.value === 'skills') loadSkillsTab()
    else if (activeTab.value === 'tools') loadToolsTab()
    else if (activeTab.value === 'stats') loadStatsTab()
  }
})
```

Note: This creates a second `onMounted` — merge it with the existing one (lines 34-47) into a single `onMounted` call.

- [ ] **Step 5: Merge the two onMounted blocks**

Replace the existing `onMounted` (lines 34-47) with the merged version:

```js
onMounted(async () => {
  if (agentId.value && agentId.value !== 'new') {
    const detail = await store.loadDetail(Number(agentId.value))
    if (detail) {
      name.value = detail.name || ''
      description.value = detail.description || ''
      systemPrompt.value = detail.systemPrompt || ''
      agentType.value = detail.agentType || 'react'
      enabled.value = detail.enabled !== false
      isPublic.value = detail.isPublic !== false
      capabilityTags.value = detail.capabilityTags || []
      modelName.value = detail.modelName || ''
    }
    // Lazy-load active tab
    if (activeTab.value === 'skills') loadSkillsTab()
    else if (activeTab.value === 'tools') loadToolsTab()
    else if (activeTab.value === 'stats') loadStatsTab()
  }
})
```

And remove the duplicate `onMounted` from Step 4 (only keep the `watch` from Step 4).

- [ ] **Step 6: Commit the script setup changes**

```bash
git add AIagent_frontend/src/views/AgentDetailView.vue
git commit -m "feat: add reactive state and lazy-fetch logic for Skills/Tools/Stats tabs"
```

---

### Task 3: Replace Skills Tab placeholder

**Files:**
- Modify: `AIagent_frontend/src/views/AgentDetailView.vue` (template section only)

- [ ] **Step 1: Replace the Skills tab placeholder (lines 194-196)**

Old:
```html
<NTabPane name="skills" tab="技能">
  <p class="tab-placeholder">技能配置 — 开发中</p>
</NTabPane>
```

New:
```html
<NTabPane name="skills" tab="技能">
  <NSpin v-if="loadingSkills" />
  <NSpace v-else vertical :size="16">
    <div v-if="availableSkills.length === 0">
      <NEmpty description="暂未绑定技能" />
    </div>
    <NCheckboxGroup v-model:value="selectedSkillIds" v-else>
      <NSpace vertical :size="12">
        <NCheckbox
          v-for="skill in availableSkills"
          :key="skill.id"
          :value="skill.id"
        >
          <div>
            <NText strong>{{ skill.name }}</NText>
            <NText v-if="skill.description" depth="3" style="display:block;">{{ skill.description }}</NText>
          </div>
        </NCheckbox>
      </NSpace>
    </NCheckboxGroup>
    <NButton type="primary" @click="saveSkills" :loading="savingSkills" :disabled="availableSkills.length === 0">
      保存技能配置
    </NButton>
  </NSpace>
</NTabPane>
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/views/AgentDetailView.vue
git commit -m "feat: implement Skills tab with checkbox binding UI"
```

---

### Task 4: Replace Tools Tab placeholder

**Files:**
- Modify: `AIagent_frontend/src/views/AgentDetailView.vue` (template section only)

- [ ] **Step 1: Replace the Tools tab placeholder (lines 197-199)**

Old:
```html
<NTabPane name="tools" tab="工具">
  <p class="tab-placeholder">工具配置 — 开发中</p>
</NTabPane>
```

New:
```html
<NTabPane name="tools" tab="工具">
  <NSpin v-if="loadingTools" />
  <NSpace v-else vertical :size="16">
    <div v-if="availableTools.length === 0">
      <NEmpty description="暂未绑定工具" />
    </div>
    <NCheckboxGroup v-model:value="selectedToolNames" v-else>
      <NSpace vertical :size="12">
        <NCheckbox
          v-for="tool in availableTools"
          :key="tool.name"
          :value="tool.name"
        >
          <div>
            <NText strong>{{ tool.name }}</NText>
            <NTag v-if="tool.source" :type="tool.source === 'builtin' ? 'info' : 'success'" size="tiny" style="margin-left:8px;">
              {{ tool.source }}
            </NTag>
            <NText v-if="tool.description" depth="3" style="display:block;margin-top:2px;">{{ tool.description }}</NText>
          </div>
        </NCheckbox>
      </NSpace>
    </NCheckboxGroup>
    <NButton type="primary" @click="saveTools" :loading="savingTools" :disabled="availableTools.length === 0">
      保存工具配置
    </NButton>
  </NSpace>
</NTabPane>
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/views/AgentDetailView.vue
git commit -m "feat: implement Tools tab with checkbox binding UI and source badges"
```

---

### Task 5: Replace Stats Tab placeholder

**Files:**
- Modify: `AIagent_frontend/src/views/AgentDetailView.vue` (template section only)

- [ ] **Step 1: Replace the Stats tab placeholder (lines 200-202)**

Old:
```html
<NTabPane name="stats" tab="统计">
  <p class="tab-placeholder">使用统计 — 开发中</p>
</NTabPane>
```

New:
```html
<NTabPane name="stats" tab="统计">
  <NSpin v-if="loadingStats" />
  <NGrid v-else-if="stats" cols="3" x-gap="12" y-gap="12">
    <NGridItem>
      <NStatistic label="总会话数" :value="stats.totalConversations ?? 0" />
    </NGridItem>
    <NGridItem>
      <NStatistic label="总消息数" :value="stats.totalMessages ?? 0" />
    </NGridItem>
    <NGridItem>
      <NStatistic label="Token 用量" :value="stats.totalTokens ?? 0" />
    </NGridItem>
    <NGridItem>
      <NStatistic label="平均响应时间" :value="stats.avgResponseTimeMs != null ? (stats.avgResponseTimeMs / 1000).toFixed(2) + ' s' : '—'" />
    </NGridItem>
    <NGridItem>
      <NStatistic label="最后活跃" :value="stats.lastActiveAt ? formatTime(stats.lastActiveAt) : '—'" />
    </NGridItem>
  </NGrid>
  <NEmpty v-else description="暂无统计数据" />
</NTabPane>
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/views/AgentDetailView.vue
git commit -m "feat: implement Stats tab with NStatistic grid (5 backend fields)"
```

---

### Task 6: Hide Skills/Tools/Stats tabs for new agents

**Files:**
- Modify: `AIagent_frontend/src/views/AgentDetailView.vue` (template section only)

- [ ] **Step 1: Add `v-if` to hide extra tabs for new agents**

Wrap the three new tab panes in a conditional. Wrap Skills, Tools, and Stats `NTabPane` elements with:

```html
<template v-if="agentId !== 'new'">
  <NTabPane name="skills" tab="技能"> ... </NTabPane>
  <NTabPane name="tools" tab="工具"> ... </NTabPane>
  <NTabPane name="stats" tab="统计"> ... </NTabPane>
</template>
```

This replaces the three individual `NTabPane` elements. The `<template>` wrapper groups them behind a single `v-if`.

In the template, replace lines 194-202 (the three placeholder NTabPane blocks) with:

```html
<template v-if="agentId !== 'new'">
  <NTabPane name="skills" tab="技能">
    <NSpin v-if="loadingSkills" />
    <NSpace v-else vertical :size="16">
      <div v-if="availableSkills.length === 0">
        <NEmpty description="暂未绑定技能" />
      </div>
      <NCheckboxGroup v-model:value="selectedSkillIds" v-else>
        <NSpace vertical :size="12">
          <NCheckbox
            v-for="skill in availableSkills"
            :key="skill.id"
            :value="skill.id"
          >
            <div>
              <NText strong>{{ skill.name }}</NText>
              <NText v-if="skill.description" depth="3" style="display:block;">{{ skill.description }}</NText>
            </div>
          </NCheckbox>
        </NSpace>
      </NCheckboxGroup>
      <NButton type="primary" @click="saveSkills" :loading="savingSkills" :disabled="availableSkills.length === 0">
        保存技能配置
      </NButton>
    </NSpace>
  </NTabPane>

  <NTabPane name="tools" tab="工具">
    <NSpin v-if="loadingTools" />
    <NSpace v-else vertical :size="16">
      <div v-if="availableTools.length === 0">
        <NEmpty description="暂未绑定工具" />
      </div>
      <NCheckboxGroup v-model:value="selectedToolNames" v-else>
        <NSpace vertical :size="12">
          <NCheckbox
            v-for="tool in availableTools"
            :key="tool.name"
            :value="tool.name"
          >
            <div>
              <NText strong>{{ tool.name }}</NText>
              <NTag v-if="tool.source" :type="tool.source === 'builtin' ? 'info' : 'success'" size="tiny" style="margin-left:8px;">
                {{ tool.source }}
              </NTag>
              <NText v-if="tool.description" depth="3" style="display:block;margin-top:2px;">{{ tool.description }}</NText>
            </div>
          </NCheckbox>
        </NSpace>
      </NCheckboxGroup>
      <NButton type="primary" @click="saveTools" :loading="savingTools" :disabled="availableTools.length === 0">
        保存工具配置
      </NButton>
    </NSpace>
  </NTabPane>

  <NTabPane name="stats" tab="统计">
    <NSpin v-if="loadingStats" />
    <NGrid v-else-if="stats" cols="3" x-gap="12" y-gap="12">
      <NGridItem>
        <NStatistic label="总会话数" :value="stats.totalConversations ?? 0" />
      </NGridItem>
      <NGridItem>
        <NStatistic label="总消息数" :value="stats.totalMessages ?? 0" />
      </NGridItem>
      <NGridItem>
        <NStatistic label="Token 用量" :value="stats.totalTokens ?? 0" />
      </NGridItem>
      <NGridItem>
        <NStatistic label="平均响应时间" :value="stats.avgResponseTimeMs != null ? (stats.avgResponseTimeMs / 1000).toFixed(2) + ' s' : '—'" />
      </NGridItem>
      <NGridItem>
        <NStatistic label="最后活跃" :value="stats.lastActiveAt ? formatTime(stats.lastActiveAt) : '—'" />
      </NGridItem>
    </NGrid>
    <NEmpty v-else description="暂无统计数据" />
  </NTabPane>
</template>
```

Note: Since this step bundles the final template for all three tabs wrapped in the `v-if`, it effectively replaces Tasks 3, 4, 5. If implementing sequentially, do this single-step replacement instead of the individual tab replacements.

- [ ] **Step 2: Verify the template compiles**

Run: `cd AIagent_frontend && npx vue-tsc --noEmit src/views/AgentDetailView.vue 2>&1 | head -20`  
Expected: No errors (or pre-existing errors only, no new ones)

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/views/AgentDetailView.vue
git commit -m "feat: hide Skills/Tools/Stats tabs for new agents, show full tab content for existing agents"
```

---

### Task 7: Build verification

- [ ] **Step 1: Build the frontend to verify no compilation errors**

Run: `cd AIagent_frontend && npm run build`  
Expected: Build succeeds with no errors (warnings OK)

- [ ] **Step 2: Start dev server for manual verification**

Run: `cd AIagent_frontend && npm run dev`  
Then navigate to:
- `http://localhost:3000/#/agents/1` → Skills tab: should show skill checkboxes
- Select/deselect skills, click save, refresh → config persists
- Tools tab: should show tool checkboxes with source badges
- Stats tab: should show 5 statistics in a grid
- `http://localhost:3000/#/agents/new` → only Config tab visible, no Skills/Tools/Stats tabs

---

### Summary of Commits

| # | Commit Message | Files |
|---|---------------|-------|
| 1 | `feat: add agent-bindings API module (skills, tools, stats)` | `agent-bindings.js` (new) |
| 2 | `feat: add reactive state and lazy-fetch logic for Skills/Tools/Stats tabs` | `AgentDetailView.vue` (script) |
| 3 | `feat: implement Skills, Tools, Stats tabs with full UI content` | `AgentDetailView.vue` (template) |
| 4 | `feat: hide Skills/Tools/Stats tabs for new agents` | `AgentDetailView.vue` (template) |
