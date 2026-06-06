# Write File → Artifact Preview 渲染链路修复 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agent 调用 `write_file` 时自动推送 `artifact_preview` SSE 事件，前端聊天区展示可交互的产物卡片。

**Architecture:** 在 `ToolExecutionExecutor` 中将 `workspaceBasePath` 附带到 `tool_call_completed` 事件中，`StreamAccumulator` 拦截该事件、读取文件内容、调用 `emitArtifactPreview()` 推送给前端，前端 `ArtifactPreviewCard` 根据文件类型渲染代码块或 Markdown。

**Tech Stack:** Java 21 / Spring Boot / Vue 3 (Composition API) / Naive UI / Pinia

---

### Task 1: GraphEventPublisher — 新增 toolComplete 重载

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/agent/GraphEventPublisher.java:113-131`

- [ ] **Step 1: 新增带 workspaceBasePath 的 toolComplete 重载**

在现有的 4 参数版 `toolComplete`（行 117-131）下方新增重载。用 `LinkedHashMap` 替代 `Map.of` 以支持可选字段。

```java
// GraphEventPublisher.java — 在 public static GraphEvent toolComplete(
// String toolCallId, String toolName, String result, boolean success) 方法后面新增:

public static GraphEvent toolComplete(String toolCallId, String toolName, String result,
                                       boolean success, String workspaceBasePath) {
    long ts = System.currentTimeMillis();
    Map<String, Object> data = new java.util.LinkedHashMap<>();
    data.put("toolCallId", toolCallId != null ? toolCallId : "");
    data.put("toolName", toolName);
    data.put("result", result != null ? result : "");
    data.put("success", success);
    data.put("timestamp", ts);
    if (workspaceBasePath != null && !workspaceBasePath.isBlank()) {
        data.put("workspaceBasePath", workspaceBasePath);
    }
    return new GraphEvent(EVENT_TOOL_COMPLETE, Map.copyOf(data), ts);
}
```

- [ ] **Step 2: 验证编译**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-domain -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/agent/GraphEventPublisher.java
git commit -m "feat: add toolComplete overload with workspaceBasePath parameter"
```

---

### Task 2: ToolExecutionExecutor — 传递 workspaceBasePath

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/agent/graph/executor/ToolExecutionExecutor.java:866-871,884-888`

- [ ] **Step 1: 成功路径传入 workspaceBasePath**

修改行 866-869，将 `pc.workspaceBasePath` 传入新重载：

```java
// 原代码（行 866-869）:
// events.add(GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, result, true));
// if (streamTracker != null) {
//     streamTracker.broadcastObject(pc.conversationId, GraphEventPublisher.EVENT_TOOL_COMPLETE,
//             GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, result, true).data());
//     streamTracker.updateRunningTool(pc.conversationId, null);
// }

// 改为:
events.add(GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, result, true,
        pc.workspaceBasePath));
if (streamTracker != null) {
    streamTracker.broadcastObject(pc.conversationId, GraphEventPublisher.EVENT_TOOL_COMPLETE,
            GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, result, true,
                    pc.workspaceBasePath).data());
    streamTracker.updateRunningTool(pc.conversationId, null);
}
```

- [ ] **Step 2: 失败路径传入 workspaceBasePath**

修改行 884-888，同样传入 `pc.workspaceBasePath`：

```java
// 原代码（行 884-888）:
// events.add(GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, reportedError, false));
// if (streamTracker != null) {
//     streamTracker.broadcastObject(pc.conversationId, GraphEventPublisher.EVENT_TOOL_COMPLETE,
//             GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, reportedError, false).data());
//     streamTracker.updateRunningTool(pc.conversationId, null);
// }

// 改为:
events.add(GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, reportedError, false,
        pc.workspaceBasePath));
if (streamTracker != null) {
    streamTracker.broadcastObject(pc.conversationId, GraphEventPublisher.EVENT_TOOL_COMPLETE,
            GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, reportedError, false,
                    pc.workspaceBasePath).data());
    streamTracker.updateRunningTool(pc.conversationId, null);
}
```

- [ ] **Step 3: 验证编译**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-domain -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/agent/graph/executor/ToolExecutionExecutor.java
git commit -m "feat: pass workspaceBasePath in tool_call_completed events"
```

---

### Task 3: ChatStreamTracker — 扩展 emitArtifactPreview 签名

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/infra/channel/web/ChatStreamTracker.java:1822-1830`

- [ ] **Step 1: 替换方法签名，新增 name 和 content 参数**

```java
// 替换原有的 emitArtifactPreview 方法:

public void emitArtifactPreview(String conversationId, long artifactId,
                                String type, String name, String content,
                                String previewUrl) {
    if (conversationId == null || conversationId.isBlank()) return;
    broadcastObject(conversationId, "artifact_preview", Map.of(
            "artifactId", artifactId,
            "type", type != null ? type : "",
            "name", name != null ? name : "",
            "content", content != null ? content : "",
            "previewUrl", previewUrl != null ? previewUrl : ""
    ));
}
```

- [ ] **Step 2: 验证编译**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-domain -q
```

Expected: BUILD SUCCESS (确认没有其他调用者编译报错——`emitArtifactPreview` 目前零调用者，不会有编译错误)

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/infra/channel/web/ChatStreamTracker.java
git commit -m "feat: extend emitArtifactPreview with name and content fields"
```

---

### Task 4: ChatController.StreamAccumulator — 核心拦截 write_file

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/channel/web/ChatController.java:2136-2171`

- [ ] **Step 1: 在 accumulateToolEvent 的 tool_call_completed 分支末尾添加拦截**

在行 2170（`}` 闭合 tool_call_completed 分支）之前插入：

```java
                // 拦截 write_file 工具完成，推送 artifact_preview
                if ("write_file".equals(toolName)
                        && Boolean.TRUE.equals(data.getOrDefault("success", false))) {
                    String wsPath = String.valueOf(data.getOrDefault("workspaceBasePath", ""));
                    String resultStr = String.valueOf(data.getOrDefault("result", ""));
                    if (!wsPath.isBlank() && !resultStr.isBlank()) {
                        interceptWriteFileForPreview(conversationId, resultStr, wsPath);
                    }
                }
```

- [ ] **Step 2: 新增 interceptWriteFileForPreview 私有方法**

在 `accumulateToolEvent` 方法下方（行 2171 之后、`ensurePlanStepCapacity` 之前）新增：

```java
        /**
         * 拦截 write_file 工具结果：读取刚写入的文件内容，推送 artifact_preview SSE 事件。
         * 异常时静默跳过，不中断 SSE 流。
         */
        private void interceptWriteFileForPreview(String conversationId,
                                                   String resultJson,
                                                   String workspaceBasePath) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(resultJson, Map.class);
                String filePath = String.valueOf(parsed.getOrDefault("filePath", ""));
                if (filePath.isBlank()) return;

                String artifactType = inferArtifactType(filePath);
                // 拼接绝对路径读取文件
                java.nio.file.Path absPath = java.nio.file.Path.of(workspaceBasePath, filePath);
                if (!java.nio.file.Files.exists(absPath)) return;

                String content = java.nio.file.Files.readString(absPath);
                // 文件过大时截断
                if (content.length() > 512 * 1024) {
                    content = content.substring(0, 512 * 1024) + "\n\n[Content truncated at 512KB]";
                }

                long artifactId = Math.abs((long) (conversationId + filePath).hashCode());
                String name = absPath.getFileName().toString();

                streamTracker.emitArtifactPreview(conversationId, artifactId,
                        artifactType, name, content, /* previewUrl */ "");
            } catch (Exception e) {
                log.debug("[StreamAccumulator] Failed to emit artifact preview for write_file: {}",
                        e.getMessage());
            }
        }

        /** 根据文件扩展名确定 artifact 类型 */
        private static String inferArtifactType(String filePath) {
            String lower = filePath.toLowerCase();
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return "website";
            if (lower.endsWith(".md")) return "document";
            if (lower.endsWith(".css") || lower.endsWith(".scss")
                    || lower.endsWith(".less") || lower.endsWith(".sass")) return "stylesheet";
            if (lower.endsWith(".json") || lower.endsWith(".yaml")
                    || lower.endsWith(".yml") || lower.endsWith(".xml")
                    || lower.endsWith(".toml")) return "data";
            // code 类型涵盖常见编程语言
            String[] codeExts = {".js", ".ts", ".jsx", ".tsx", ".vue", ".py", ".java",
                    ".go", ".rs", ".rb", ".php", ".c", ".cpp", ".h",
                    ".cs", ".swift", ".kt", ".scala"};
            for (String ext : codeExts) {
                if (lower.endsWith(ext)) return "code";
            }
            return "file";
        }
```

- [ ] **Step 3: 验证编译**

```bash
cd mateclaw-dev && mvn compile -pl mateclaw-server -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/channel/web/ChatController.java
git commit -m "feat: intercept write_file tool results to emit artifact_preview SSE events"
```

---

### Task 5: 前端 chat.js — artifact_preview 处理器传 content

**Files:**
- Modify: `AIagent_frontend/src/stores/chat.js:259-275`

- [ ] **Step 1: 传递 content 给 handleArtifactPreview**

```javascript
// 原代码 (行 259-275):
// sse.on('artifact_preview', (data) => {
//   const artifactStore = useArtifactStore()
//   if (data.artifactId) {
//     artifactStore.handleArtifactPreview({
//       artifactId: data.artifactId,
//       artifactType: data.type || 'html',
//       artifactName: data.name || 'New Artifact',
//       conversationId: conversationId.value,
//       previewUrl: data.previewUrl
//     })
//     addMessageLocal('assistant', data.previewUrl || '', {
//       messageType: 'preview_card',
//       artifactRefs: [data.artifactId],
//       status: 'completed'
//     })
//   }
// })

// 改为:
sse.on('artifact_preview', (data) => {
  const artifactStore = useArtifactStore()
  if (data.artifactId) {
    artifactStore.handleArtifactPreview({
      artifactId: data.artifactId,
      artifactType: data.type || 'html',
      artifactName: data.name || 'New Artifact',
      conversationId: conversationId.value,
      previewUrl: data.previewUrl,
      content: data.content || ''
    })
    addMessageLocal('assistant', data.content || data.previewUrl || '', {
      messageType: 'preview_card',
      artifactRefs: [data.artifactId],
      status: 'completed'
    })
  }
})
```

- [ ] **Step 2: 验证前端编译**

```bash
cd AIagent_frontend && npx vite build --mode development 2>&1 | tail -5
```

Expected: 无报错（如无 vite build 环境，`npx vite --version` 确认工具链可用即可）

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/stores/chat.js
git commit -m "feat: pass content field from artifact_preview SSE event to artifact store"
```

---

### Task 6: 前端 artifact.js — handleArtifactPreview 存储 content

**Files:**
- Modify: `AIagent_frontend/src/stores/artifact.js:139-156`

- [ ] **Step 1: 接受并存储 content 字段**

```javascript
// 原代码 (行 139):
// function handleArtifactPreview({ artifactId, artifactType, artifactName, conversationId, previewUrl }) {

// 改为:
function handleArtifactPreview({ artifactId, artifactType, artifactName, conversationId, previewUrl, content }) {
  const existing = artifacts.value.find(a => a.id === artifactId)
  if (existing) {
    existing.previewUrl = previewUrl
    existing.filePath = previewUrl
    existing.content = content || existing.content || ''
  } else {
    artifacts.value.unshift({
      id: artifactId,
      artifactType,
      artifactName,
      conversationId,
      previewUrl,
      filePath: previewUrl,
      content: content || '',
      deployStatus: 'none',
      currentVersion: 1
    })
  }
}
```

- [ ] **Step 2: 验证前端编译**

```bash
cd AIagent_frontend && npx vite build --mode development 2>&1 | tail -5
```

Expected: 无报错

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/stores/artifact.js
git commit -m "feat: store content field in artifact preview state"
```

---

### Task 7: 前端 ArtifactPreviewCard — 扩展渲染 code/document 类型

**Files:**
- Modify: `AIagent_frontend/src/components/chat/ArtifactPreviewCard.vue`

- [ ] **Step 1: 更新 script 部分**

```vue
<script setup>
import { NButton, NSpace, NCode } from 'naive-ui'
import { renderMarkdown } from '@/composables/useMarkdown'
import { computed } from 'vue'

const props = defineProps({
  message: { type: Object, required: true },
  artifact: { type: Object, default: null }
})

const emit = defineEmits(['preview', 'edit', 'deploy', 'download'])

const showCodeBlock = computed(() => {
  const t = props.artifact?.artifactType
  return t === 'website' || t === 'code' || t === 'data' || t === 'stylesheet'
})

const showDocument = computed(() => props.artifact?.artifactType === 'document')

const codeLanguage = computed(() => {
  const name = props.artifact?.artifactName || ''
  if (name.endsWith('.html') || name.endsWith('.htm')) return 'html'
  if (name.endsWith('.js')) return 'javascript'
  if (name.endsWith('.ts')) return 'typescript'
  if (name.endsWith('.vue')) return 'html'
  if (name.endsWith('.css')) return 'css'
  if (name.endsWith('.scss')) return 'scss'
  if (name.endsWith('.json')) return 'json'
  if (name.endsWith('.yaml') || name.endsWith('.yml')) return 'yaml'
  if (name.endsWith('.xml')) return 'xml'
  if (name.endsWith('.md')) return 'markdown'
  if (name.endsWith('.py')) return 'python'
  if (name.endsWith('.java')) return 'java'
  if (name.endsWith('.go')) return 'go'
  if (name.endsWith('.sql')) return 'sql'
  return 'text'
})

const renderedMd = computed(() => renderMarkdown(props.artifact?.content || ''))
</script>
```

- [ ] **Step 2: 更新 template 部分**

```vue
<template>
  <div class="artifact-preview-card">
    <div class="artifact-header">
      <span class="artifact-name">{{ artifact?.artifactName || '产物' }}</span>
      <span v-if="artifact?.currentVersion" class="artifact-version">v{{ artifact.currentVersion }}</span>
    </div>

    <!-- code/website/data/stylesheet: NCode 代码块 -->
    <div v-if="showCodeBlock && artifact?.content" class="artifact-code">
      <NCode :code="artifact.content" :language="codeLanguage" />
    </div>

    <!-- document: Markdown 渲染 -->
    <div v-if="showDocument && artifact?.content" class="artifact-markdown">
      <div class="markdown-body" v-html="renderedMd" />
    </div>

    <NSpace class="artifact-actions">
      <NButton size="small" @click="emit('preview', artifact?.id)">预览</NButton>
      <NButton size="small" @click="emit('edit', artifact?.id)">编辑</NButton>
      <NButton size="small" type="primary" @click="emit('deploy', artifact?.id)">部署</NButton>
      <NButton size="small" @click="emit('download', artifact?.id)">下载</NButton>
    </NSpace>
  </div>
</template>
```

- [ ] **Step 3: 更新 style 部分**

在现有 `<style scoped>` 块末尾追加：

```css
.artifact-code {
  padding: 0;
  max-height: 400px;
  overflow: auto;
}

.artifact-markdown {
  padding: 12px 16px;
  font-size: 14px;
  line-height: 1.6;
  color: #1D1D1F;
}

.markdown-body :deep(pre) {
  background: #F5F5F7;
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
}

.markdown-body :deep(code) {
  font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
  font-size: 13px;
}
```

- [ ] **Step 4: 验证前端编译**

```bash
cd AIagent_frontend && npx vite build --mode development 2>&1 | tail -10
```

Expected: 无报错

- [ ] **Step 5: Commit**

```bash
git add AIagent_frontend/src/components/chat/ArtifactPreviewCard.vue
git commit -m "feat: render code blocks and markdown in ArtifactPreviewCard"
```

---

### Task 8: 端到端验证

- [ ] **Step 1: 启动后端**

```bash
cd mateclaw-dev/mateclaw-server && mvn spring-boot:run &
```

Expected: 后端在 18088 端口正常启动

- [ ] **Step 2: 启动前端**

```bash
cd AIagent_frontend && npm run dev &
```

Expected: 前端在 3000 端口正常启动

- [ ] **Step 3: 测试 HTML 产物**

在聊天界面发送 "写一个简单的 hello.html，包含 Hello World 标题和红色背景"

Expected: 聊天区出现 `preview_card` 卡片，显示 HTML 代码内容，NCode 代码高亮正常，**不再只有** "已将代码写入某某文件" 文本

- [ ] **Step 4: 测试 Markdown 产物**

发送 "写一个 README.md，介绍这个项目"

Expected: 聊天区出现 `preview_card` 卡片，Markdown 内容渲染为富文本

- [ ] **Step 5: 测试代码产物**

发送 "写一个 utils.js，包含 formatDate 和 debounce 两个工具函数"

Expected: 聊天区出现 `preview_card` 卡片，JavaScript 代码高亮显示

- [ ] **Step 6: 验证 SSE 流稳定性**

在以上测试中确认 `content_delta` 文本流未被 `artifact_preview` 事件打断，流式输出正常

- [ ] **Step 7: Commit 最终验证结果**

（如有修复，追加提交；如全部通过，无需额外提交）
