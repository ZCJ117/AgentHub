# Workspace Working Directory — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to set a working directory per workspace so CLI agents spawn in the user's chosen project folder instead of inheriting the Java process cwd.

**Architecture:** Reuses the existing `mate_workspace.base_path` column. Frontend adds text input on SettingsView workspace card. Backend validates directory existence on save (WorkspaceService) and again on spawn (LocalCliProcessManager). Two callers (BridgedAgent, AgentMentionDispatcher) resolve workspace basePath via WorkspaceMapper before spawning.

**Tech Stack:** Vue 3 + Naive UI (frontend), Java 21 + Spring Boot (backend)

---

**A note on dependencies:** Tasks 1-3 (frontend) and Task 4-5 (backend) are independent. Tasks 6-7 depend on Task 5 (spawn signature change).

---

### Task 1: Frontend — Add `updateWorkspace` to API layer

**Files:**
- Modify: `AIagent_frontend/src/api/workspaces.js`

- [ ] **Step 1: Add updateWorkspace function**

```js
import apiClient from './client'

export function fetchWorkspaces() {
  return apiClient.get('/api/v1/workspaces')
}

export function updateWorkspace(id, data) {
  return apiClient.put(`/api/v1/workspaces/${id}`, data)
}
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/api/workspaces.js
git commit -m "feat: add updateWorkspace API function"
```

---

### Task 2: Frontend — Add workspace cache refresh to store

**Files:**
- Modify: `AIagent_frontend/src/stores/workspace.js`

- [ ] **Step 1: Add `refresh` action that reloads workspace list without changing selection**

Add a `refresh` method that re-fetches and updates the `workspaces` array while preserving `activeId`:

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
      const list = Array.isArray(data) ? data : (data?.records || [])
      workspaces.value = list
      if (list.length > 0) {
        const id = stored && list.some(w => String(w.id) === String(stored))
          ? Number(stored)
          : list[0].id
        activeId.value = id
        localStorage.setItem('ai_agent_workspace_id', String(id))
      }
    } catch {
      if (stored) {
        activeId.value = Number(stored)
      }
    }
  }

  async function refresh() {
    try {
      const data = await fetchWorkspaces()
      const list = Array.isArray(data) ? data : (data?.records || [])
      workspaces.value = list
    } catch {
      // keep stale data on failure
    }
  }

  async function selectWorkspace(id) {
    activeId.value = id
    localStorage.setItem('ai_agent_workspace_id', String(id))

    const { useConversationStore } = await import('@/stores/conversation')
    const { useAgentStore } = await import('@/stores/agent')
    const { useArtifactStore } = await import('@/stores/artifact')
    const { useOrchestratorStore } = await import('@/stores/orchestrator')

    useConversationStore().setActive(null)
    useOrchestratorStore().reset()
    await Promise.allSettled([
      useConversationStore().loadList(),
      useAgentStore().loadAgents({ enabled: true }),
      useArtifactStore().loadList()
    ])
  }

  return { workspaces, activeId, activeWorkspace, loadAndSelect, refresh, selectWorkspace }
})
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/stores/workspace.js
git commit -m "feat: add refresh action to workspace store"
```

---

### Task 3: Frontend — SettingsView workspace card with basePath editor

**Files:**
- Modify: `AIagent_frontend/src/views/SettingsView.vue`

- [ ] **Step 1: Add basePath state, saveBasePath function, and replace the workspace card template**

In `<script setup>`, add below the existing `email` ref (line ~16):

```js
const basePath = ref('')
const savingPath = ref(false)
const pathMsg = ref('')
const pathOk = ref(false)
```

Add a `watch` import and watch for activeWorkspace changes:

```js
import { ref, onMounted, watch, h } from 'vue'
```

In `onMounted`, add after `authStore.refreshProfile()`:

```js
onMounted(async () => {
  await authStore.refreshProfile()
  nickname.value = authStore.nickname || ''
  loadTokens()
  // Initialize basePath from workspace
  basePath.value = workspaceStore.activeWorkspace?.basePath || ''
})

// Watch for workspace changes while on the page
watch(() => workspaceStore.activeWorkspace, (ws) => {
  basePath.value = ws?.basePath || ''
})
```

Add `saveBasePath` function after `savePassword`:

```js
async function saveBasePath() {
  savingPath.value = true
  pathMsg.value = ''
  try {
    const data = { basePath: basePath.value || null }
    await updateWorkspace(workspaceStore.activeId, data)
    pathOk.value = true
    pathMsg.value = basePath.value ? '工作目录已更新' : '工作目录已清除'
    await workspaceStore.refresh()
  } catch (err) {
    pathOk.value = false
    pathMsg.value = err.message || '保存失败'
  } finally {
    savingPath.value = false
  }
}
```

Add the import for `updateWorkspace`:

```js
import { fetchWorkspaces, updateWorkspace } from '@/api/workspaces'
```

Wait, `fetchWorkspaces` is not currently imported in SettingsView — the workspace store uses it internally. So just import `updateWorkspace`:

```js
import { updateWorkspace } from '@/api/workspaces'
```

Also add to the imports from `naive-ui`: `NSpace` is already imported, check if `NTag` is needed — it's used in the "本地 Agent 接入" card. Good, it's already imported. No new Naive UI component imports needed since `NInput` is already imported.

Replace the existing workspace card (lines 158-160):

```html
<NCard title="工作区" class="settings-card">
  <p>当前工作区 ID: {{ workspaceStore.activeId || '未选择' }}</p>
</NCard>
```

With:

```html
<NCard title="工作区" class="settings-card">
  <NSpace vertical :size="12">
    <div>
      <label>当前工作区</label>
      <NInput :value="workspaceStore.activeWorkspace?.name || '未选择'" disabled />
    </div>
    <div>
      <label>工作目录（Agent CLI 执行路径）</label>
      <NInput v-model:value="basePath" placeholder="如 D:\projects\my-app 或 /home/user/projects/my-app" />
    </div>
    <NButton type="primary" @click="saveBasePath" :loading="savingPath">保存</NButton>
    <span v-if="pathMsg" :style="{ fontSize: '13px', color: pathOk ? '#34C759' : '#FF3B30' }">{{ pathMsg }}</span>
  </NSpace>
</NCard>
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/views/SettingsView.vue
git commit -m "feat: add workspace basePath editor to SettingsView"
```

---

### Task 4: Backend — Validate basePath on workspace save

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/core/service/WorkspaceService.java`

- [ ] **Step 1: Add basePath validation in update() method**

Add imports at top of file:

```java
import java.nio.file.Files;
import java.nio.file.Path;
```

In the `update()` method (~line 212-230), add the validation block before `workspaceMapper.updateById(entity)`:

```java
public WorkspaceEntity update(WorkspaceEntity entity) {
    WorkspaceEntity existing = getById(entity.getId());
    if (entity.getSlug() == null) {
        entity.setSlug(existing.getSlug());
    }
    if (DEFAULT_SLUG.equals(existing.getSlug()) && !DEFAULT_SLUG.equals(entity.getSlug())) {
        throw new MateClawException("err.workspace.cannot_modify_default", "不能修改默认工作区的标识");
    }
    if (!entity.getSlug().equals(existing.getSlug())) {
        if (getBySlug(entity.getSlug()) != null) {
            throw new MateClawException("err.workspace.slug_exists", "工作区标识已存在: " + entity.getSlug());
        }
    }

    // Validate basePath: if non-blank, directory must exist and be readable/writable
    if (entity.getBasePath() != null && !entity.getBasePath().isBlank()) {
        Path path = Path.of(entity.getBasePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new MateClawException("err.workspace.basepath_not_dir",
                    "工作目录不存在: " + path);
        }
        if (!Files.isReadable(path) || !Files.isWritable(path)) {
            throw new MateClawException("err.workspace.basepath_not_accessible",
                    "工作目录不可读写: " + path);
        }
    }

    workspaceMapper.updateById(entity);
    return entity;
}
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/core/service/WorkspaceService.java
git commit -m "feat: validate directory existence on workspace basePath save"
```

---

### Task 5: Backend — Add workingDir to LocalCliProcessManager.spawn()

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/infra/agent/cli/LocalCliProcessManager.java`

- [ ] **Step 1: Add workingDir parameter to spawn() method signature**

Change the method signature (line ~89-91):

```java
public boolean spawn(String agentId, String cliType,
                     String agentName, String systemPrompt,
                     String claudeMdPath,
                     String workingDir) {
```

- [ ] **Step 2: Set ProcessBuilder directory before pb.start()**

Add after the `pb.environment().put(...)` block, before `Process p = pb.start()` (around line 123):

```java
if (workingDir != null && !workingDir.isBlank()) {
    File dir = new File(workingDir);
    if (!dir.isDirectory()) {
        throw new IllegalStateException(
                "Workspace working directory does not exist: " + workingDir);
    }
    pb.directory(dir);
    log.info("[CliPM] Set working directory for agent={}: {}", agentId, workingDir);
}
Process p = pb.start();
```

The insertion point is right before line 124 (`Process p = pb.start()`).

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/infra/agent/cli/LocalCliProcessManager.java
git commit -m "feat: add workingDir parameter to CLI process spawn"
```

---

### Task 6: Backend — BridgedAgent resolves workspace basePath before spawn

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/infra/agent/bridge/BridgedAgent.java`

- [ ] **Step 1: Inject WorkspaceMapper and resolve basePath in chatViaProcess**

Add import:

```java
import vip.mate.domain.workspace.core.repository.WorkspaceMapper;
import vip.mate.domain.workspace.core.model.WorkspaceEntity;
import vip.mate.domain.workspace.conversation.model.ConversationEntity;
```

Add a new field and update the Process bridge constructor to accept WorkspaceMapper:

```java
private final WorkspaceMapper workspaceMapper;
```

Update the Process bridge constructor (~line 51-59):

```java
/** Process bridge constructor */
public BridgedAgent(ConversationService conversationService,
                    AgentBridgeManager bridgeManager,
                    LocalCliProcessManager processManager,
                    AgentMapper agentMapper,
                    WorkspaceMapper workspaceMapper) {
    super(null, conversationService);
    this.bridgeManager = bridgeManager;
    this.processManager = processManager;
    this.agentMapper = agentMapper;
    this.workspaceMapper = workspaceMapper;
}
```

Note: The WebSocket bridge constructor (line 41-47) does not need workspaceMapper — set to null:

```java
/** WebSocket bridge constructor */
public BridgedAgent(ConversationService conversationService,
                    AgentBridgeManager bridgeManager,
                    AgentMapper agentMapper) {
    super(null, conversationService);
    this.bridgeManager = bridgeManager;
    this.processManager = null;
    this.agentMapper = agentMapper;
    this.workspaceMapper = null;
}
```

In `chatViaProcess()`, replace the `spawn()` call (line 117-118):

```java
boolean spawned = processManager.spawn(
        agentId, cliType, agentName, systemPrompt, null);
```

With:

```java
String workingDir = resolveWorkingDir(conversationId);
boolean spawned = processManager.spawn(
        agentId, cliType, agentName, systemPrompt, null, workingDir);
```

Add the `resolveWorkingDir` helper method after `buildContextualMessage`:

```java
private String resolveWorkingDir(String conversationId) {
    if (workspaceMapper == null) return null;
    try {
        ConversationEntity conv = conversationService.getByConversationId(conversationId);
        if (conv == null || conv.getWorkspaceId() == null) return null;
        WorkspaceEntity ws = workspaceMapper.selectById(conv.getWorkspaceId());
        if (ws == null) return null;
        String basePath = ws.getBasePath();
        return (basePath != null && !basePath.isBlank()) ? basePath : null;
    } catch (Exception e) {
        log.warn("[BridgedAgent] Failed to resolve working dir: {}", e.getMessage());
        return null;
    }
}
```

- [ ] **Step 2: Add WorkspaceMapper to AgentGraphBuilder and update constructor call**

AgentGraphBuilder constructs BridgedAgent at line 400. It uses `@RequiredArgsConstructor` and already injects `AgentBridgeManager`, `LocalCliProcessManager`, and `AgentMapper`. Add:

In `AgentGraphBuilder.java`, add the field (~line 121):

```java
private final WorkspaceMapper workspaceMapper;
```

And add the import:

```java
import vip.mate.domain.workspace.core.repository.WorkspaceMapper;
```

At line 400, update the Process bridge constructor call from:

```java
agent = new BridgedAgent(conversationService,
        agentBridgeManager, localCliProcessManager, agentMapper);
```

To:

```java
agent = new BridgedAgent(conversationService,
        agentBridgeManager, localCliProcessManager, agentMapper, workspaceMapper);
```

The WebSocket-only constructor at line 405 (3 args) does not need changes — `workspaceMapper` is null-safe in `resolveWorkingDir`.

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/infra/agent/bridge/BridgedAgent.java \
        mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/agent/AgentGraphBuilder.java
git commit -m "feat: resolve workspace basePath before CLI spawn in BridgedAgent"
```

---

### Task 7: Backend — AgentMentionDispatcher resolves workspace basePath before spawn

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Inject WorkspaceMapper and resolve basePath in spawnAndStreamAgent**

Add import:

```java
import vip.mate.domain.workspace.core.repository.WorkspaceMapper;
import vip.mate.domain.workspace.core.model.WorkspaceEntity;
```

Add a field (the class uses `@RequiredArgsConstructor`, so add it as a constructor parameter):

```java
private final WorkspaceMapper workspaceMapper;
```

The `@RequiredArgsConstructor` annotation from Lombok will auto-generate the constructor including the new field.

In `spawnAndStreamAgent()`, replace the `spawn()` call (lines 129-131):

```java
boolean spawned = processManager.spawn(
        agentIdStr, agent.getCliType(), agentName,
        agent.getSystemPrompt(), claudeMdPath);
```

With:

```java
String workingDir = resolveWorkingDir(conversationId);
boolean spawned = processManager.spawn(
        agentIdStr, agent.getCliType(), agentName,
        agent.getSystemPrompt(), claudeMdPath, workingDir);
```

Add the `resolveWorkingDir` helper method after `completeAndBroadcastDoneIfLast`:

```java
private String resolveWorkingDir(String conversationId) {
    if (workspaceMapper == null) return null;
    try {
        var conv = conversationService.getByConversationId(conversationId);
        if (conv == null || conv.getWorkspaceId() == null) return null;
        WorkspaceEntity ws = workspaceMapper.selectById(conv.getWorkspaceId());
        if (ws == null) return null;
        String basePath = ws.getBasePath();
        return (basePath != null && !basePath.isBlank()) ? basePath : null;
    } catch (Exception e) {
        log.warn("[Dispatcher] Failed to resolve working dir: {}", e.getMessage());
        return null;
    }
}
```

Note: `ConversationService` is already imported and injected as `conversationService` in the current class.

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: resolve workspace basePath before CLI spawn in AgentMentionDispatcher"
```

---

### Task 8: Build verification

- [ ] **Step 1: Build backend**

```bash
cd mateclaw-dev && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Build frontend**

```bash
cd AIagent_frontend && npm run build
```

Expected: builds without errors

- [ ] **Step 3: Commit (if build fixes were needed)**

```bash
git add -A
git commit -m "chore: build fixes for workspace working directory feature"
```

---

### Task 9: Manual verification checklist

- [ ] **1. Save valid path:** Open Settings → enter a valid directory path → click Save → green "工作目录已更新" message appears
- [ ] **2. Save invalid path:** Enter a non-existent path → click Save → red error message about path not existing
- [ ] **3. Clear path:** Clear the input → click Save → green "工作目录已清除" message
- [ ] **4. Single chat:** Create a new 1:1 chat with a CLI agent → agent should spawn with working directory set
- [ ] **5. Group chat:** Create a new group chat → @mention a CLI agent → agent should spawn with working directory set
- [ ] **6. Workspace switch:** Switch to a different workspace → basePath input should reflect that workspace's value
