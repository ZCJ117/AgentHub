# Spec: Write File → Artifact Preview 渲染链路修复

**日期**: 2026-06-06
**状态**: 设计中
**用户故事**: Agent 生成的代码文件不可见——前端收不到产物渲染事件，用户只看到"已将代码写入某某文件"文本。

## 目标

当 Agent 通过 `write_file` 工具将代码/文档写入工作区文件时，自动推送 `artifact_preview` SSE 事件给前端，使聊天界面中显示可交互的产物卡片（预览 / 复制代码 / Markdown 渲染）。

## 非目标

- 不修改 Agent prompt（方案一）
- 不创建数据库 ArtifactEntity 记录（仅流式推送）
- 不拦截 write_file 以外的文件产出工具（pdf/docx/xlsx 等渲染工具）
- 不创建新的文件服务 HTTP 端点

## 数据流

```
write_file 完成 (ToolExecutionExecutor)
  → tool_call_completed SSE 事件（附带 workspaceBasePath）
    → StreamAccumulator.accept() 拦截
      → 解析 result JSON → 提取 filePath
      → 拼接 workspaceBasePath + filePath → 读取文件内容
      → 根据扩展名确定 artifact type
      → ChatStreamTracker.emitArtifactPreview() 推送 SSE
        → 前端 artifact_preview 处理器
          → artifactStore.handleArtifactPreview()
          → addMessageLocal('preview_card')
            → ArtifactPreviewCard 渲染
```

## 文件扩展名 → Artifact Type 映射

| 扩展名 | artifactType | 前端渲染 |
|--------|-------------|---------|
| `.html`, `.htm` | `website` | NCode 代码块 + 复制 + 预览 |
| `.md` | `document` | Markdown 渲染 + 复制源码 |
| `.js`, `.ts`, `.jsx`, `.tsx`, `.vue`, `.py`, `.java`, `.go`, `.rs`, `.rb`, `.php`, `.c`, `.cpp`, `.h`, `.cs`, `.swift`, `.kt`, `.scala` | `code` | NCode 代码块 + 复制 |
| `.json`, `.yaml`, `.yml`, `.xml`, `.toml` | `data` | NCode 代码块 + 复制 |
| `.css`, `.scss`, `.less`, `.sass` | `stylesheet` | NCode 代码块 + 复制 |
| 其他 | `file` | 文件名 + 下载链接 |

## 改动清单

### 1. GraphEventPublisher — 新增重载

**文件**: `mateclaw-domain/.../agent/GraphEventPublisher.java`

新增 `toolComplete` 重载，接收 `workspaceBasePath` 参数。数据 map 新增 `workspaceBasePath` 键（仅非空时写入）。

```java
// 新重载（仅内部 tool_call_completed 事件使用）
public static GraphEvent toolComplete(
    String toolCallId, String toolName, String result,
    boolean success, String workspaceBasePath) {
  // ... 同原方法，data.put("workspaceBasePath", workspaceBasePath) 当非空时
}
```

### 2. ToolExecutionExecutor — 传递路径

**文件**: `mateclaw-domain/.../graph/executor/ToolExecutionExecutor.java`

在 `executeSingleTool()` 成功路径（行 866）和失败路径（行 884），调用新重载并传入 `pc.workspaceBasePath`。

### 3. ChatStreamTracker — 扩展签名

**文件**: `mateclaw-domain/.../channel/web/ChatStreamTracker.java`

`emitArtifactPreview()` 扩展参数：

```java
public void emitArtifactPreview(String conversationId, long artifactId,
    String type, String name, String content, String previewUrl)
```

payload 新增 `name` 和 `content` 字段。

### 4. ChatController.StreamAccumulator — 核心拦截

**文件**: `mateclaw-server/.../channel/web/ChatController.java`

在 `accumulateToolEvent()` 的 `tool_call_completed` 分支（行 2136）末尾新增：

```
if ("write_file".equals(toolName) && success) → interceptWriteFileForPreview(...)
```

新增私有方法 `interceptWriteFileForPreview(String conversationId, String resultJson, String workspaceBasePath)`:
1. 用 ObjectMapper 解析 result JSON → filePath
2. 从扩展名查表确定 artifact type
3. `Path.of(workspaceBasePath, filePath)` 读文件 → content
4. 生成临时 artifactId = `Math.abs((conversationId + filePath).hashCode())`
5. 调用 `streamTracker.emitArtifactPreview(...)`
6. 异常时静默忽略（不阻塞下游）

### 5. 前端 chat.js — artifact_preview 处理器

**文件**: `AIagent_frontend/src/stores/chat.js`

在 `artifact_preview` 处理器中，传递 `data.content` 给 `handleArtifactPreview`。

### 6. 前端 artifact.js — store 扩展

**文件**: `AIagent_frontend/src/stores/artifact.js`

`handleArtifactPreview()` 接受并存储 `content` 字段。

### 7. 前端 ArtifactPreviewCard — 渲染扩展

**文件**: `AIagent_frontend/src/components/chat/ArtifactPreviewCard.vue`

根据 `artifact.artifactType` 条件渲染：

- `website` / `code` / `data` / `stylesheet` → NCode 组件（代码高亮 + 复制按钮）
- `document` → `v-html` 渲染 Markdown（通过 `renderMarkdown()`）
- `file` → 文件名 + 下载/编辑按钮

所有类型保留"复制代码"按钮。

## 边界条件

- **workspaceBasePath 为空**: 跳过 artifact preview，不报错
- **文件不存在**: 静默跳过，不阻塞 SSE 流
- **文件过大（>512KB）**: 截断 content 到 512KB，前端标记"内容已截断"
- **result JSON 解析失败**: 静默跳过
- **并发 write_file**: 各文件独立生成 artifact preview，不冲突

## 验证方式

1. 单聊：向 Agent 发送 "写一个 hello.html"，确认聊天区出现 preview_card 卡片
2. 群聊：多 Agent 协作中任一 Agent 调用 write_file，确认产物卡片出现
3. 代码文件：写 JS/Python 文件，确认 NCode 代码块渲染 + 复制按钮可用
4. Markdown：写 .md 文件，确认 Markdown 渲染正确
5. SSE 流稳定性：artifact_preview 事件不打断 content_delta 流

## 风险

- **LLM 不再在文本中重复代码内容**: 用户可能期待在消息正文中看到代码，但 artifact 卡片替代了文本。这是期望行为——由卡片提供更好的交互（复制/预览）。
- **HTML 预览 URL**: MVP 阶段 HTML 文件不在 iframe 中预览（`blob:` URL 需前端构造）。预览按钮打开文件内容在新标签页中。
