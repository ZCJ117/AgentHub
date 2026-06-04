# 群聊固定消息作为子Agent长期上下文

## 概述

在群聊模式中，用户手动 Pin 的关键消息将自动作为"长期上下文"注入到子 Agent 的执行上下文中。编排器分配任务时，发送给子Agent 的内容结构为：

**[固定消息上下文] + [对话历史] + [系统提示词] + [任务]**

## 设计原则

- **复用现有设施**：Pin API (`/conversations/{id}/pins`)、`MessagePinEntity`、前端 Pin 按钮均已存在
- **方案 B：通过对话历史注入**：在 `buildContextMessage` 中将固定消息前置插入上下文
- **全部发送**：群聊中所有已固定消息都发送给每个子Agent
- **系统提示词保持现状**：`agent.getSystemPrompt()` 已在 `BridgeFrame` 中传递，不修改

## 改动范围

### 后端：1 个文件

| 文件 | 方法 | 改动 |
|------|------|------|
| `AgentMentionDispatcher.java` | `buildContextMessage` | 注入 `MessagePinService`，查询固定消息并前置插入上下文 |

### 前端：无需改动

Pin/Unpin 按钮、API 调用、侧边栏固定消息列表均已实现在 `MessageBubble.vue`、`ChatView.vue`、`DetailPanel.vue` 中。

### 无其他改动

- API：无新增/修改
- 协议：`BridgeFrame` / `SpawnArgs` 不变
- 数据库：`mate_message_pin` 表已存在
- 迁移脚本：不需要

## 数据流

```
1. 用户 Pin 消息 → POST /conversations/{id}/pins → mate_message_pin 表

2. 用户发消息到群聊
   → ChatController → GroupOrchestratorService (Agent01 分解任务)
   → AgentMentionDispatcher 拦截 @AgentName 行

3. executeSingleNode → buildContextMessage(task, conversationId)
   → 查询 mate_message_pin WHERE conversationId = ?
   → 查询固定消息的原始内容
   → 格式化上下文：

   # 以下是长期上下文（已固定的关键消息）
   - 消息内容1
   - 消息内容2
   ---
   # 以下是群聊对话历史上下文
   用户: ...
   Claude Code: ...
   ---
   # 当前任务
   实现登录页面

4. BridgeFrame → 子Agent CLI
   message: 上述完整上下文
   systemPrompt: Agent 自身的系统提示词
```

## 实现细节

### buildContextMessage 修改

在现有对话历史获取之前，查询并前置注入固定消息：

```java
private String buildContextMessage(String task, String conversationId) {
    try {
        StringBuilder ctx = new StringBuilder();

        // ── NEW: 前置注入固定消息 ──
        List<MessagePinEntity> pins = messagePinService.listPins(conversationId);
        if (pins != null && !pins.isEmpty()) {
            int maxPins = Math.min(pins.size(), 10);
            ctx.append("# 以下是长期上下文（已固定的关键消息）\n\n");
            for (int i = 0; i < maxPins; i++) {
                MessagePinEntity pin = pins.get(i);
                MessageEntity pinnedMsg = messageMapper.selectById(pin.getMessageId());
                if (pinnedMsg != null) {
                    String content = conversationService.renderMessageContent(pinnedMsg);
                    if (content != null && !content.isBlank()) {
                        ctx.append("- ").append(content).append("\n");
                    }
                }
            }
            ctx.append("\n---\n\n");
        }

        // ── 原有：对话历史 ──
        List<MessageEntity> history = conversationService.listRecentMessages(conversationId, 20);
        if (history != null && !history.isEmpty()) {
            ctx.append("# 以下是群聊对话历史上下文\n\n");
            // ... 现有格式化逻辑不变 ...
        }

        ctx.append("---\n");
        ctx.append("# 当前任务\n");
        ctx.append(task);
        return ctx.toString();
    } catch (Exception e) {
        log.warn("[Dispatcher] Failed to build conversation context: {}", e.getMessage());
        return task;
    }
}
```

### 需要新增的依赖注入

```java
private final MessagePinService messagePinService;
```

### 边界情况

| 场景 | 行为 |
|------|------|
| 无固定消息 | `pins` 为空，跳过注入，行为不变 |
| 固定消息的原始消息已删除 | `pinnedMsg == null`，跳过该条 |
| 固定消息超过 10 条 | 只取前 10 条 |
| 固定消息内容为空 | `content.isBlank()`，跳过该条 |
| Pin 查询异常 | catch 后降级，不影响任务执行 |

## 验收标准

1. 群聊中 Pin 一条消息后，编排器分配任务给子Agent，子Agent 收到的上下文包含该固定消息
2. 群聊中 Pin 多条消息后，所有固定消息都出现在子Agent 的上下文中
3. 群聊中无固定消息时，子Agent 收到的上下文与修改前一致
4. 固定消息的原始消息被删除后，不影响其他固定消息的注入
