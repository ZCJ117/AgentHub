# Workspace Working Directory — Design Spec

**Date:** 2026-05-31
**Status:** Draft
**Summary:** 用户可为工作区指定本地工作目录（basePath），该路径作为 Agent CLI 子进程的工作目录，使 Agent 能在用户选定的项目文件夹内创建/修改文件。

---

## 1. 背景

### 1.1 现状

- `WorkspaceEntity.basePath` 字段已在 V2 迁移中创建（VARCHAR 512），但前端和 CLI 进程均未使用
- `WorkspacePathGuard` 已使用 basePath 限制文件工具的访问范围（沙箱）
- `ChatOrigin` 已携带 `workspaceBasePath` 在工具调用链中流转
- `PUT /api/v1/workspaces/{id}` 已支持更新 basePath，但无校验逻辑
- `LocalCliProcessManager.spawn()` 从未调用 `pb.directory()`，CLI 子进程继承 Java 进程 cwd
- 前端 SettingsView 的工作区卡片仅显示 workspace ID，无编辑入口

### 1.2 目标

- 前端工作区卡片增加 basePath 输入和保存功能
- 保存时后端校验目录存在且可读写
- CLI spawn 时以 basePath 作为工作目录启动子进程
- basePath 为空时不设工作目录，回退到现有行为
- 不引入独立校验端点，保持改动最小

---

## 2. 数据模型

无 schema 变更。`mate_workspace.base_path` 字段已存在。

---

## 3. 整体数据流

```
SettingsView                WorkspaceController          WorkspaceService         LocalCliProcessManager
   │                              │                            │                          │
   │  PUT /workspaces/{id}        │                            │                          │
   │  { basePath: "/home/..." }   │                            │                          │
   │─────────────────────────────>│                            │                          │
   │                              │  update(entity)            │                          │
   │                              │───────────────────────────>│                          │
   │                              │                            │  校验 Path.of(basePath)  │
   │                              │                            │  存在 && 可读写           │
   │                              │                            │  workspaceMapper.update  │
   │                              │  R.ok(updated)             │                          │
   │  200 OK                      │<───────────────────────────│                          │
   │<─────────────────────────────│                            │                          │
   │                              │                            │                          │
   ... 之后，新建对话/群聊 ...                                                         │
   │                              │                            │                          │
   │                              │                            │  spawn(agentId, ...,      │
   │                              │                            │    workingDir)            │
   │                              │                            │─────────────────────────>│
   │                              │                            │                          │  pb.directory(workingDir)
   │                              │                            │                          │  校验存在 → spawn
   │                              │                            │                          │  不存在 → 报错
```

---

## 4. 变更清单

### 4.1 前端 — SettingsView.vue

**当前工作区卡片**（仅显示 ID）：
```html
<NCard title="工作区" class="settings-card">
  <p>当前工作区 ID: {{ workspaceStore.activeId || '未选择' }}</p>
</NCard>
```

**改为**：
```html
<NCard title="工作区" class="settings-card">
  <NSpace vertical :size="12">
    <div>
      <label>当前工作区</label>
      <NInput :value="workspaceStore.activeWorkspace?.name || '未选择'" disabled />
    </div>
    <div>
      <label>工作目录（Agent CLI 执行路径）</label>
      <NInput v-model:value="basePath" placeholder="如 /home/user/projects/my-app" />
    </div>
    <NButton type="primary" @click="saveBasePath" :loading="savingPath">保存</NButton>
    <span v-if="pathMsg" :class="pathOk ? 'msg-ok' : 'msg-err'">{{ pathMsg }}</span>
  </NSpace>
</NCard>
```

**逻辑**：
- `onMounted` 中从 `workspaceStore.activeWorkspace.basePath` 初始化 `basePath`
- `saveBasePath()` 调用 `PUT /api/v1/workspaces/{id}` 传 `{ basePath }`
- 后端校验失败 → 显示红色错误
- 保存成功 → 绿色提示，刷新 workspaceStore 缓存
- 需引入 `NInput`（已在页面中使用）

### 4.2 前端 — workspace.js store

`saveBasePath` 成功后调用 `loadAndSelect()` 刷新缓存，确保 `activeWorkspace.basePath` 反映最新值。

### 4.3 后端 — WorkspaceService.update()

在 `workspaceMapper.updateById(entity)` 调用前增加 basePath 校验：

```java
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
```

- basePath 为 null/空 → 不校验，保持向后兼容
- 引入 `java.nio.file.Path`, `java.nio.file.Files`

### 4.4 后端 — LocalCliProcessManager.spawn()

**方法签名** — 增加 `workingDir` 参数：

```java
public boolean spawn(String agentId, String cliType,
                     String agentName, String systemPrompt,
                     String claudeMdPath,
                     String workingDir)
```

**spawn() 内部** — 在 `pb.start()` 之前：

```java
if (workingDir != null && !workingDir.isBlank()) {
    File dir = new File(workingDir);
    if (!dir.isDirectory()) {
        throw new IllegalStateException(
                "Workspace working directory does not exist: " + workingDir);
    }
    pb.directory(dir);
}
```

### 4.5 后端 — BridgedAgent.chatViaProcess()

- 注入 `WorkspaceMapper`
- `spawn()` 调用前：`conversationService.getByConversationId(conversationId)` → `conv.getWorkspaceId()` → `workspaceMapper.selectById(workspaceId).getBasePath()`
- 传入 spawn 作为 `workingDir` 参数
- `conversationService` 已由 BaseAgent 提供，无需额外注入

### 4.6 后端 — AgentMentionDispatcher.spawnAndStreamAgent()

- 注入 `WorkspaceMapper`
- `spawn()` 调用前：`conversationService.getByConversationId(conversationId)` → `conv.getWorkspaceId()` → `workspaceMapper.selectById(workspaceId).getBasePath()`
- `conversationService` 已在当前类中注入，无需额外依赖
- 传入 spawn 作为 `workingDir` 参数

---

## 5. 边界情况 & 错误处理

| 场景 | 行为 |
|------|------|
| basePath 为空/null | 不设 `pb.directory()`，继承 Java 进程 cwd（保持现有行为） |
| Windows 路径（`D:\projects\foo`） | `Path.of()` + `Files.isDirectory()` 跨平台支持 |
| 保存后、spawn 前目录被删除 | spawn 时二次校验 → 抛出 `IllegalStateException` → sink.error() → 前端 "Agent CLI 启动失败：工作目录不存在" |
| 相对路径输入 | `Path.of().toAbsolutePath().normalize()` 转为绝对路径 |
| 非 admin 角色修改 | `WorkspaceController.update()` 已有 `requirePermission(id, userId, "admin")` |
| 内置任务分配 Agent（Orchestrator） | 群聊 Orchestrator（arthur-agent）不使用 CLI 进程，不受影响 |

---

## 6. 文件变更清单

| 文件 | 改动 |
|------|------|
| `AIagent_frontend/src/views/SettingsView.vue` | 工作区卡片增加 basePath 输入 + 保存逻辑 |
| `AIagent_frontend/src/stores/workspace.js` | 保存后刷新缓存 |
| `mateclaw-domain/.../service/WorkspaceService.java` | `update()` 增加 basePath 目录校验 |
| `mateclaw-domain/.../cli/LocalCliProcessManager.java` | `spawn()` 增加 `workingDir` 参数 + `pb.directory()` |
| `mateclaw-domain/.../bridge/BridgedAgent.java` | 注入 WorkspaceMapper，spawn 前解析 basePath |
| `mateclaw-domain/.../service/AgentMentionDispatcher.java` | 注入 WorkspaceMapper，spawn 前解析 basePath |

估计改动量：~80 行。
