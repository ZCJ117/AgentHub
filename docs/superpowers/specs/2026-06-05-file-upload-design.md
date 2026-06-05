# 文件上传功能设计规范

> 版本: 0.1 | 日期: 2026-06-05 | 状态: 待实现

## 1. 概述

### 1.1 目标

在单聊和群聊中新增文件上传功能。用户可上传文档类文件，Agent（含 Orchestrator 和子 Agent）能够读取文件内容并结合作出响应。

### 1.2 范围

- 单聊：用户上传文件 → Agent 读取 → 输出结果
- 群聊：用户上传文件 → Orchestrator 读取文件内容 → 分解任务 → 子 Agent 读取文件 → 执行任务
- 子 Agent 包括 in-JVM 子 Agent（`delegateToAgent`）和 CLI 子 Agent（BridgedAgent / Claude Code / OpenCode）

### 1.3 非范围

- 图片、视频、音频文件上传
- 文件过期清理策略
- 文件加密/病毒扫描
- 断点续传

## 2. 需求约束

| 维度 | 约束 |
|------|------|
| 文件类型 | PDF、Word (.docx)、Excel (.xlsx)、PPT (.pptx)、TXT、Markdown (.md) |
| 体积上限 | 10MB/文件（前端校验） |
| 数量上限 | 5 个/条消息 |
| 上传时机 | 发送时上传（选文件后仅显示标签，点击发送才开始上传） |
| 拖拽支持 | 支持拖拽文件到聊天区域 |

## 3. 设计方案

### 3.1 总体思路

**方案：绝对路径注入。** 在 prompt 中把上传文件的相对路径转为绝对路径，让所有 Agent（无论 JVM 内还是独立进程）都能通过文件系统直接访问。不引入新 API，不修改适配器脚本，不新增数据库表。

### 3.2 改动范围

| 层 | 文件 | 改动 |
|----|------|------|
| 前端 | `src/components/chat/Composer.vue` | 新增拖拽区域、文件标签、发送时上传逻辑 |
| 前端 | `src/stores/chat.js` | `sendMessage` 支持 `contentParts` 携带文件引用 |
| 后端 | `ChatController.buildPromptText()` | 文件路径输出为绝对路径 |
| 后端 | `ChatUploadResolver` | 增加父对话目录 fallback 查找 |
| 后端 | `BridgedAgent.buildContextualMessage()` | 追加文件列表段落到 CLI Agent 上下文 |

### 3.3 数据流

#### 阶段 1：用户上传与发送

```
1. 用户选择/拖入文件（≤5 个，每文件 ≤10MB）
2. Composer 显示文件标签（文件名 + 大小），超限文件红色标记
3. 用户输入消息文本，点击发送
4. 逐个调用 POST /api/v1/chat/upload，获取 { fileName, storedName, path, contentType }
5. 全部上传完成后，调用 POST /api/v1/chat/stream，body 中携带 contentParts
```

#### 阶段 2：Prompt 构建

```
buildPromptText() 接收 contentParts[type=file]
→ 将 path 从相对路径转为绝对路径（Paths.get(path).toAbsolutePath()）
→ 输出: "附件: report.pdf (/abs/path/data/chat-uploads/abc/17773_report.pdf)"
```

#### 阶段 3：Agent 读取文件

```
单聊 / Orchestrator:
  read_file(absPath) → WorkspacePathGuard → ChatUploadResolver → 返回内容

群聊子 Agent (delegateToAgent):
  ChatUploadResolver 在当前对话目录未找到 → fallback 父对话目录 → 返回内容

群聊 CLI 子 Agent:
  BridgedAgent.buildContextualMessage() 显式列出文件绝对路径
  → CLI 进程用文件系统 API 打开绝对路径 → 返回内容
```

### 3.4 前端：Composer.vue

**新增状态**：
- `pendingFiles: Array<{ name, size, type, file: File }>` — 待上传文件列表
- `uploading: boolean` — 是否正在上传
- `uploadProgress: number` — 上传进度百分比
- `uploadErrors: Map<string, string>` — 上传错误

**交互逻辑**：

1. **文件选择**：点击附件按钮或拖入文件，检查类型和大小后加入 `pendingFiles`
2. **文件标签**：每个文件显示文件名 + 大小，可点击 × 移除
3. **超限处理**：>10MB 红色标签标记，>5 个截断并 toast 提示
4. **发送**：逐个上传文件 → 全部完成后发送消息（携带 contentParts）→ 启动 SSE 流
5. **取消**：中止所有上传，清空 `pendingFiles`，恢复 Composer 可输入状态
6. **纯文本兼容**：无文件时行为完全不变

### 3.5 后端：ChatController.buildPromptText()

**改动**：`file` 和 `image` 类型 contentPart 的路径从原始相对路径改为绝对路径。

```java
case "file", "image" -> {
    Path absPath = Paths.get(part.getPath()).toAbsolutePath();
    appendPromptLine(builder, "附件: " + safe(part.getFileName()) + " (" + absPath + ")");
}
```

### 3.6 后端：ChatUploadResolver

**改动**：resolve() 新增两个查找步骤：

1. 如果 rawPath 是绝对路径且文件存在 → 直接返回（新增）
2. 当前对话 uploads 目录中 basename 匹配
3. 当前对话 uploads 目录中 suffix 匹配
4. 如果有 parentConversationId → 在父对话 uploads 中重复 2-3（新增）

父对话 ID 来源：`delegateToAgent` 创建子对话时写入子对话的扩展字段。

### 3.7 后端：BridgedAgent.buildContextualMessage()

**改动**：在上下文开头追加文件列表段：

```
[用户上传的文件]
- report.pdf: /data/chat-uploads/abc/17773_report.pdf
- data.xlsx: /data/chat-uploads/abc/17773_data.xlsx
```

从当前对话的首条用户消息的 `contentParts` 中提取 `type=file` 的部分渲染。

## 4. 错误处理

| 场景 | 处理 |
|------|------|
| 文件 > 10MB | 红色标签，阻止发送，提示移除 |
| 超过 5 个文件 | 截断保留前 5 个，toast 提示 |
| 不支持的文件类型 | MIME type 过滤，toast 提示 |
| 单文件上传失败 | 标签变红，其余继续上传，可重试 |
| 全部上传失败 | 不发送消息，错误 toast |
| 用户取消上传 | 中止所有请求，恢复 Composer |
| Agent 读文件时文件不存在 | 工具返回"文件未找到"给 LLM |
| 二进制格式（PDF/Word 等） | extract_document_text 提取文本内容 |

## 5. 测试策略

### 5.1 前端 Composer（手动 + 组件测试）

- 文件选择/拖拽/删除
- 10MB 超限、5 个上限、不支持类型过滤
- 上传进度、取消上传
- 纯文本消息无回归

### 5.2 单聊文件读取（集成测试）

- 上传 PDF → Agent 调用 read_file → 返回内容 → 回答引用文件
- 验证 buildPromptText() 输出绝对路径
- 验证 ChatUploadResolver 解析绝对路径

### 5.3 群聊子 Agent 文件读取（集成测试）

- 上传文件 → Orchestrator 分解任务 → 子 Agent 读取
- 验证 ChatUploadResolver 子对话 fallback
- 验证 CLI Agent 上下文含文件列表

## 6. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| 0.1 | 2026-06-05 | 初始版本 |
