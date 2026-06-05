# 文件上传功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在单聊和群聊中新增文档文件上传功能，用户可上传 ≤5 个文件（每个 ≤10MB），Agent 能通过绝对路径读取文件内容。

**Architecture:** 前端 Composer 新增拖拽和文件标签，发送时逐个上传文件，消息携带 `contentParts`。后端在 prompt 构建时将文件路径转为绝对路径，`ChatUploadResolver` 增加绝对路径直查和父对话 fallback，`BridgedAgent` 在上下文中显式列出文件。

**Tech Stack:** Vue 3 + Naive UI + Pinia + Java 21 + Spring Boot 3.5

---

## 文件结构

| 操作 | 文件 | 职责 |
|------|------|------|
| 新增 | `AIagent_frontend/src/api/chat.js` | 新增 `uploadFile()` 函数 |
| 修改 | `AIagent_frontend/src/components/chat/Composer.vue` | 拖拽、文件标签、发送时上传 |
| 修改 | `AIagent_frontend/src/stores/chat.js` | `sendMessage` 支持 `contentParts` |
| 修改 | `mateclaw-dev/mateclaw-server/.../ChatController.java` | `buildPromptText()` 路径绝对化 |
| 修改 | `mateclaw-dev/mateclaw-domain/.../ChatUploadResolver.java` | 绝对路径直查 + 父对话 fallback |
| 修改 | `mateclaw-dev/mateclaw-domain/.../BridgedAgent.java` | `buildContextualMessage()` 追加文件列表 |

---

### Task 1: 前端 — 新增 uploadFile API 函数

**Files:**
- Modify: `AIagent_frontend/src/api/chat.js`

- [ ] **Step 1: 添加 uploadFile 函数**

在 `AIagent_frontend/src/api/chat.js` 末尾添加：

```javascript
export function uploadFile(conversationIdOrNull, file) {
  const wsId = localStorage.getItem('ai_agent_workspace_id') || ''
  const token = getToken()
  const formData = new FormData()
  formData.append('file', file)
  const convId = conversationIdOrNull || 'default'
  return fetch(`${BASE}/api/v1/chat/upload?conversationId=${encodeURIComponent(convId)}`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'X-Workspace-Id': wsId
    },
    body: formData
  }).then(res => {
    if (!res.ok) throw new Error(`Upload failed: ${res.status}`)
    return res.json()
  }).then(data => {
    if (data && (data.code === 200 || data.code === '0000')) {
      return data.data
    }
    throw new Error(data?.message || 'Upload failed')
  })
}
```

使用原生 `fetch`（与同文件 `streamChat` 一致），因为需要 `multipart/form-data`，Axios 拦截器会固定 `Content-Type: application/json`。

- [ ] **Step 2: 验证 — 检查代码编译**

```bash
cd AIagent_frontend && npx vite build --mode development 2>&1 | tail -5
```

验证：无语法错误。

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/api/chat.js
git commit -m "feat: add uploadFile API function for file upload"
```

---

### Task 2: 前端 — Composer.vue 增加文件选择和拖拽

**Files:**
- Modify: `AIagent_frontend/src/components/chat/Composer.vue`

- [ ] **Step 1: 在 `<script setup>` 中新增文件相关状态和逻辑**

在现有 import 之后、`const props` 之前插入文件相关代码。修改后的 `<script setup>` 头部：

```javascript
import { ref, watch, computed, nextTick } from 'vue'
import { NButton, NPopover, NList, NListItem, NAvatar, useMessage } from 'naive-ui'
import { useConversationStore } from '@/stores/conversation'
import { useChatStore } from '@/stores/chat'
import { useTextareaAutosize } from '@/composables/useTextareaAutosize'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  isStreaming: { type: Boolean, default: false },
  placeholder: { type: String, default: '输入消息...' },
  prefillText: { type: String, default: '' }
})

const emit = defineEmits(['send', 'stop', 'interrupt'])

const convStore = useConversationStore()
const chatStore = useChatStore()
const text = ref('')
const { textarea, onInput: autosizeOnInput } = useTextareaAutosize()
const message = useMessage()

// ── 文件上传状态 ──
const SUPPORTED_TYPES = [
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'text/plain',
  'text/markdown',
  'text/x-markdown'
]
const MAX_FILE_SIZE = 10 * 1024 * 1024  // 10MB
const MAX_FILE_COUNT = 5

const pendingFiles = ref([])        // { id, name, size, type, file: File }
const isDragging = ref(false)
const uploadErrors = ref(new Map()) // id → error message
let fileIdCounter = 0

function acceptFile(file) {
  if (!SUPPORTED_TYPES.includes(file.type) && !file.name.match(/\.(md|txt)$/i)) {
    message.warning(`不支持的文件类型: ${file.name}`)
    return false
  }
  if (file.size > MAX_FILE_SIZE) {
    message.warning(`${file.name} 超过 10MB 限制`)
    return false
  }
  return true
}

function addFiles(files) {
  const remaining = MAX_FILE_COUNT - pendingFiles.value.length
  if (remaining <= 0) {
    message.warning(`最多上传 ${MAX_FILE_COUNT} 个文件`)
    return
  }
  const toAdd = []
  for (const f of files) {
    if (toAdd.length >= remaining) {
      message.warning(`最多上传 ${MAX_FILE_COUNT} 个文件，已截断`)
      break
    }
    if (acceptFile(f)) {
      toAdd.push({ id: ++fileIdCounter, name: f.name, size: f.size, type: f.type, file: f })
    }
  }
  pendingFiles.value = [...pendingFiles.value, ...toAdd]
}

function removeFile(id) {
  pendingFiles.value = pendingFiles.value.filter(f => f.id !== id)
  uploadErrors.value.delete(id)
}

function handleFileInput(e) {
  if (e.target.files?.length) addFiles(e.target.files)
  e.target.value = ''
}

function onDragOver(e) {
  e.preventDefault()
  isDragging.value = true
}

function onDragLeave() {
  isDragging.value = false
}

function onDrop(e) {
  e.preventDefault()
  isDragging.value = false
  if (e.dataTransfer?.files?.length) addFiles(e.dataTransfer.files)
}
// ── 文件上传状态结束 ──
```

- [ ] **Step 2: 修改 `handleSend` 函数，加入文件上传逻辑**

将原来的 `handleSend` 替换为：

```javascript
async function handleSend() {
  const val = text.value.trim()
  if (!val || props.disabled) return
  if (pendingFiles.value.length === 0) {
    emit('send', val)
    chatStore.clearReplyTo()
    text.value = ''
    return
  }
  // 有文件：发送时上传，携带 contentParts
  emit('send', val, pendingFiles.value.map(f => f.file))
  chatStore.clearReplyTo()
  text.value = ''
  pendingFiles.value = []
  uploadErrors.value.clear()
}
```

- [ ] **Step 3: 修改模板 — 在 composer-inner 内部、textarea 上方添加文件标签和拖拽覆盖层**

将模板中 `<div class="composer-inner">` 内部的内容修改为：

```html
<template>
  <div
    class="composer"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div v-if="isDragging" class="drop-overlay">
      <div class="drop-hint">释放以上传文件</div>
    </div>
    <div class="composer-inner">
      <div v-if="chatStore.replyTo" class="reply-indicator">
        <span class="reply-indicator-text">正在回复 <strong>{{ chatStore.replyTo.senderName }}</strong>：{{ chatStore.replyTo.preview }}</span>
        <NButton text size="tiny" @click="chatStore.clearReplyTo()">✕ 取消</NButton>
      </div>

      <!-- 文件标签 -->
      <div v-if="pendingFiles.length > 0" class="file-tags">
        <span
          v-for="f in pendingFiles"
          :key="f.id"
          class="file-tag"
          :class="{ 'file-tag-error': uploadErrors.has(f.id) }"
        >
          📄 {{ f.name }}
          <span class="file-tag-size">{{ formatSize(f.size) }}</span>
          <span class="file-tag-remove" @click="removeFile(f.id)">✕</span>
        </span>
      </div>

      <NPopover
        :show="mentionOpen"
        trigger="manual"
        placement="top-start"
        :width="280"
        display-directive="show"
      >
        <template #trigger>
          <textarea
            ref="textarea"
            v-model="text"
            class="composer-input"
            :placeholder="placeholder"
            :disabled="disabled"
            rows="1"
            @input="onInput"
            @keydown="handleKeydown"
            @blur="closeMention"
            @paste="onPaste"
          />
        </template>
        <div class="mention-popover">
          <div class="mention-title">选择 Agent</div>
          <NList
            v-if="filteredMembers.length > 0"
            hoverable
            clickable
            :show-divider="false"
            style="max-height: 240px; overflow-y: auto"
          >
            <NListItem
              v-for="(member, idx) in filteredMembers"
              :key="member.agentId"
              :class="{ 'mention-item-active': idx === mentionIndex }"
              @click="selectMember(member)"
            >
              <template #prefix>
                <NAvatar v-if="member.avatarUrl" :size="28" round :src="member.avatarUrl">
                  <template #fallback>
                    {{ (member.agentName || '?')[0] }}
                  </template>
                </NAvatar>
                <NAvatar v-else :size="28" round>
                  {{ (member.agentName || '?')[0] }}
                </NAvatar>
              </template>
              {{ member.agentName }}
            </NListItem>
          </NList>
          <div v-else class="mention-empty">无匹配 Agent</div>
        </div>
      </NPopover>

      <!-- 文件选择按钮 -->
      <NButton text class="attach-btn" @click="$refs.fileInput.click()" title="添加文件">
        📎
      </NButton>
      <input
        ref="fileInput"
        type="file"
        multiple
        style="display:none"
        @change="handleFileInput"
      />

      <NButton
        v-if="isStreaming"
        type="warning"
        ghost
        @click="handleInterrupt"
        class="send-btn"
      >
        打断
      </NButton>
      <NButton
        v-if="!isStreaming"
        type="primary"
        :disabled="!text.trim()"
        @click="handleSend"
        class="send-btn"
      >
        发送
      </NButton>
      <NButton
        v-if="isStreaming"
        type="error"
        @click="emit('stop')"
        class="send-btn"
      >
        停止
      </NButton>
    </div>
  </div>
</template>
```

- [ ] **Step 4: 在 `<script setup>` 末尾添加辅助函数**

```javascript
function formatSize(bytes) {
  if (bytes < 1024) return bytes + 'B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'KB'
  return (bytes / (1024 * 1024)).toFixed(1) + 'MB'
}

function onPaste(e) {
  // 暂不处理粘贴文件
}
```

- [ ] **Step 5: 添加拖拽覆盖层和文件标签的样式**

在 `<style scoped>` 末尾追加：

```css
.drop-overlay {
  position: absolute;
  inset: 0;
  background: rgba(46, 117, 182, 0.08);
  border: 2px dashed #2E75B6;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  pointer-events: none;
}

.drop-hint {
  font-size: 16px;
  color: #2E75B6;
  font-weight: 500;
}

.file-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  width: 100%;
  margin-bottom: 6px;
}

.file-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: #F0F4FF;
  border: 1px solid #C4D7FF;
  border-radius: 6px;
  padding: 3px 8px;
  font-size: 12px;
  color: #333;
}

.file-tag-error {
  background: #FFF3F0;
  border-color: #FFC4B8;
  color: #E74C3C;
}

.file-tag-size {
  color: #999;
  font-size: 11px;
}

.file-tag-remove {
  cursor: pointer;
  color: #999;
  margin-left: 2px;
}

.file-tag-remove:hover {
  color: #E74C3C;
}

.attach-btn {
  font-size: 18px;
  color: #999;
  padding: 4px 8px;
  flex-shrink: 0;
}

.attach-btn:hover {
  color: #2E75B6;
}
```

将 `.composer` 样式改为 `position: relative;`（添加此属性）。

- [ ] **Step 6: 验证 — 前端编译通过**

```bash
cd AIagent_frontend && npx vite build --mode development 2>&1 | tail -5
```

验证：无编译错误。

- [ ] **Step 7: Commit**

```bash
git add AIagent_frontend/src/components/chat/Composer.vue
git commit -m "feat: add file selection, drag-drop, and file tags to Composer"
```

---

### Task 3: 前端 — chat.js store 的 sendMessage 支持 contentParts

**Files:**
- Modify: `AIagent_frontend/src/stores/chat.js`

- [ ] **Step 1: 修改 `sendMessage` 函数签名和逻辑**

在 `AIagent_frontend/src/stores/chat.js` 中，将 `sendMessage` 函数的开头部分修改为支持 `files` 参数。找到函数签名处（约第 159 行）：

```javascript
// 改前:
async function sendMessage(text, agentId, options = {}) {

// 改后:
async function sendMessage(text, agentId, options = {}) {
```

在 `sendMessage` 函数体内，`if (!text.trim() || isStreaming.value) return` 之后、`streamError.value = ''` 之前，插入文件上传逻辑：

```javascript
  async function sendMessage(text, agentId, options = {}) {
    console.log('[chatStore] sendMessage called, text:', text, 'agentId:', agentId, 'isStreaming:', isStreaming.value)
    if (!text.trim() || isStreaming.value) {
      console.log('[chatStore] sendMessage blocked — empty text or already streaming')
      return
    }

    // ── 文件上传 ──
    const uploadFiles = options.files
    let contentParts = options.contentParts || null
    if (uploadFiles && uploadFiles.length > 0) {
      const { uploadFile } = await import('@/api/chat')
      const uploaded = []
      for (const file of uploadFiles) {
        try {
          const data = await uploadFile(conversationId.value || 'default', file)
          uploaded.push({
            type: 'file',
            fileName: data.fileName,
            storedName: data.storedName,
            path: data.path,
            contentType: data.contentType,
            fileSize: data.size
          })
        } catch (err) {
          console.warn('[chatStore] File upload failed:', file.name, err)
          streamError.value = `文件 ${file.name} 上传失败: ${err.message}`
          return  // 不上传失败时发送消息
        }
      }
      contentParts = uploaded
    }
    // ── 文件上传结束 ──

    streamError.value = ''
    addMessageLocal('user', text, { replyToId: replyTo.value?.id || null })
```

- [ ] **Step 2: 修改 SSE 连接时的 body 参数**

找到 `sse.connect` 调用处（约第 443 行），将 options 展开改为显式包含 contentParts：

```javascript
    sse.connect((signal) => streamChat({
      agentId,
      message: text,
      conversationId: conversationId.value || null,
      contentParts: contentParts,
      ...options
    }, signal))
```

确保 `contentParts` 优先使用上传产生的值，options 中的其他属性（如 `thinkingLevel`）正常传递。

- [ ] **Step 3: 验证 — 前端编译通过**

```bash
cd AIagent_frontend && npx vite build --mode development 2>&1 | tail -5
```

验证：无编译错误。

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/stores/chat.js
git commit -m "feat: support contentParts with uploaded files in sendMessage"
```

---

### Task 4: 后端 — buildPromptText() 路径绝对化

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/channel/web/ChatController.java:1779-1782`

- [ ] **Step 1: 修改 file/image/video 分支，将路径转为绝对路径**

`Path` 和 `Paths` 已在文件头部导入（第 35-36 行）。将第 1780-1782 行：

```java
case "file" -> appendPromptLine(builder, "附件: " + safe(part.getFileName()) + " (" + safe(part.getPath()) + ")");
case "image" -> appendPromptLine(builder, "图片附件: " + safe(part.getFileName()) + " (" + safe(part.getPath()) + ")");
case "video" -> appendPromptLine(builder, "视频附件: " + safe(part.getFileName()) + " (" + safe(part.getPath()) + ")");
```

改为：

```java
case "file" -> {
    Path abs = part.getPath() != null ? Paths.get(part.getPath()).toAbsolutePath().normalize() : null;
    appendPromptLine(builder, "附件: " + safe(part.getFileName()) + " (" + safe(abs != null ? abs.toString() : part.getPath()) + ")");
}
case "image" -> {
    Path abs = part.getPath() != null ? Paths.get(part.getPath()).toAbsolutePath().normalize() : null;
    appendPromptLine(builder, "图片附件: " + safe(part.getFileName()) + " (" + safe(abs != null ? abs.toString() : part.getPath()) + ")");
}
case "video" -> {
    Path abs = part.getPath() != null ? Paths.get(part.getPath()).toAbsolutePath().normalize() : null;
    appendPromptLine(builder, "视频附件: " + safe(part.getFileName()) + " (" + safe(abs != null ? abs.toString() : part.getPath()) + ")");
}
```

- [ ] **Step 2: 编译验证**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-server -q 2>&1 | tail -5
```

验证：无编译错误。

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/channel/web/ChatController.java
git commit -m "feat: output absolute file paths in buildPromptText for cross-agent access"
```

---

### Task 5: 后端 — ChatUploadResolver 增加绝对路径和父对话 fallback

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/tool/builtin/ChatUploadResolver.java`

- [ ] **Step 1: 在 resolve() 方法开头增加绝对路径直查逻辑**

在 `ChatUploadResolver.java` 的 `resolve` 方法中，`if (rawPath == null || rawPath.isBlank())` 之后、获取 `conversationId` 之前，插入：

```java
    static Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        // 绝对路径：直接检查文件是否存在
        Path absPath = Paths.get(rawPath).toAbsolutePath().normalize();
        if (absPath.isAbsolute() && Files.isRegularFile(absPath)) {
            return absPath;
        }
        String conversationId = ToolExecutionContext.conversationId();
        // ... 后续原有逻辑不变
```

- [ ] **Step 2: 在原有查找失败后增加父对话 fallback**

在方法末尾 `return null;` 之前（suffix 匹配失败后），增加：

```java
        // 原有 suffix 匹配逻辑 ...
        try (var stream = Files.list(uploadDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("[ChatUploadResolver] Failed to scan chat-upload dir {}: {}", uploadDir, e.getMessage());
            return null;
        }

        // ── 新增: 父对话 fallback ──
        // 如果是子对话（delegateToAgent 创建），在父对话的 uploads 目录中查找
        // 注: 需要注入 ConversationMapper 或通过 ToolExecutionContext 获取 parentConversationId
        // 此处通过 conversationId 查找 ConversationEntity.parentConversationId
    }
```

由于 `ChatUploadResolver` 是 package-private 的工具类且当前无依赖注入，需要改为通过 `ToolExecutionContext` 获取 `parentConversationId`。先在 `ToolExecutionContext` 中增加该字段：

- [ ] **Step 3: 在 ToolExecutionContext 中增加 parentConversationId**

读取 `ToolExecutionContext.java`（位于同包），增加静态字段和方法：

```java
private static final ThreadLocal<String> PARENT_CONVERSATION_ID = new ThreadLocal<>();

public static void setParentConversationId(String id) { PARENT_CONVERSATION_ID.set(id); }
public static String parentConversationId() { return PARENT_CONVERSATION_ID.get(); }
public static void clearParentConversationId() { PARENT_CONVERSATION_ID.remove(); }
```

- [ ] **Step 4: 完成 ChatUploadResolver 的父对话 fallback 逻辑**

在 suffix 匹配失败后追加：

```java
        // 父对话 fallback
        String parentConvId = ToolExecutionContext.parentConversationId();
        if (parentConvId != null && !parentConvId.isBlank()) {
            Path parentDir = CHAT_UPLOAD_ROOT.resolve(parentConvId).toAbsolutePath().normalize();
            if (Files.isDirectory(parentDir)) {
                Path parentDirect = parentDir.resolve(basename);
                if (Files.isRegularFile(parentDirect)) {
                    return parentDirect;
                }
                try (var parentStream = Files.list(parentDir)) {
                    return parentStream
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(suffix))
                            .findFirst()
                            .orElse(null);
                }
            }
        }
        return null;
```

- [ ] **Step 5: 在 delegateToAgent 中设置 parentConversationId**

修改 `DelegateAgentTool.java` 的 `runSingleChild` 方法（约第 882 行），在执行子 Agent 之前设置 parentConversationId：

在 `agentService.chat(...)` 调用之前添加：
```java
ToolExecutionContext.setParentConversationId(parentConversationId);
```
在 finally 块中清理：
```java
ToolExecutionContext.clearParentConversationId();
```

- [ ] **Step 6: 编译验证**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-domain -q 2>&1 | tail -5
```

- [ ] **Step 7: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/tool/builtin/ChatUploadResolver.java \
        mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/tool/builtin/ToolExecutionContext.java \
        mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/tool/builtin/DelegateAgentTool.java
git commit -m "feat: add absolute path lookup and parent conversation fallback to ChatUploadResolver"
```

---

### Task 6: 后端 — BridgedAgent 上下文追加文件列表

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/infra/agent/bridge/BridgedAgent.java`

- [ ] **Step 1: 修改 buildContextualMessage() 开头追加文件列表**

`BridgedAgent` 不是 Spring Bean，没有注入 `ObjectMapper`。使用 `new ObjectMapper()` 直接创建即可（轻量、默认配置足够）。需要新增 import：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.domain.workspace.conversation.model.MessageContentPart;
```

在 `BridgedAgent.java` 第 183 行 `StringBuilder ctx = new StringBuilder();` 之后、`ctx.append("# 以下是对话历史上下文\n\n");` 之前插入：

```java
            StringBuilder ctx = new StringBuilder();

            // ── 新增: 提取用户上传的文件列表 ──
            ObjectMapper objectMapper = new ObjectMapper();
            List<MessageEntity> fileMessages = conversationService.listRecentMessages(conversationId, 50);
            List<String> fileLines = new ArrayList<>();
            for (MessageEntity msg : fileMessages) {
                if ("user".equals(msg.getRole()) && msg.getContentParts() != null) {
                    try {
                        List<MessageContentPart> parts = objectMapper.readValue(
                            msg.getContentParts(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, MessageContentPart.class)
                        );
                        for (MessageContentPart part : parts) {
                            if ("file".equals(part.getType()) && part.getFileName() != null) {
                                java.nio.file.Path absPath = part.getPath() != null
                                    ? java.nio.file.Paths.get(part.getPath()).toAbsolutePath().normalize()
                                    : null;
                                fileLines.add("- " + part.getFileName() + ": "
                                    + (absPath != null ? absPath.toString() : part.getPath()));
                            }
                        }
                    } catch (Exception ignored) { }
                }
            }
            if (!fileLines.isEmpty()) {
                ctx.append("[用户上传的文件]\n");
                for (String line : fileLines) {
                    ctx.append(line).append("\n");
                }
                ctx.append("\n");
            }
            // ── 文件列表结束 ──

            ctx.append("# 以下是对话历史上下文\n\n");
```

- [ ] **Step 2: 编译验证**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-domain -q 2>&1 | tail -5
```

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/infra/agent/bridge/BridgedAgent.java
git commit -m "feat: append uploaded file list to BridgedAgent context for CLI sub-agents"
```

---

### Task 7: 端到端手动验证

- [ ] **Step 1: 启动后端**

```bash
cd mateclaw-dev && mvn spring-boot:run -pl mateclaw-server
```

验证：后端启动在 18088 端口，无报错。

- [ ] **Step 2: 启动前端**

```bash
cd AIagent_frontend && npm run dev
```

验证：前端启动在 3000 端口。

- [ ] **Step 3: 单聊文件上传测试**

1. 登录 → 进入聊天页面
2. 点击 📎 按钮选择 PDF/TXT/MD 文件
3. 验证文件标签显示文件名和大小
4. 输入消息"请分析这个文件" → 点击发送
5. 验证：Agent 返回了文件内容相关的回答
6. 刷新页面 → 验证消息历史中文件引用正确保留

- [ ] **Step 4: 拖拽上传测试**

1. 从桌面拖入一个 .txt 文件到聊天区域
2. 验证拖拽时显示"释放以上传文件"覆盖层
3. 释放后文件加入标签列表

- [ ] **Step 5: 边界测试**

- 选择 6 个文件 → 验证第 6 个被截断 + toast
- 选择 >10MB 文件 → 验证 toast 提示超限
- 选择 .exe 文件 → 验证 toast 提示不支持
- 无文件纯文本发送 → 验证行为不变

- [ ] **Step 6: 群聊文件上传测试**

1. 创建或进入群聊
2. 上传 PDF → 发送"请根据这个文件内容分配任务"
3. 验证 Orchestrator 返回了任务分解（说明读到了文件）
4. 验证子 Agent 也能引用文件内容

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: end-to-end verification notes for file upload feature"
```
