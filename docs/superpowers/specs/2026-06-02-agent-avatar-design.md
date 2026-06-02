# Agent 头像功能设计文档

**日期**: 2026-06-02  
**状态**: 已确认  
**方案**: A — 直接修复 + CSS 缩略

---

## 1. 需求概述

为 AgentHub 多 Agent 协同平台实现 Agent 头像展示功能：

- **头像上传/修改**：Agent 设置模块中支持上传、预览、更换头像，限制 ≤2MB、image/* 格式
- **默认头像**：未上传时显示首字母 + 彩色背景（NAvatar 默认行为）
- **多场景展示**：一对一聊天头部、群聊每条消息旁、会话列表均显示对应 Agent 头像
- **点击预览**：点击头像通过 NImage 的 Modal 弹窗查看原图

---

## 2. 当前状态

### 已实现
| 模块 | 文件 | 状态 |
|------|------|------|
| 后台上传接口 | `AgentController.java:102-111` — `PUT /agents/{id}/avatar` | ✅ 已实现 |
| 文件存储 | `AgentService.java:259-286` — 保存到 `./workspace/avatars/{id}/` | ✅ 已实现 |
| 文件校验 | `AgentService.java` — 格式 image/*, 大小 ≤2MB | ✅ 已实现 |
| DB 字段 | `AgentEntity.java:82` — `avatarUrl` | ✅ 已存在 |
| 前端上传 UI | `AgentDetailView.vue:201-269` — 上传、预览、更换 | ✅ 已实现 |
| 聊天头部头像 | `ChatArea.vue:44-48,92` — 显示单个 Agent 头像 | ✅ 已实现 |
| 会话列表头像 | `ConversationSidebar.vue:164-170` — conv.agentAvatarUrl | ✅ 已实现 |
| 消息气泡头像 | `MessageBubble.vue:94-102` — senderAgentAvatarUrl | ⚠️ UI 已写，数据源断 |

### 缺口（需修复）
| # | 问题 | 位置 |
|---|------|------|
| 1 | 无 `/avatars/**` 静态资源映射，上传文件 404 | `WebMvcConfig.java:57-61` |
| 2 | `ConversationVO.from()` 不填充 `agentAvatarUrl` | `ConversationVO.java:85-125` |
| 3 | `MessageVO` 无 `senderAgentAvatarUrl` 字段 | `MessageVO.java` |
| 4 | SSE `agent_message_start` 不携带 `avatarUrl` | `AgentMentionDispatcher.java` |
| 5 | Composer @mention 弹窗 NAvatar 无 `:src` 绑定 | `Composer.vue:219` |
| 6 | chat.js `addMessageLocal` 不设置 `senderAgentAvatarUrl` | `chat.js:74` |

---

## 3. 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 默认头像 | 首字母 + 彩色背景 | NAvatar 内置行为，群聊中颜色区分度高 |
| 查看原图 | NImage Modal 弹窗 | Naive UI 内置，零额外开发 |
| 缩略图策略 | CSS `object-fit: cover` | 改动最小，现代浏览器效果足够 |
| 图片服务 | 静态资源映射 | 简单可靠，无需额外 API |

---

## 4. 后端改动

### 4.1 WebMvcConfig — 静态资源映射

**文件**: `mateclaw-server/.../config/WebMvcConfig.java`

新增 `/avatars/**` 资源处理器，映射到 `file:./workspace/avatars/`：

```java
registry.addResourceHandler("/avatars/**")
        .addResourceLocations("file:./workspace/avatars/");
```

### 4.2 ConversationVO — 填充 agentAvatarUrl

**文件**: `mateclaw-domain/.../vo/ConversationVO.java`

`from()` 方法新增 `agentAvatarUrl` 参数并填充：

```java
public static ConversationVO from(ConversationEntity entity,
                                   String agentName,
                                   String agentIcon,
                                   String agentAvatarUrl) {
    // ...
    vo.setAgentAvatarUrl(agentAvatarUrl);
}
```

**调用方修改**：
- `ConversationService.listConversations()` — 传入 `agent.getAvatarUrl()`
- `ConversationController.detail()` — 传入 `agent.getAvatarUrl()`

### 4.3 MessageVO — 新增字段

**文件**: `mateclaw-domain/.../vo/MessageVO.java`

```java
private String senderAgentAvatarUrl;
```

消息查询 SQL（Mapper XML）中 LEFT JOIN `mate_agent` 取出 `avatar_url`，填充到 VO。

### 4.4 SSE agent_message_start — 携带 avatarUrl

**文件**: `mateclaw-domain/.../service/AgentMentionDispatcher.java`

在以下事件 payload 中增加 `avatarUrl` 字段：
- DAG task start 事件（行 ~199）
- Single spawn 事件（行 ~281）
- Orchestrator start 事件（行 ~538）

---

## 5. 前端改动

### 5.1 MessageBubble — NImage 预览

**文件**: `AIagent_frontend/src/components/chat/MessageBubble.vue`

将消息气泡中的纯 NAvatar 替换为 NImage + NAvatar 的组合：

- NImage 包裹，`:preview-disabled` 在无 src 时禁用
- NAvatar 作为 `#placeholder` 插槽，保持首字母回退
- `object-fit: cover; border-radius: 50%` 实现圆形缩略图

### 5.2 Composer @mention — 补充 src

**文件**: `AIagent_frontend/src/components/chat/Composer.vue`

@mention 弹窗中的 NAvatar 补充 `:src="agent.avatarUrl"`。

### 5.3 chat.js — SSE 事件写入 avatarUrl

**文件**: `AIagent_frontend/src/stores/chat.js`

`agent_message_start` 事件处理中：
```js
senderAgentAvatarUrl: event.avatarUrl || null
```

### 5.4 AgentDetailView — 无需改动

现有上传 UI 已可用。确认逻辑完整即可：
- 点击上传 → 选择文件 → 即时预览 → 保存
- 已上传时显示当前头像，hover 显示更换按钮

---

## 6. 数据流

```
用户上传头像
  → AgentDetailView: FormData → PUT /agents/{id}/avatar
  → AgentService: 校验 → 存文件 → 更新 avatarUrl → 入库
  → 返回 { avatarUrl: "/avatars/123/avatar_xxx.png" }

头像展示
  → Agent API 返回 agent.avatarUrl
  → ConversationVO 携带 agentAvatarUrl
  → MessageVO 携带 senderAgentAvatarUrl
  → SSE 事件携带 avatarUrl
  → 前端拼 base URL → <img> → 浏览器加载 /avatars/.../xxx.png
  → WebMvcConfig 映射到 ./workspace/avatars/.../xxx.png
```

---

## 7. 边界情况

| 场景 | 处理 |
|------|------|
| 未上传头像 | NAvatar 首字母 + 彩色背景 |
| 图片加载失败 | NImage fallback → NAvatar 文字回退 |
| 上传超大文件 | 后端 ≤2MB 校验 → 413 |
| 非图片格式 | 后端 image/* 校验 → 400 |
| 头像更新后旧缓存 | URL 含时间戳，自然 bypass 缓存 |
| Agent 删除后历史头像 | 文件保留不删，404 时走 fallback |
| 群聊无头像 Agent | senderAgentAvatarUrl=null → 文字回退 |
| 点击默认头像 | preview-disabled 阻止无意义弹窗 |

---

## 8. 文件改动清单

### 后端 (5 文件)
1. `mateclaw-server/.../config/WebMvcConfig.java` — 新增 /avatars/** 映射
2. `mateclaw-domain/.../vo/ConversationVO.java` — from() 新增 avatarUrl
3. `mateclaw-domain/.../service/ConversationService.java` — 传 agentAvatarUrl
4. `mateclaw-domain/.../vo/MessageVO.java` — 新增 senderAgentAvatarUrl 字段
5. `mateclaw-domain/.../service/AgentMentionDispatcher.java` — SSE 事件补 avatarUrl

### 前端 (3 文件)
1. `AIagent_frontend/src/components/chat/MessageBubble.vue` — NImage 预览
2. `AIagent_frontend/src/components/chat/Composer.vue` — @mention src 绑定
3. `AIagent_frontend/src/stores/chat.js` — 写入 senderAgentAvatarUrl
