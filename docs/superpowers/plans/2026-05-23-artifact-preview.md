# Artifact Content Loading & SSE Real-Time Preview — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace hardcoded placeholders in ArtifactDetailView with real content loaded from a new backend endpoint, and wire SSE `artifact_preview` events into the artifact store and chat stream.

**Architecture:** New backend `GET /api/v1/artifacts/{id}/content` endpoint reads file content via `ArtifactStorageProvider`. Frontend `src/api/artifacts.js` wraps the endpoint. `artifactStore` gains `loadContent`/`loadVersionContent`/`handleArtifactPreview`. `ArtifactDetailView` uses `NCode`, `renderMarkdown`, and `:srcdoc` to display loaded content. `chat.js` SSE handler replaces `console.log` with store integration and `preview_card` message insertion.

**Tech Stack:** Vue 3 + Pinia + Naive UI + Axios (frontend), Spring Boot 3 + MyBatis-Plus (backend)

---

## File Map

| File | Responsibility |
|------|---------------|
| `mateclaw-dev/.../artifact/model/ArtifactContentVO.java` | **New** — DTO for content endpoint response |
| `mateclaw-dev/.../artifact/service/ArtifactService.java` | Modify — add `getContent()`, `getVersionContent()` |
| `mateclaw-dev/.../artifact/controller/ArtifactController.java` | Modify — add `GET /{id}/content`, `GET /{id}/versions/{versionId}/content` |
| `AIagent_frontend/src/api/artifacts.js` | **New** — `fetchArtifactContent`, `fetchVersionContent` |
| `AIagent_frontend/src/stores/artifact.js` | Modify — refactor to use API module, add 3 new methods |
| `AIagent_frontend/src/views/ArtifactDetailView.vue` | Modify — replace placeholders with real content |
| `AIagent_frontend/src/stores/chat.js` | Modify — replace `console.log` with store + message integration |
| `AIagent_frontend/src/components/chat/MessageBubble.vue` | Modify — lookup artifact from store, pass prop |

---

### Task 1: Backend — Create ArtifactContentVO

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/artifact/model/ArtifactContentVO.java`

- [ ] **Step 1: Create the VO class**

```java
package vip.mate.artifact.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactContentVO {
    private String content;
    private String contentType;
    private String fileName;
    private String downloadUrl;
}
```

- [ ] **Step 2: Compile to verify**

Run: `cd mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/artifact/model/ArtifactContentVO.java
git commit -m "feat: add ArtifactContentVO for content endpoint response"
```

---

### Task 2: Backend — Add getContent and getVersionContent to ArtifactService

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/artifact/service/ArtifactService.java` — add two methods

- [ ] **Step 1: Add getContent method**

Insert after `getArtifact` (line 67):

```java
public ArtifactContentVO getContent(Long artifactId) {
    ArtifactEntity artifact = getArtifact(artifactId);
    ArtifactVersionEntity latestVersion = versionMapper.selectOne(
        new LambdaQueryWrapper<ArtifactVersionEntity>()
            .eq(ArtifactVersionEntity::getArtifactId, artifactId)
            .eq(ArtifactVersionEntity::getVersionNumber, artifact.getCurrentVersion()));
    if (latestVersion == null) {
        throw new MateClawException("err.artifact.version_not_found", "版本不存在");
    }
    return buildContentVO(artifact, latestVersion);
}

public ArtifactContentVO getVersionContent(Long artifactId, Long versionId) {
    ArtifactEntity artifact = getArtifact(artifactId);
    ArtifactVersionEntity version = getVersion(artifactId, versionId);
    return buildContentVO(artifact, version);
}

private ArtifactContentVO buildContentVO(ArtifactEntity artifact, ArtifactVersionEntity version) {
    String fileName = extractFileName(version.getFilePath());
    String contentType = guessContentType(fileName, artifact.getArtifactType());

    String content = null;
    String downloadUrl = null;

    if (isTextType(contentType)) {
        try {
            content = new String(storageProvider.read(version.getFilePath()).readAllBytes());
        } catch (Exception e) {
            log.warn("Failed to read artifact content: {}", e.getMessage());
        }
    } else {
        downloadUrl = "/api/v1/artifacts/" + artifact.getId() + "/versions/" + version.getId() + "/raw";
    }

    return ArtifactContentVO.builder()
        .content(content)
        .contentType(contentType)
        .fileName(fileName)
        .downloadUrl(downloadUrl)
        .build();
}

private String extractFileName(String filePath) {
    if (filePath == null) return "untitled";
    int idx = filePath.lastIndexOf('/');
    return idx >= 0 ? filePath.substring(idx + 1) : filePath;
}

private String guessContentType(String fileName, String artifactType) {
    if ("html".equalsIgnoreCase(artifactType)) return "text/html";
    if ("markdown".equalsIgnoreCase(artifactType)) return "text/markdown";
    if ("code".equalsIgnoreCase(artifactType)) {
        String ext = fileName != null && fileName.contains(".")
            ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
        return switch (ext) {
            case "js" -> "text/javascript";
            case "ts" -> "text/typescript";
            case "py" -> "text/x-python";
            case "java" -> "text/x-java";
            case "go" -> "text/x-go";
            case "rs" -> "text/x-rust";
            case "css" -> "text/css";
            case "json" -> "application/json";
            case "yaml", "yml" -> "text/x-yaml";
            case "sql" -> "text/x-sql";
            case "sh" -> "text/x-sh";
            case "xml" -> "text/xml";
            default -> "text/plain";
        };
    }
    if ("image".equalsIgnoreCase(artifactType)) {
        String ext = fileName != null && fileName.contains(".")
            ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "png";
        return "image/" + ext;
    }
    return "application/octet-stream";
}

private boolean isTextType(String contentType) {
    return contentType.startsWith("text/") || contentType.equals("application/json")
        || contentType.equals("application/javascript");
}
```

- [ ] **Step 2: Compile to verify**

Run: `cd mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/artifact/service/ArtifactService.java
git commit -m "feat: add getContent and getVersionContent methods to ArtifactService"
```

---

### Task 3: Backend — Add controller endpoints

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/artifact/controller/ArtifactController.java` — add two endpoints

- [ ] **Step 1: Add content endpoints**

Insert after the `version` method (line 51), before `diff`:

```java
@Operation(summary = "获取产物最新版本内容")
@GetMapping("/{id}/content")
public R<ArtifactContentVO> content(@PathVariable Long id) {
    return R.ok(artifactService.getContent(id));
}

@Operation(summary = "获取产物指定版本内容")
@GetMapping("/{id}/versions/{versionId}/content")
public R<ArtifactContentVO> versionContent(@PathVariable Long id, @PathVariable Long versionId) {
    return R.ok(artifactService.getVersionContent(id, versionId));
}
```

The `ArtifactContentVO` import is already covered by the wildcard `import vip.mate.artifact.model.*` at line 8.

- [ ] **Step 2: Compile to verify**

Run: `cd mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/artifact/controller/ArtifactController.java
git commit -m "feat: add artifact content endpoints to controller"
```

---

### Task 4: Frontend — Create src/api/artifacts.js

**Files:**
- Create: `AIagent_frontend/src/api/artifacts.js`

- [ ] **Step 1: Create the API module**

```js
import apiClient from './client'

export function fetchArtifacts(params) {
  return apiClient.get('/api/v1/artifacts', { params })
}

export function fetchArtifactDetail(id) {
  return apiClient.get(`/api/v1/artifacts/${id}`)
}

export function fetchArtifactVersions(id) {
  return apiClient.get(`/api/v1/artifacts/${id}/versions`)
}

export function fetchVersionDetail(artifactId, versionId) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/versions/${versionId}`)
}

export function fetchArtifactContent(artifactId) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/content`)
}

export function fetchVersionContent(artifactId, versionId) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/versions/${versionId}/content`)
}

export function fetchVersionDiff(artifactId, fromVersion, toVersion) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/versions/diff`, {
    params: { from: fromVersion, to: toVersion }
  })
}

export function restoreArtifactVersion(artifactId, versionId) {
  return apiClient.post(`/api/v1/artifacts/${artifactId}/versions/${versionId}/restore`)
}

export function deployArtifact(artifactId, config) {
  return apiClient.post(`/api/v1/artifacts/${artifactId}/deploy`, config)
}

export function fetchDeployStatus(artifactId) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/deploy/status`)
}

export function fetchDeployHistory(artifactId, params) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/deploy/history`, { params })
}

export function updateArtifactTags(id, tags) {
  return apiClient.put(`/api/v1/artifacts/${id}/tags`, { tags })
}
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/api/artifacts.js
git commit -m "feat: add artifacts API module with all artifact endpoints"
```

---

### Task 5: Frontend — Refactor artifact store to use API module

**Files:**
- Modify: `AIagent_frontend/src/stores/artifact.js` — replace direct `apiClient` calls with imports from `@/api/artifacts`

- [ ] **Step 1: Replace imports and rewrite all methods**

```js
import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  fetchArtifacts,
  fetchArtifactDetail,
  fetchArtifactVersions,
  fetchVersionDetail,
  fetchArtifactContent,
  fetchVersionContent,
  fetchVersionDiff,
  restoreArtifactVersion,
  deployArtifact,
  fetchDeployStatus,
  fetchDeployHistory,
  updateArtifactTags
} from '@/api/artifacts'

export const useArtifactStore = defineStore('artifact', () => {
  const artifacts = ref([])
  const current = ref(null)
  const versions = ref([])
  const diffResult = ref(null)
  const deployStatus = ref(null)
  const deployHistory = ref([])
  const loading = ref(false)

  async function loadList(params = {}) {
    loading.value = true
    try {
      const data = await fetchArtifacts(params)
      artifacts.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load artifacts:', err)
    } finally {
      loading.value = false
    }
  }

  async function loadDetail(id) {
    try {
      current.value = await fetchArtifactDetail(id)
    } catch (err) {
      console.warn('Failed to load artifact detail:', err)
    }
  }

  async function loadVersions(id) {
    try {
      const data = await fetchArtifactVersions(id)
      versions.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load versions:', err)
    }
  }

  async function loadDiff(artifactId, fromVersion, toVersion) {
    try {
      const data = await fetchVersionDiff(artifactId, fromVersion, toVersion)
      diffResult.value = data?.diff || ''
    } catch (err) {
      console.warn('Failed to load diff:', err)
    }
  }

  async function restoreVersion(artifactId, versionId) {
    try {
      await restoreArtifactVersion(artifactId, versionId)
      await loadDetail(artifactId)
      await loadVersions(artifactId)
    } catch (err) {
      console.warn('Failed to restore version:', err)
    }
  }

  async function deploy(artifactId, config = {}) {
    try {
      deployStatus.value = { status: 'deploying' }
      await deployArtifact(artifactId, config)
      await loadDeployStatus(artifactId)
    } catch (err) {
      deployStatus.value = { status: 'failed', error: err.message }
    }
  }

  async function loadDeployStatus(artifactId) {
    try {
      deployStatus.value = await fetchDeployStatus(artifactId)
    } catch (err) {
      console.warn('Failed to load deploy status:', err)
    }
  }

  async function loadDeployHistory(artifactId) {
    try {
      const data = await fetchDeployHistory(artifactId)
      deployHistory.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load deploy history:', err)
    }
  }

  async function updateTags(id, tags) {
    try {
      await updateArtifactTags(id, tags)
      if (current.value && current.value.id === id) {
        current.value.tags = tags
      }
    } catch (err) {
      console.warn('Failed to update tags:', err)
    }
  }

  async function loadContent(artifactId) {
    try {
      const data = await fetchArtifactContent(artifactId)
      if (current.value && current.value.id === artifactId) {
        current.value = {
          ...current.value,
          content: data.content,
          contentType: data.contentType,
          downloadUrl: data.downloadUrl
        }
      }
      return data
    } catch (err) {
      console.warn('Failed to load artifact content:', err)
      return null
    }
  }

  async function loadVersionContent(artifactId, versionId) {
    try {
      return await fetchVersionContent(artifactId, versionId)
    } catch (err) {
      console.warn('Failed to load version content:', err)
      return null
    }
  }

  function handleArtifactPreview({ artifactId, artifactType, artifactName, conversationId, previewUrl }) {
    const existing = artifacts.value.find(a => a.id === artifactId)
    if (existing) {
      existing.previewUrl = previewUrl
    } else {
      artifacts.value.unshift({
        id: artifactId,
        artifactType,
        artifactName,
        conversationId,
        previewUrl,
        deployStatus: 'none',
        currentVersion: 1
      })
    }
  }

  return {
    artifacts, current, versions, diffResult, deployStatus, deployHistory, loading,
    loadList, loadDetail, loadVersions, loadDiff,
    handleRestoreVersion, deploy, loadDeployStatus, loadDeployHistory, updateTags,
    loadContent, loadVersionContent, handleArtifactPreview
  }
})
```

- [ ] **Step 2: Verify no broken references**

Run: `cd AIagent_frontend && npx vite build --emptyOutDir false 2>&1 | head -5`
Expected: No import errors referencing artifact store methods. (Build may have warnings about missing backend but no JS errors.)

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/stores/artifact.js AIagent_frontend/src/api/artifacts.js
git commit -m "refactor: use artifacts API module in artifact store, add content/preview methods"
```

---

### Task 6: Frontend — Replace placeholders in ArtifactDetailView.vue

**Files:**
- Modify: `AIagent_frontend/src/views/ArtifactDetailView.vue`

- [ ] **Step 1: Update script section**

Replace the `<script setup>` block:

```vue
<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useArtifactStore } from '@/stores/artifact'
import { renderMarkdown } from '@/composables/useMarkdown'
import { NButton, NTag, NCode, NSpin, NSpace, NTimeline, NTimelineItem, NModal, NSelect, NDynamicTags } from 'naive-ui'

const route = useRoute()
const router = useRouter()
const store = useArtifactStore()
const artifactId = computed(() => Number(route.params.id))

const selectedVersion1 = ref(null)
const selectedVersion2 = ref(null)
const showDiff = ref(false)
const deploying = ref(false)
const contentLoading = ref(false)

const renderedMarkdown = computed(() => renderMarkdown(store.current?.content || ''))

const codeLanguage = computed(() => {
  const ct = store.current?.contentType || ''
  const map = {
    'text/html': 'html',
    'text/css': 'css',
    'text/javascript': 'javascript',
    'application/javascript': 'javascript',
    'text/typescript': 'typescript',
    'text/x-python': 'python',
    'text/x-java': 'java',
    'text/x-go': 'go',
    'text/x-rust': 'rust',
    'application/json': 'json',
    'text/x-yaml': 'yaml',
    'text/markdown': 'markdown',
    'text/x-sh': 'bash',
    'text/x-sql': 'sql'
  }
  return map[ct] || ct.split('/').pop() || 'text'
})

const imageUrl = computed(() => store.current?.downloadUrl || store.current?.filePath || '')

onMounted(async () => {
  await store.loadDetail(artifactId.value)
  await store.loadVersions(artifactId.value)
  contentLoading.value = true
  await store.loadContent(artifactId.value)
  contentLoading.value = false
})

const tags = computed({
  get: () => store.current?.tags || [],
  set: (value) => store.updateTags(artifactId.value, value)
})

async function handleDeploy() {
  deploying.value = true
  try {
    await store.deploy(artifactId.value, { deployTarget: 'static_host' })
    await store.loadDeployHistory(artifactId.value)
  } finally {
    deploying.value = false
  }
}

async function handleRestore(versionId) {
  await store.restoreVersion(artifactId.value, versionId)
}

async function handleDiff() {
  if (selectedVersion1.value && selectedVersion2.value) {
    await store.loadDiff(artifactId.value, selectedVersion1.value, selectedVersion2.value)
    showDiff.value = true
  }
}
</script>
```

- [ ] **Step 2: Update template — center preview area**

Replace lines 82–97 (the entire `.detail-center` div block):

```html
<div class="detail-center">
  <NSpin v-if="contentLoading" />

  <iframe v-else-if="store.current.artifactType === 'html' && store.current.content"
    :srcdoc="store.current.content" sandbox="allow-scripts" class="preview-full" />

  <NCode v-else-if="store.current.artifactType === 'code' && store.current.content"
    :code="store.current.content" :language="codeLanguage" />

  <div v-else-if="store.current.artifactType === 'markdown' && store.current.content"
    v-html="renderedMarkdown" class="markdown-preview" />

  <img v-else-if="store.current.artifactType === 'image' && imageUrl"
    :src="imageUrl" class="image-preview" />

  <div v-else class="generic-preview">文件类型: {{ store.current.artifactType }}</div>
</div>
```

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/views/ArtifactDetailView.vue
git commit -m "feat: replace artifact placeholders with real content loading and rendering"
```

---

### Task 7: Frontend — Wire SSE artifact_preview event in chat.js

**Files:**
- Modify: `AIagent_frontend/src/stores/chat.js` — lines 144–147

- [ ] **Step 1: Replace the console.log handler**

Replace:
```js
sse.on('artifact_preview', (data) => {
  // Phase 3 — artifact store integration point
  console.log('Artifact preview:', data)
})
```

With:
```js
sse.on('artifact_preview', (data) => {
  const artifactStore = useArtifactStore()
  if (data.artifactId) {
    artifactStore.handleArtifactPreview({
      artifactId: data.artifactId,
      artifactType: data.type || 'html',
      artifactName: data.name || 'New Artifact',
      conversationId: conversationId.value,
      previewUrl: data.previewUrl
    })
  }
  addMessageLocal('assistant', data.previewUrl || '', {
    messageType: 'preview_card',
    artifactRefs: [data.artifactId],
    status: 'completed'
  })
})
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/stores/chat.js
git commit -m "feat: wire SSE artifact_preview event to artifact store and chat stream"
```

---

### Task 8: Frontend — Pass artifact prop in MessageBubble.vue

**Files:**
- Modify: `AIagent_frontend/src/components/chat/MessageBubble.vue`

- [ ] **Step 1: Add store import and computed in script**

Add after existing imports:
```js
import { useArtifactStore } from '@/stores/artifact'
```

Add in `<script setup>`, after `const props = defineProps(...)`:
```js
const artifactStore = useArtifactStore()
const previewCardArtifact = computed(() => {
  const refId = props.message?.artifactRefs?.[0]
  if (!refId) return null
  return artifactStore.artifacts.find(a => a.id === refId) || null
})
```

- [ ] **Step 2: Add artifact prop to ArtifactPreviewCard in template**

Change:
```html
<ArtifactPreviewCard
  v-else-if="message.messageType === 'preview_card'"
  :message="message"
  @preview="emit('previewArtifact', $event)"
  @edit="emit('editArtifact', $event)"
  @deploy="emit('deployArtifact', $event)"
  @download="emit('downloadArtifact', $event)"
/>
```

To:
```html
<ArtifactPreviewCard
  v-else-if="message.messageType === 'preview_card'"
  :message="message"
  :artifact="previewCardArtifact"
  @preview="emit('previewArtifact', $event)"
  @edit="emit('editArtifact', $event)"
  @deploy="emit('deployArtifact', $event)"
  @download="emit('downloadArtifact', $event)"
/>
```

The only change is adding `:artifact="previewCardArtifact"`.

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/components/chat/MessageBubble.vue
git commit -m "feat: pass artifact data to ArtifactPreviewCard in chat stream"
```

---

### Task 9: Verification

- [ ] **Step 1: Start backend and frontend**

```bash
# Terminal 1: Backend
cd mateclaw-dev && ./mvnw spring-boot:run

# Terminal 2: Frontend
cd AIagent_frontend && npm run dev
```

- [ ] **Step 2: Verify ArtifactDetailView content loading**

1. Open a conversation that has generated artifacts
2. Navigate to an artifact detail page (e.g., `#/artifacts/1`)
3. Confirm: HTML artifact shows actual content in iframe (not blank)
4. Confirm: Code artifact shows code with correct syntax highlighting in NCode
5. Confirm: Markdown artifact shows rendered HTML
6. Confirm: Image artifact shows the actual image

- [ ] **Step 3: Verify SSE artifact_preview flow**

1. Open a conversation and send a message that triggers artifact generation
2. Confirm: When the backend sends `artifact_preview` SSE event, the artifact appears in the artifact list
3. Confirm: A `preview_card` message appears in the chat stream
4. Confirm: The ArtifactPreviewCard shows the artifact name, type, and version
5. Confirm: Clicking "预览" navigates to ArtifactDetailView

- [ ] **Step 4: Commit any fixes if needed**

```bash
git status
# If changes needed:
git add <files> && git commit -m "fix: address verification issues in artifact preview"
```
