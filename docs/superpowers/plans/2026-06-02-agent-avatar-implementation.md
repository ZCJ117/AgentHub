# Agent 头像功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Agent 头像数据流，使头像在会话列表、消息气泡、群聊消息中正确展示，并支持点击 NImage 预览原图。

**Architecture:** 后端修复 4 处数据流断点（静态资源映射、ConversationVO 填充、MessageVO 填充、SSE 事件），前端将 MessageBubble 中的纯 NAvatar 替换为 NImage+NAvatar 组合并补充 Composer @mention 的 src 绑定。

**Tech Stack:** Java 21 / Spring Boot 3.5 / MyBatis Plus, Vue 3 / Naive UI / Pinia

---

### Task 1: WebMvcConfig — 新增 /avatars/** 静态资源映射

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/config/WebMvcConfig.java`

- [ ] **Step 1: 在 addResourceHandlers 中新增 /avatars/** 映射**

在现有 `/skill-assets/**` 映射之后添加：

```java
@Override
public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/skill-assets/**")
            .addResourceLocations("classpath:/skills/")
            .setCachePeriod(86400);
    registry.addResourceHandler("/avatars/**")
            .addResourceLocations("file:./workspace/avatars/");
}
```

- [ ] **Step 2: 验证编译通过**

```bash
cd D:/code/Loom/mateclaw-dev/mateclaw-server && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/config/WebMvcConfig.java
git commit -m "feat: add /avatars/** static resource mapping for agent avatars"
```

---

### Task 2: ConversationVO — from() 新增 agentAvatarUrl 参数

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/conversation/vo/ConversationVO.java`

- [ ] **Step 1: 修改 from() 方法签名，新增 agentAvatarUrl 参数并设置**

```java
public static ConversationVO from(ConversationEntity entity, String agentName, String agentIcon, String agentAvatarUrl) {
    ConversationVO vo = new ConversationVO();
    // ... 现有字段复制不变 ...
    // 补充关联字段
    vo.setAgentName(agentName != null ? agentName : "未知 Agent");
    vo.setAgentIcon(agentIcon != null ? agentIcon : "🤖");
    vo.setAgentAvatarUrl(agentAvatarUrl);
    // ... 后续不变 ...
}
```

具体改动：
- 方法签名第 85 行：`String agentIcon)` → `String agentIcon, String agentAvatarUrl)`
- 第 111 行之后新增：`vo.setAgentAvatarUrl(agentAvatarUrl);`

- [ ] **Step 2: 验证编译通过**

```bash
cd D:/code/Loom/mateclaw-dev && mvn compile -q
```

Expected: **BUILD FAILURE** — 因为调用方尚未更新参数（ConversationService、ConversationController 等方法签名不匹配）

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/conversation/vo/ConversationVO.java
git commit -m "feat: add agentAvatarUrl parameter to ConversationVO.from()"
```

---

### Task 3: ConversationService + ConversationController — 传入 agentAvatarUrl

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/conversation/ConversationService.java`
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/workspace/conversation/controller/ConversationController.java`

- [ ] **Step 1: ConversationService.listConversations() — 提取 agentAvatarUrl 并传入 from()**

在 `listConversations(String username, Long workspaceId, String conversationType)` 方法中（约第 156-165 行）：

```java
return entities.stream()
        .map(entity -> {
            AgentEntity agent = entity.getAgentId() != null
                    ? agentMap.get(entity.getAgentId())
                    : null;
            String agentName = agent != null ? agent.getName() : null;
            String agentIcon = agent != null ? agent.getIcon() : null;
            String agentAvatarUrl = agent != null ? agent.getAvatarUrl() : null;
            return ConversationVO.from(entity, agentName, agentIcon, agentAvatarUrl);
        })
        .collect(Collectors.toList());
```

- [ ] **Step 2: ConversationService.pageConversations() — 同样补充 agentAvatarUrl**

在同一文件中找到 `pageConversations` 方法（约第 179 行开始），找到其中调用 `ConversationVO.from()` 的位置，同样传入 `agent.getAvatarUrl()`。

- [ ] **Step 3: ConversationController.detail() — 提取并传入 agentAvatarUrl**

在 `detail()` 方法中（约第 310-319 行）：

```java
String agentName = null;
String agentIcon = null;
String agentAvatarUrl = null;
if (conv.getAgentId() != null) {
    AgentEntity agent = agentMapper.selectById(conv.getAgentId());
    if (agent != null) {
        agentName = agent.getName();
        agentIcon = agent.getIcon();
        agentAvatarUrl = agent.getAvatarUrl();
    }
}
ConversationVO vo = ConversationVO.from(conv, agentName, agentIcon, agentAvatarUrl);
```

- [ ] **Step 4: 验证编译通过**

```bash
cd D:/code/Loom/mateclaw-dev && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/conversation/ConversationService.java \
        mateclaw-dev/mateclaw-server/src/main/java/vip/mate/server/workspace/conversation/controller/ConversationController.java
git commit -m "fix: populate agentAvatarUrl in conversation list and detail"
```

---

### Task 4: ConversationService — toMessageViews 填充 senderAgentAvatarUrl

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/conversation/ConversationService.java`

- [ ] **Step 1: 修改 buildAgentNameMap 为 buildAgentInfoMaps，同时返回 nameMap 和 avatarMap**

将现有的 `buildAgentNameMap` 方法替换为：

```java
private record AgentInfoMaps(Map<Long, String> nameMap, Map<Long, String> avatarMap) {}

private AgentInfoMaps buildAgentInfoMaps(List<MessageEntity> messages) {
    Set<Long> agentIds = messages.stream()
            .map(MessageEntity::getSenderAgentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (agentIds.isEmpty()) {
        return new AgentInfoMaps(Map.of(), Map.of());
    }
    List<AgentEntity> agents = agentMapper.selectBatchIds(agentIds);
    Map<Long, String> nameMap = agents.stream()
            .collect(Collectors.toMap(AgentEntity::getId, AgentEntity::getName));
    Map<Long, String> avatarMap = agents.stream()
            .filter(a -> a.getAvatarUrl() != null)
            .collect(Collectors.toMap(AgentEntity::getId, AgentEntity::getAvatarUrl));
    return new AgentInfoMaps(nameMap, avatarMap);
}
```

- [ ] **Step 2: 更新 toMessageViews 使用新的方法并设置 avatarUrl**

```java
public List<MessageVO> toMessageViews(List<MessageEntity> messages) {
    AgentInfoMaps infoMaps = buildAgentInfoMaps(messages);
    return messages.stream()
            .map(m -> MessageVO.from(m, parseMessageParts(m), renderMessageContent(m)))
            .peek(vo -> {
                if (vo.getSenderAgentId() != null) {
                    vo.setSenderAgentName(infoMaps.nameMap().get(vo.getSenderAgentId()));
                    vo.setSenderAgentAvatarUrl(infoMaps.avatarMap().get(vo.getSenderAgentId()));
                }
            })
            .toList();
}
```

- [ ] **Step 3: 在 MessageVO 中添加 senderAgentAvatarUrl 字段**

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/conversation/vo/MessageVO.java`

在 `senderAgentName` 字段下方（第 68 行之后）添加：

```java
/** 发送者 Agent 头像 URL */
private String senderAgentAvatarUrl;
```

- [ ] **Step 4: 验证编译通过**

```bash
cd D:/code/Loom/mateclaw-dev && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/conversation/ConversationService.java \
        mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/workspace/conversation/vo/MessageVO.java
git commit -m "fix: populate senderAgentAvatarUrl in message views"
```

---

### Task 5: AgentMentionDispatcher — SSE 事件携带 avatarUrl

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

3 处 `agent_message_start` 发射点都需要补充 `avatarUrl`。

- [ ] **Step 1: DAG task start 事件（约第 198-206 行）— 添加 avatarUrl**

在 `startEvent` map 中增加一行：

```java
Map<String, Object> startEvent = new LinkedHashMap<>();
startEvent.put("agentName", node.agentName);
startEvent.put("agentId", String.valueOf(node.dagTask.agent.getId()));
startEvent.put("taskDescription", node.dagTask.task);
startEvent.put("avatarUrl", node.dagTask.agent.getAvatarUrl());
if (node.dagTask.dependsOnAgentName != null) {
    startEvent.put("dependsOn", node.dagTask.dependsOnAgentName);
}
```

- [ ] **Step 2: Single spawn 事件（约第 281-285 行）— 添加 avatarUrl**

```java
streamTracker.broadcastObject(conversationId, "agent_message_start", Map.of(
        "agentName", agentName,
        "agentId", String.valueOf(agent.getId()),
        "taskDescription", task,
        "avatarUrl", agent.getAvatarUrl()
));
```

- [ ] **Step 3: Orchestrator start 事件（约第 538-542 行）— 添加 avatarUrl**

已有 `agent01` 对象的引用，在 Map.of 中增加：

```java
streamTracker.broadcastObject(conversationId, "agent_message_start", Map.of(
        "agentName", "Agent01",
        "agentId", orchestratorAgentId[0] != null ? orchestratorAgentId[0] : "0",
        "taskDescription", "任务执行结果汇总",
        "avatarUrl", agent01 != null ? agent01.getAvatarUrl() : null
));
```

- [ ] **Step 4: 验证编译通过**

```bash
cd D:/code/Loom/mateclaw-dev && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "fix: include avatarUrl in SSE agent_message_start events"
```

---

### Task 6: MessageBubble.vue — NImage 包裹头像支持点击预览

**Files:**
- Modify: `AIagent_frontend/src/components/chat/MessageBubble.vue`

- [ ] **Step 1: 替换 NAvatar 为 NImage + NAvatar 组合**

将第 94-102 行的纯 NAvatar 替换为：

```html
<NImage
  v-if="!isUser"
  :src="message.senderAgentAvatarUrl"
  :width="32"
  :height="32"
  :preview-disabled="!message.senderAgentAvatarUrl"
  object-fit="cover"
  style="border-radius: 50%; flex-shrink: 0; margin-top: 2px"
>
  <template #placeholder>
    <NAvatar
      :size="32"
      round
      class="msg-avatar"
    >
      {{ (message.senderAgentName || 'AI')[0] }}
    </NAvatar>
  </template>
</NImage>
```

> NImage 自带 preview 功能：点击后弹出居中 Modal 查看原图，点击遮罩或关闭按钮退出。无需额外实现。

- [ ] **Step 2: 验证前端编译通过**

```bash
cd D:/code/Loom/AIagent_frontend && npx vite build --mode development 2>&1 | tail -5
```

Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/components/chat/MessageBubble.vue
git commit -m "feat: wrap agent avatar in NImage for click-to-preview"
```

---

### Task 7: Composer.vue — @mention 弹窗 NAvatar 补充 src

**Files:**
- Modify: `AIagent_frontend/src/components/chat/Composer.vue`

- [ ] **Step 1: 补充 :src 绑定**

将第 219 行的 NAvatar 添加 `:src` 属性：

```html
<NAvatar :size="28" round :src="member.avatarUrl">
  {{ (member.agentName || '?')[0] }}
</NAvatar>
```

- [ ] **Step 2: 验证前端编译通过**

```bash
cd D:/code/Loom/AIagent_frontend && npx vite build --mode development 2>&1 | tail -5
```

Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/components/chat/Composer.vue
git commit -m "fix: add avatar image src to @mention popover"
```

---

### Task 8: chat.js — SSE 事件写入 senderAgentAvatarUrl

**Files:**
- Modify: `AIagent_frontend/src/stores/chat.js`

- [ ] **Step 1: agent_message_start 处理中补充 avatarUrl**

在第 282-291 行的 `addMessageLocal` 调用中增加一行：

```js
sse.on('agent_message_start', (data) => {
  const hasDependency = !!data.dependsOn
  const agentId = addMessageLocal('assistant', '', {
    status: hasDependency ? 'waiting' : 'streaming',
    senderAgentName: data.agentName,
    senderAgentId: data.agentId,
    senderAgentAvatarUrl: data.avatarUrl || null,
    dependsOn: data.dependsOn || null
  })
  agentStreams.value.set(data.agentName, agentId)
})
```

- [ ] **Step 2: 验证前端编译通过**

```bash
cd D:/code/Loom/AIagent_frontend && npx vite build --mode development 2>&1 | tail -5
```

Expected: no errors

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/stores/chat.js
git commit -m "fix: capture senderAgentAvatarUrl from SSE agent_message_start events"
```

---

### 验证清单

全部 8 个任务完成后，进行端到端验证：

- [ ] 启动后端：`cd mateclaw-server && mvn spring-boot:run`
- [ ] 启动前端：`cd AIagent_frontend && npm run dev`
- [ ] 在 Agent 设置页上传头像，确认预览和保存正常
- [ ] 刷新后头像 URL 可直接在浏览器访问（验证静态资源映射）
- [ ] 会话列表中出现 Agent 头像（验证 ConversationVO 填充）
- [ ] 一对一聊天中消息气泡显示 Agent 头像（验证 MessageVO 填充）
- [ ] 群聊中不同 Agent 的发言各有自己的头像（验证 SSE 事件填充）
- [ ] 点击头像弹出 NImage Modal 查看原图
- [ ] @mention 弹窗中 Agent 列表显示头像
- [ ] 未上传头像时显示首字母 + 彩色背景
