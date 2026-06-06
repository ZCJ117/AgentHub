# Artifact Content Loading & SSE Real-Time Preview Design

Date: 2026-05-23
Status: approved

## Summary

ArtifactDetailView currently shows hardcoded placeholders for code/markdown preview. The SSE `artifact_preview` event is logged but not wired to the artifact store or chat stream. This design adds a backend content endpoint, frontend API/store methods for loading content, SSE-to-store integration, and wires the ArtifactPreviewCard in MessageBubble to display actual artifact data.

## Architecture

```
Backend:  ArtifactController  →  GET /api/v1/artifacts/{id}/content  (new)
                                  GET /api/v1/artifacts/{id}/versions/{versionId}/content  (new)
                                  ↓ JSON { content, contentType, fileName, downloadUrl }

Frontend: src/api/artifacts.js  →  fetchArtifactContent / fetchVersionContent  (new file)
              ↓
          artifactStore  →  loadContent / loadVersionContent / handleArtifactPreview  (new methods)
              ↓                    ↓
     ArtifactDetailView      chat.js SSE handler → appendMessage(preview_card)
                                                         ↓
                                                  MessageBubble → ArtifactPreviewCard (with artifact prop)
```

---

## 1. Backend Content Endpoint

### 1.1 GET /api/v1/artifacts/{id}/content

Returns the latest version's file content as a standard API response.

Response:
```json
{
  "code": 200,
  "data": {
    "content": "<html>...</html>",
    "contentType": "text/html",
    "fileName": "index.html",
    "downloadUrl": null
  }
}
```

- **code/markdown**: `content` is the raw text, `contentType` is `text/plain` or `text/markdown`
- **HTML**: `content` is the HTML string, `contentType` is `text/html`
- **image**: `content` is null, `downloadUrl` is the file download URL

### 1.2 GET /api/v1/artifacts/{id}/versions/{versionId}/content

Same response format, for loading specific historical version content.

---

## 2. Frontend API Layer: src/api/artifacts.js

New file following existing API module patterns (src/api/auth.js, src/api/chat.js, etc.).

```js
import apiClient from './client'

export function fetchArtifactContent(artifactId) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/content`)
}

export function fetchVersionContent(artifactId, versionId) {
  return apiClient.get(`/api/v1/artifacts/${artifactId}/versions/${versionId}/content`)
}
```

The response interceptor in `client.js` unwraps `response.data.data`, so callers receive `{ content, contentType, fileName, downloadUrl }` directly.

---

## 3. Artifact Store: New Methods

### 3.1 loadContent(artifactId)

```js
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
```

### 3.2 loadVersionContent(artifactId, versionId)

Same pattern as loadContent, uses `fetchVersionContent`.

### 3.3 handleArtifactPreview({ artifactId, ... })

```js
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
```

### 3.4 Refactor existing methods

Existing methods (`loadList`, `loadDetail`, `loadVersions`, `loadDiff`, `restoreVersion`, `deploy`, `loadDeployStatus`, `loadDeployHistory`, `updateTags`) currently call `apiClient` directly. Refactor them to use the new `src/api/artifacts.js` functions for consistency.

---

## 4. ArtifactDetailView: Replace Placeholders with Real Content

### 4.1 Template changes

```html
<div class="detail-center">
  <NSpin v-if="contentLoading" />

  <!-- HTML: inline srcdoc from content endpoint -->
  <iframe v-else-if="store.current.artifactType === 'html' && store.current.content"
    :srcdoc="store.current.content" sandbox="allow-scripts" class="preview-full" />

  <!-- Code: NCode with loaded content -->
  <NCode v-else-if="store.current.artifactType === 'code' && store.current.content"
    :code="store.current.content" :language="codeLanguage" />

  <!-- Markdown: render via useMarkdown composable -->
  <div v-else-if="store.current.artifactType === 'markdown' && store.current.content"
    v-html="renderedMarkdown" class="markdown-preview" />

  <!-- Image: use downloadUrl or filePath -->
  <img v-else-if="store.current.artifactType === 'image' && imageUrl"
    :src="imageUrl" class="image-preview" />

  <!-- Fallback: generic preview -->
  <div v-else class="generic-preview">文件类型: {{ store.current.artifactType }}</div>
</div>
```

### 4.2 Script additions

```js
import { renderMarkdown } from '@/composables/useMarkdown'
import { computed } from 'vue'

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
```

### 4.3 Error handling

If content loading fails, `store.current.content` stays falsy, so the fallback `<div class="generic-preview">` shows the artifact type. No separate error UI needed — the generic preview already serves as a degraded state.

---

## 5. SSE artifact_preview Integration: chat.js

Replace the existing placeholder handler:

```js
// Before (line 144):
sse.on('artifact_preview', (data) => {
  console.log('Artifact preview:', data)
})

// After:
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

`addMessageLocal` is in scope within `sendMessage` where all SSE handlers are registered — no export changes needed.

---

## 6. MessageBubble: Pass artifact to ArtifactPreviewCard

### 6.1 Script addition

```js
import { useArtifactStore } from '@/stores/artifact'

const artifactStore = useArtifactStore()
const previewCardArtifact = computed(() => {
  const refId = props.message?.artifactRefs?.[0]
  if (!refId) return null
  return artifactStore.artifacts.find(a => a.id === refId) || null
})
```

### 6.2 Template change

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

The only change is adding `:artifact="previewCardArtifact"` — it was missing before, so the card rendered without artifact name/version/type.

---

## 7. Data Flow Summary

### Page load flow
1. `ArtifactDetailView` mounts → `store.loadDetail(id)` → `store.loadVersions(id)` → `store.loadContent(id)`
2. Content populates `store.current.content`, computed properties render it by type

### SSE flow
1. Backend sends `artifact_preview` event
2. `chat.js` handler calls `artifactStore.handleArtifactPreview()` → upserts into `artifacts[]`
3. `chat.js` handler calls `addMessageLocal()` with `messageType: 'preview_card'`
4. `ChatArea` renders `MessageBubble` → detects `preview_card` → renders `ArtifactPreviewCard` with looked-up artifact
5. User clicks "预览" → `ChatView` routes to `ArtifactDetailView`

---

## 8. Files Changed

| File | Change |
|------|--------|
| `mateclaw-dev/.../ArtifactController.java` | Add `GET /{id}/content` and `GET /{id}/versions/{versionId}/content` endpoints |
| `AIagent_frontend/src/api/artifacts.js` | **New file** — `fetchArtifactContent`, `fetchVersionContent` |
| `AIagent_frontend/src/stores/artifact.js` | Add `loadContent`, `loadVersionContent`, `handleArtifactPreview`; refactor existing methods to use API module |
| `AIagent_frontend/src/views/ArtifactDetailView.vue` | Replace placeholders with content loading, `renderMarkdown`, `NCode`, `NSpin` |
| `AIagent_frontend/src/stores/chat.js` | Replace `console.log` in `artifact_preview` handler with store integration |
| `AIagent_frontend/src/components/chat/MessageBubble.vue` | Look up artifact from store, pass `artifact` prop to `ArtifactPreviewCard` |

## 9. Verification

- Open an HTML artifact in ArtifactDetailView → iframe shows actual HTML content (not blank page)
- Open a code artifact → NCode displays real code with correct language highlighting (not placeholder)
- Open a markdown artifact → rendered HTML shown via DOMPurify + marked
- Send a message that triggers artifact generation → SSE `artifact_preview` event populates the artifact list and inserts a `preview_card` in the chat stream
- Click "预览" on the ArtifactPreviewCard → navigate to ArtifactDetailView
