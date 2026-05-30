# AgentHub Backend — Multi-Agent Collaboration Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement complete backend for the AgentHub multi-agent collaboration platform by extending mateclaw-dev with 12 Flyway migrations, 9 new entities, 8 new services, 5 new controllers, SSE event extensions, and integration tests — following the Extend-First, Then-Build-New strategy.

**Architecture:** Phase 1 extends 3 existing tables (mate_agent, mate_conversation, mate_message) and their entities/services/controllers. Phase 2 adds 9 new tables across 5 new packages (group/, orchestrator/, artifact/, message/) plus a storage abstraction. The Orchestrator reuses existing AgentGraphBuilder with agentType="orchestrator"; DelegateAgentTool gets group-context-aware delegation.

**Tech Stack:** Spring Boot 3.5 + MyBatis-Plus 3.5.16 + Flyway + H2/MySQL + Lombok + Spring Security JWT + SSE (SseEmitter)

---

## File Structure

### Phase 1 — Modified Files

| File | Change |
|---|---|
| `.../db/migration/h2/V120__extend_agent.sql` | Create |
| `.../db/migration/mysql/V120__extend_agent.sql` | Create |
| `.../db/migration/h2/V121__extend_conversation.sql` | Create |
| `.../db/migration/mysql/V121__extend_conversation.sql` | Create |
| `.../db/migration/h2/V122__extend_message.sql` | Create |
| `.../db/migration/mysql/V122__extend_message.sql` | Create |
| `.../agent/model/AgentEntity.java` | Modify — add 5 fields |
| `.../workspace/conversation/model/ConversationEntity.java` | Modify — add 6 fields |
| `.../workspace/conversation/model/MessageEntity.java` | Modify — add 5 fields |
| `.../agent/service/AgentService.java` | Modify — add stats/avatar/status |
| `.../agent/controller/AgentController.java` | Modify — add 3 endpoints |
| `.../workspace/conversation/service/ConversationService.java` | Modify — add archive/type-filter/pins |
| `.../workspace/conversation/controller/ConversationController.java` | Modify — add archive/pins endpoints |
| `.../workspace/conversation/vo/ConversationVO.java` | Modify — add new display fields |

### Phase 2 — New Files

| Package | Files |
|---|---|
| `vip/mate/group/model/` | GroupConversationEntity.java, GroupMemberEntity.java |
| `vip/mate/group/repository/` | GroupConversationMapper.java, GroupMemberMapper.java |
| `vip/mate/group/service/` | GroupConversationService.java |
| `vip/mate/group/controller/` | GroupConversationController.java |
| `vip/mate/orchestrator/model/` | OrchestratorTaskEntity.java, OrchestratorAssignmentEntity.java |
| `vip/mate/orchestrator/repository/` | OrchestratorTaskMapper.java, OrchestratorAssignmentMapper.java |
| `vip/mate/orchestrator/service/` | OrchestratorService.java |
| `vip/mate/orchestrator/controller/` | OrchestratorController.java |
| `vip/mate/orchestrator/event/` | OrchestratorEventPublisher.java |
| `vip/mate/artifact/model/` | ArtifactEntity.java, ArtifactVersionEntity.java, DeployRecordEntity.java |
| `vip/mate/artifact/repository/` | ArtifactMapper.java, ArtifactVersionMapper.java, DeployRecordMapper.java |
| `vip/mate/artifact/service/` | ArtifactService.java, ArtifactDeployService.java |
| `vip/mate/artifact/storage/` | ArtifactStorageProvider.java, LocalFileSystemProvider.java |
| `vip/mate/artifact/controller/` | ArtifactController.java |
| `vip/mate/message/model/` | MessagePinEntity.java, MessageReactionEntity.java |
| `vip/mate/message/repository/` | MessagePinMapper.java, MessageReactionMapper.java |
| `vip/mate/message/service/` | MessagePinService.java, MessageReactionService.java |
| `.../tool/builtin/DelegateAgentTool.java` | Modify — group-context-aware delegation |
| `.../chat/controller/ChatController.java` | Modify — new SSE event types |
| Migrations V123–V131 | 18 files (9 h2 + 9 mysql) |
| Tests | 5 integration test classes |

---

### Task 1: V120 — Extend mate_agent table

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\h2\V120__extend_agent.sql`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\mysql\V120__extend_agent.sql`

- [ ] **Step 1: Write H2 migration**

```sql
-- V120: Extend mate_agent with avatar, capability_tags, agent_status, is_public columns
-- Aligns with 数据库设计文档 3.1

ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500);
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS capability_tags VARCHAR(500);
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS agent_status VARCHAR(20) DEFAULT 'AVAILABLE';
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS is_public BOOLEAN DEFAULT TRUE;
```

- [ ] **Step 2: Write MySQL migration**

```sql
-- V120: Extend mate_agent with avatar, capability_tags, agent_status, is_public columns
-- Aligns with 数据库设计文档 3.1

ALTER TABLE mate_agent ADD COLUMN avatar_url VARCHAR(500);
ALTER TABLE mate_agent ADD COLUMN capability_tags VARCHAR(500);
ALTER TABLE mate_agent ADD COLUMN agent_status VARCHAR(20) DEFAULT 'AVAILABLE';
ALTER TABLE mate_agent ADD COLUMN is_public TINYINT(1) DEFAULT 1;
```

- [ ] **Step 3: Verify migrations exist in both directories**

Run: `ls "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/resources/db/migration/h2/V120__extend_agent.sql" "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/resources/db/migration/mysql/V120__extend_agent.sql"`

---

### Task 2: Update AgentEntity

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\agent\model\AgentEntity.java`

- [ ] **Step 1: Add 5 new fields to AgentEntity**

After the `defaultThinkingLevel` field (line 79), add:

```java
    /** Agent 头像 URL — 数据库设计文档 3.1 */
    private String avatarUrl;

    /** 能力标签 JSON 数组，如 ["编码","文档"] — 数据库设计文档 3.1 */
    private String capabilityTags;

    /** Agent 在线状态：AVAILABLE / BUSY / OFFLINE — 数据库设计文档 3.1 */
    private String agentStatus;

    /** 是否对工作区全员可见 — 数据库设计文档 3.1 */
    private Integer isPublic;
```

No changes to `agentType` field needed — it's already VARCHAR, "orchestrator" is a valid value at the DB level.

- [ ] **Step 2: Verify entity compiles**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 3: V121 — Extend mate_conversation table

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\h2\V121__extend_conversation.sql`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\mysql\V121__extend_conversation.sql`

- [ ] **Step 1: Write H2 migration**

```sql
-- V121: Extend mate_conversation for group chat and conversation enhancements
-- Aligns with 数据库设计文档 3.2

ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS conversation_type VARCHAR(20) DEFAULT 'direct';
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE;
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS pinned_at TIMESTAMP;
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS last_message_preview VARCHAR(200);
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS unread_count INT DEFAULT 0;

-- Backfill last_active_at for existing data
UPDATE mate_conversation SET last_active_at = update_time WHERE last_active_at IS NULL;
UPDATE mate_conversation SET conversation_type = 'direct' WHERE conversation_type IS NULL;
```

- [ ] **Step 2: Write MySQL migration**

```sql
-- V121: Extend mate_conversation for group chat and conversation enhancements
-- Aligns with 数据库设计文档 3.2

ALTER TABLE mate_conversation ADD COLUMN conversation_type VARCHAR(20) DEFAULT 'direct';
ALTER TABLE mate_conversation ADD COLUMN archived TINYINT(1) DEFAULT 0;
ALTER TABLE mate_conversation ADD COLUMN pinned_at DATETIME;
ALTER TABLE mate_conversation ADD COLUMN last_active_at DATETIME DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE mate_conversation ADD COLUMN last_message_preview VARCHAR(200);
ALTER TABLE mate_conversation ADD COLUMN unread_count INT DEFAULT 0;

UPDATE mate_conversation SET last_active_at = update_time WHERE last_active_at IS NULL;
UPDATE mate_conversation SET conversation_type = 'direct' WHERE conversation_type IS NULL;
```

- [ ] **Step 3: Verify files exist**

Run: `ls "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/resources/db/migration/h2/V121__extend_conversation.sql" "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/resources/db/migration/mysql/V121__extend_conversation.sql"`

---

### Task 4: Update ConversationEntity

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\workspace\conversation\model\ConversationEntity.java`

- [ ] **Step 1: Add 6 new fields to ConversationEntity**

After the `modelName` field (line 62), add:

```java
    /** 会话类型：direct（单聊）/ group（群聊） — 数据库设计文档 3.2 */
    private String conversationType;

    /** 是否已归档 — 数据库设计文档 3.2 */
    private Integer archived;

    /** 置顶时间，NULL 表示未置顶 — 数据库设计文档 3.2 */
    private LocalDateTime pinnedAt;

    /** 最近活跃时间，用于排序 — 数据库设计文档 3.2 */
    private LocalDateTime lastActiveAt;

    /** 最后一条消息摘要 — 数据库设计文档 3.2 */
    private String lastMessagePreview;

    /** 当前用户未读消息计数 — 数据库设计文档 3.2 */
    private Integer unreadCount;
```

The existing `lastActiveTime` field is kept for backward compatibility. The new `lastActiveAt` field is the primary sort key going forward. In the service layer, both are maintained.

- [ ] **Step 2: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 5: V122 — Extend mate_message table

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\h2\V122__extend_message.sql`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\mysql\V122__extend_message.sql`

- [ ] **Step 1: Write H2 migration**

```sql
-- V122: Extend mate_message for rich media types and interaction
-- Aligns with 数据库设计文档 3.3

ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS message_type VARCHAR(30) DEFAULT 'text';
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS reply_to_id BIGINT;
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS sender_agent_id BIGINT;
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS artifact_refs VARCHAR(2000);
ALTER TABLE mate_message ADD COLUMN IF NOT EXISTS regenerated_from_id BIGINT;

-- Backfill: detect code messages
UPDATE mate_message SET message_type = 'text' WHERE message_type IS NULL;
```

- [ ] **Step 2: Write MySQL migration**

```sql
-- V122: Extend mate_message for rich media types and interaction
-- Aligns with 数据库设计文档 3.3

ALTER TABLE mate_message ADD COLUMN message_type VARCHAR(30) DEFAULT 'text';
ALTER TABLE mate_message ADD COLUMN reply_to_id BIGINT;
ALTER TABLE mate_message ADD COLUMN sender_agent_id BIGINT;
ALTER TABLE mate_message ADD COLUMN artifact_refs VARCHAR(2000);
ALTER TABLE mate_message ADD COLUMN regenerated_from_id BIGINT;

UPDATE mate_message SET message_type = 'text' WHERE message_type IS NULL;
```

- [ ] **Step 3: Verify files exist**

Run: `ls "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/resources/db/migration/h2/V122__extend_message.sql" "D:/code/Loom/mateclaw-dev/mateclaw-server/src/main/resources/db/migration/mysql/V122__extend_message.sql"`

---

### Task 6: Update MessageEntity

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\workspace\conversation\model\MessageEntity.java`

- [ ] **Step 1: Add 5 new fields to MessageEntity**

After the `status` field (line 53), add:

```java
    /** 消息类型：text / code / diff / image / file / preview_card / plan_card / system — 数据库设计文档 3.3 */
    private String messageType;

    /** 引用的消息 ID — 数据库设计文档 3.3 */
    private Long replyToId;

    /** 发送消息的 Agent ID（Agent 消息非空，用户消息为空） — 数据库设计文档 3.3 */
    private Long senderAgentId;

    /** 关联的产物 ID JSON 数组 — 数据库设计文档 3.3 */
    private String artifactRefs;

    /** 重新生成时指向原始消息 — 数据库设计文档 3.3 */
    private Long regeneratedFromId;
```

- [ ] **Step 2: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 7: Update AgentService — stats, avatar, status management

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\agent\service\AgentService.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\agent\vo\AgentStatsVO.java`

- [ ] **Step 1: Create AgentStatsVO**

Create `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\agent\vo\AgentStatsVO.java`:

```java
package vip.mate.agent.vo;

import lombok.Builder;
import lombok.Data;

/** Agent 使用统计 VO — API 文档 4. Agent Stats */
@Data
@Builder
public class AgentStatsVO {
    private Long agentId;
    private Long totalConversations;
    private Long totalMessages;
    private Long totalTokens;
    private Long avgResponseTimeMs;
    private String lastActiveAt;
}
```

- [ ] **Step 2: Add stats query method to AgentService**

Add to `AgentService.java`:

```java
private final vip.mate.workspace.conversation.repository.MessageMapper messageMapper;
private final vip.mate.workspace.conversation.repository.ConversationMapper conversationMapper;

/**
 * 查询 Agent 使用统计 — API 文档 4. Agent Stats
 * 统计会话数、消息数、Token 消耗、平均响应时间
 */
public AgentStatsVO getAgentStats(Long agentId) {
    AgentEntity agent = agentMapper.selectById(agentId);
    if (agent == null) {
        throw new BusinessException("Agent not found: " + agentId);
    }
    // Count conversations involving this agent
    Long totalConversations = conversationMapper.selectCount(
        new LambdaQueryWrapper<ConversationEntity>()
            .eq(ConversationEntity::getAgentId, agentId)
            .eq(ConversationEntity::getDeleted, 0));
    // Count messages from this agent
    Long totalMessages = messageMapper.selectCount(
        new LambdaQueryWrapper<MessageEntity>()
            .eq(MessageEntity::getSenderAgentId, agentId)
            .eq(MessageEntity::getDeleted, 0));
    // Sum tokens
    List<MessageEntity> msgs = messageMapper.selectList(
        new LambdaQueryWrapper<MessageEntity>()
            .select(MessageEntity::getTokenUsage)
            .eq(MessageEntity::getSenderAgentId, agentId)
            .eq(MessageEntity::getDeleted, 0));
    long totalTokens = msgs.stream().filter(m -> m.getTokenUsage() != null).mapToLong(MessageEntity::getTokenUsage).sum();
    // Avg response time (approximate from createTime deltas)
    long avgMs = 0;
    // ... simplified for plan brevity — full implementation in code

    return AgentStatsVO.builder()
        .agentId(agentId)
        .totalConversations(totalConversations)
        .totalMessages(totalMessages)
        .totalTokens(totalTokens)
        .avgResponseTimeMs(avgMs)
        .lastActiveAt(agent.getUpdateTime() != null ? agent.getUpdateTime().toString() : null)
        .build();
}
```

(Full implementation of `getAgentStats` is in the actual code — this plan shows the structure; the actual file will include the complete LambdaQueryWrapper logic with proper imports.)

- [ ] **Step 3: Add avatar upload method**

Add to `AgentService.java`:

```java
/**
 * 上传 Agent 头像 — API 文档 4. Agent Management PUT /agents/{id}/avatar
 * 保存到 {workspace.base-dir}/avatars/{agentId}/
 */
public void uploadAvatar(Long agentId, MultipartFile file) {
    AgentEntity agent = agentMapper.selectById(agentId);
    if (agent == null) {
        throw new BusinessException("Agent not found: " + agentId);
    }
    if (file.getSize() > 2 * 1024 * 1024) {
        throw new BusinessException("Avatar file size must be under 2MB");
    }
    String contentType = file.getContentType();
    if (contentType == null || !contentType.startsWith("image/")) {
        throw new BusinessException("Avatar must be an image file");
    }
    // ... save file to workspace dir, update avatarUrl on entity
}
```

- [ ] **Step 4: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 8: Update AgentController — new endpoints

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\agent\controller\AgentController.java`

- [ ] **Step 1: Add avatar upload endpoint**

```java
@Operation(summary = "上传 Agent 头像")
@PutMapping("/{id}/avatar")
@RequireWorkspaceRole("member")
public R<Map<String, String>> uploadAvatar(
        @PathVariable Long id,
        @RequestParam("file") MultipartFile file,
        @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
    agentService.uploadAvatar(id, file);
    return R.ok(Map.of("message", "Avatar uploaded"));
}
```

Add imports: `import org.springframework.web.multipart.MultipartFile;`, `import vip.mate.workspace.core.annotation.RequireWorkspaceRole;`

- [ ] **Step 2: Add stats endpoint**

```java
@Operation(summary = "获取 Agent 使用统计")
@GetMapping("/{id}/stats")
@RequireWorkspaceRole("viewer")
public R<AgentStatsVO> getStats(@PathVariable Long id) {
    return R.ok(agentService.getAgentStats(id));
}
```

- [ ] **Step 3: Add capabilities endpoint**

```java
@Operation(summary = "获取 Agent 能力标签")
@GetMapping("/{id}/capabilities")
@RequireWorkspaceRole("viewer")
public R<Map<String, Object>> getCapabilities(@PathVariable Long id,
        @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
    AgentEntity agent = agentService.getById(id);
    return R.ok(Map.of(
        "agentId", agent.getId(),
        "capabilityTags", agent.getCapabilityTags() != null ? agent.getCapabilityTags() : "[]",
        "agentStatus", agent.getAgentStatus() != null ? agent.getAgentStatus() : "AVAILABLE"
    ));
}
```

- [ ] **Step 4: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 9: Update ConversationService — archive, type filter, pins

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\workspace\conversation\ConversationService.java`

- [ ] **Step 1: Add archive/unarchive method**

```java
/**
 * 归档 / 取消归档会话 — API 文档 5. Conversation Management
 */
@Transactional
public void setArchived(String conversationId, boolean archived) {
    conversationMapper.update(null,
        new LambdaUpdateWrapper<ConversationEntity>()
            .eq(ConversationEntity::getConversationId, conversationId)
            .set(ConversationEntity::getArchived, archived ? 1 : 0)
            .set(ConversationEntity::getLastActiveAt, LocalDateTime.now()));
}
```

- [ ] **Step 2: Update listConversations to support conversationType filter**

Modify the existing `listConversations` method to accept an optional `conversationType` parameter and add the LambdaQueryWrapper condition:

```java
public List<ConversationVO> listConversations(String username, Long workspaceId) {
    return listConversations(username, workspaceId, null);
}

public List<ConversationVO> listConversations(String username, Long workspaceId, String conversationType) {
    LambdaQueryWrapper<ConversationEntity> qw = new LambdaQueryWrapper<ConversationEntity>()
        .eq(ConversationEntity::getUsername, username)
        .eq(ConversationEntity::getDeleted, 0)
        .eq(workspaceId != null, ConversationEntity::getWorkspaceId, workspaceId)
        .orderByDesc(ConversationEntity::getLastActiveAt);
    if (conversationType != null && !conversationType.isBlank()) {
        qw.eq(ConversationEntity::getConversationType, conversationType);
    }
    // ... rest of existing logic
}
```

- [ ] **Step 3: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 10: Update ConversationController — archive endpoint, conversationType filter

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\workspace\conversation\controller\ConversationController.java`

- [ ] **Step 1: Add conversationType query param to list endpoint**

Modify the `list()` method signature to accept optional `conversationType`:

```java
@Operation(summary = "获取会话列表")
@GetMapping
public R<List<ConversationVO>> list(
        Authentication auth,
        @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
        @RequestParam(required = false) String conversationType) {
    String username = auth != null ? auth.getName() : "anonymous";
    return R.ok(conversationService.listConversations(username, workspaceId, conversationType));
}
```

- [ ] **Step 2: Add archive endpoint**

```java
@Operation(summary = "归档或取消归档会话")
@PutMapping("/{conversationId}/archive")
public R<Void> setArchived(@PathVariable String conversationId,
                           @RequestBody Map<String, Boolean> body,
                           Authentication auth) {
    String username = auth != null ? auth.getName() : "anonymous";
    if (!conversationService.isConversationOwner(conversationId, username)) {
        return R.fail(403, "无权操作该会话");
    }
    conversationService.setArchived(conversationId, Boolean.TRUE.equals(body.get("archived")));
    return R.ok();
}
```

- [ ] **Step 3: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 11: V123–V124 — Create group conversation tables

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\h2\V123__create_group_conversation.sql`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\mysql\V123__create_group_conversation.sql`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\h2\V124__create_group_member.sql`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\mysql\V124__create_group_member.sql`

- [ ] **Step 1: Write V123 H2 migration**

```sql
-- V123: Create mate_group_conversation table
-- Aligns with 数据库设计文档 4.1

CREATE TABLE IF NOT EXISTS mate_group_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    orchestrator_agent_id BIGINT NOT NULL,
    scheduling_mode VARCHAR(20) NOT NULL DEFAULT 'auto',
    failure_policy VARCHAR(30) NOT NULL DEFAULT 'fail_tolerant',
    max_parallel_tasks INT NOT NULL DEFAULT 8,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_conversation UNIQUE (conversation_id),
    CONSTRAINT fk_gc_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_gc_orchestrator FOREIGN KEY (orchestrator_agent_id) REFERENCES mate_agent(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_orchestrator ON mate_group_conversation(orchestrator_agent_id);
```

- [ ] **Step 2: Write V123 MySQL migration** (same DDL, use `TINYINT`, `DATETIME`, engine)

```sql
-- V123: Create mate_group_conversation table

CREATE TABLE IF NOT EXISTS mate_group_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    orchestrator_agent_id BIGINT NOT NULL,
    scheduling_mode VARCHAR(20) NOT NULL DEFAULT 'auto',
    failure_policy VARCHAR(30) NOT NULL DEFAULT 'fail_tolerant',
    max_parallel_tasks INT NOT NULL DEFAULT 8,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conversation (conversation_id),
    KEY idx_orchestrator (orchestrator_agent_id),
    CONSTRAINT fk_gc_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_gc_orchestrator FOREIGN KEY (orchestrator_agent_id) REFERENCES mate_agent(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 3: Write V124 H2 migration**

```sql
-- V124: Create mate_group_member table
-- Aligns with 数据库设计文档 4.2

CREATE TABLE IF NOT EXISTS mate_group_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    member_role VARCHAR(20) NOT NULL DEFAULT 'member',
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_conversation_agent UNIQUE (conversation_id, agent_id),
    CONSTRAINT fk_gm_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_gm_agent FOREIGN KEY (agent_id) REFERENCES mate_agent(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_agent ON mate_group_member(agent_id);
```

- [ ] **Step 4: Write V124 MySQL migration**

```sql
CREATE TABLE IF NOT EXISTS mate_group_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    member_role VARCHAR(20) NOT NULL DEFAULT 'member',
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conversation_agent (conversation_id, agent_id),
    KEY idx_agent (agent_id),
    CONSTRAINT fk_gm_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_gm_agent FOREIGN KEY (agent_id) REFERENCES mate_agent(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

### Task 12: GroupConversationEntity + GroupMemberEntity + Mappers

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\group\model\GroupConversationEntity.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\group\model\GroupMemberEntity.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\group\repository\GroupConversationMapper.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\group\repository\GroupMemberMapper.java`

- [ ] **Step 1: Create GroupConversationEntity**

```java
package vip.mate.group.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 群聊会话扩展实体 — 数据库设计文档 4.1 */
@Data
@TableName("mate_group_conversation")
public class GroupConversationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** FK 关联 mate_conversation.id */
    private Long conversationId;

    /** FK 关联 mate_agent.id, 群聊的 Orchestrator Agent */
    private Long orchestratorAgentId;

    /** auto (自动分派) / manual (用户 @ 指定) */
    private String schedulingMode;

    /** fail_fast / fail_tolerant */
    private String failurePolicy;

    /** 最大并行任务数 */
    private Integer maxParallelTasks;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: Create GroupMemberEntity**

```java
package vip.mate.group.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 群聊成员实体 — 数据库设计文档 4.2 */
@Data
@TableName("mate_group_member")
public class GroupMemberEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** FK 关联 mate_conversation.id */
    private Long conversationId;

    /** FK 关联 mate_agent.id */
    private Long agentId;

    /** orchestrator / member */
    private String memberRole;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime joinedAt;
}
```

- [ ] **Step 3: Create Mappers**

```java
package vip.mate.group.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.group.model.GroupConversationEntity;

@Mapper
public interface GroupConversationMapper extends BaseMapper<GroupConversationEntity> {}
```

```java
package vip.mate.group.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.group.model.GroupMemberEntity;

@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMemberEntity> {}
```

- [ ] **Step 4: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 13: GroupConversationService

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\group\service\GroupConversationService.java`

- [ ] **Step 1: Create GroupConversationService**

```java
package vip.mate.group.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.BusinessException;
import vip.mate.group.model.*;
import vip.mate.group.repository.*;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 群聊会话管理服务 — 产品设计文档 4.1.3 + API 文档 6.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupConversationService {

    private final GroupConversationMapper groupConversationMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final ConversationMapper conversationMapper;
    private final AgentMapper agentMapper;

    /**
     * 创建群聊会话 — API 文档 6. Create Group Conversation
     */
    @Transactional
    public Map<String, Object> createGroup(String username, Long workspaceId, String title,
                                            Long orchestratorAgentId, List<Long> agentIds,
                                            String schedulingMode, String failurePolicy, Integer maxParallelTasks) {
        if (agentIds == null || agentIds.size() < 2) {
            throw new BusinessException("群聊至少需要 2 个 Agent");
        }
        // Validate orchestrator
        AgentEntity orchestrator = agentMapper.selectById(orchestratorAgentId);
        if (orchestrator == null || !"orchestrator".equals(orchestrator.getAgentType())) {
            throw new BusinessException("指定的 Orchestrator Agent 不存在或类型不正确");
        }
        // Create conversation
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId(UUID.randomUUID().toString());
        conv.setTitle(title);
        conv.setAgentId(orchestratorAgentId);
        conv.setUsername(username);
        conv.setMessageCount(0);
        conv.setConversationType("group");
        conv.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
        conv.setArchived(0);
        conv.setUnreadCount(0);
        conv.setLastActiveAt(LocalDateTime.now());
        conversationMapper.insert(conv);

        // Create group config
        GroupConversationEntity gc = new GroupConversationEntity();
        gc.setConversationId(conv.getId());
        gc.setOrchestratorAgentId(orchestratorAgentId);
        gc.setSchedulingMode(schedulingMode != null ? schedulingMode : "auto");
        gc.setFailurePolicy(failurePolicy != null ? failurePolicy : "fail_tolerant");
        gc.setMaxParallelTasks(maxParallelTasks != null ? maxParallelTasks : 8);
        groupConversationMapper.insert(gc);

        // Add members
        addMemberInternal(conv.getId(), orchestratorAgentId, "orchestrator");
        for (Long agentId : agentIds) {
            if (!agentId.equals(orchestratorAgentId)) {
                addMemberInternal(conv.getId(), agentId, "member");
            }
        }

        return buildGroupResponse(conv.getId(), conv, gc);
    }

    private void addMemberInternal(Long conversationId, Long agentId, String role) {
        GroupMemberEntity member = new GroupMemberEntity();
        member.setConversationId(conversationId);
        member.setAgentId(agentId);
        member.setMemberRole(role);
        groupMemberMapper.insert(member);
    }

    /**
     * 列出当前用户的群聊会话 — API 文档 6. List Group Conversations
     */
    public IPage<Map<String, Object>> listGroups(String username, int page, int size) {
        Page<ConversationEntity> pageReq = new Page<>(page, size);
        IPage<ConversationEntity> result = conversationMapper.selectPage(pageReq,
            new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getUsername, username)
                .eq(ConversationEntity::getConversationType, "group")
                .eq(ConversationEntity::getDeleted, 0)
                .orderByDesc(ConversationEntity::getLastActiveAt));
        return result.convert(conv -> {
            GroupConversationEntity gc = groupConversationMapper.selectOne(
                new LambdaQueryWrapper<GroupConversationEntity>()
                    .eq(GroupConversationEntity::getConversationId, conv.getId()));
            return buildGroupResponse(conv.getId(), conv, gc);
        });
    }

    // ... additional methods: getGroupDetail, updateGroupConfig, batchReplaceMembers,
    //     addMember, removeMember, buildGroupResponse
    // Full implementation in code — all follow the same LambdaQueryWrapper pattern
}
```

(Full service includes `getGroupDetail`, `updateGroupConfig`, `addMember`, `removeMember`, `batchReplaceMembers`, and `buildGroupResponse` helper. All follow the exact same patterns shown above.)

- [ ] **Step 2: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 14: GroupConversationController

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\group\controller\GroupConversationController.java`

- [ ] **Step 1: Create GroupConversationController** (full file)

```java
package vip.mate.group.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.group.service.GroupConversationService;

import java.util.List;
import java.util.Map;

/**
 * 群聊会话管理接口 — API 文档 6. Group Conversations
 */
@Tag(name = "群聊会话管理")
@RestController
@RequestMapping("/api/v1/conversations/group")
@RequiredArgsConstructor
public class GroupConversationController {

    private final GroupConversationService groupConversationService;

    @Operation(summary = "创建群聊会话")
    @PostMapping
    public R<Map<String, Object>> create(
            Authentication auth,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody Map<String, Object> body) {
        String username = auth != null ? auth.getName() : "anonymous";
        String title = (String) body.getOrDefault("title", "Group Chat");
        Long orchestratorId = Long.valueOf(body.get("orchestratorAgentId").toString());
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.get("agentIds");
        List<Long> agentIds = rawIds.stream().map(Long::valueOf).toList();
        String schedulingMode = (String) body.getOrDefault("schedulingMode", "auto");
        String failurePolicy = (String) body.getOrDefault("failurePolicy", "fail_tolerant");
        Integer maxParallel = body.get("maxParallelTasks") != null
            ? Integer.valueOf(body.get("maxParallelTasks").toString()) : 8;
        return R.ok(groupConversationService.createGroup(username, workspaceId, title,
            orchestratorId, agentIds, schedulingMode, failurePolicy, maxParallel));
    }

    @Operation(summary = "获取群聊会话列表")
    @GetMapping
    public R<IPage<Map<String, Object>>> list(
            Authentication auth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String username = auth != null ? auth.getName() : "anonymous";
        return R.ok(groupConversationService.listGroups(username, page, size));
    }

    @Operation(summary = "获取群聊详情")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(groupConversationService.getGroupDetail(id));
    }

    @Operation(summary = "更新群聊配置")
    @PutMapping("/{id}")
    public R<Void> updateConfig(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        groupConversationService.updateGroupConfig(id, body);
        return R.ok();
    }

    @Operation(summary = "批量替换群聊成员")
    @PutMapping("/{id}/members")
    public R<Void> batchReplaceMembers(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.get("agentIds");
        List<Long> agentIds = rawIds.stream().map(Long::valueOf).toList();
        groupConversationService.batchReplaceMembers(id, agentIds);
        return R.ok();
    }

    @Operation(summary = "添加群聊成员")
    @PostMapping("/{id}/members")
    public R<Void> addMember(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long agentId = Long.valueOf(body.get("agentId").toString());
        String role = (String) body.getOrDefault("memberRole", "member");
        groupConversationService.addMember(id, agentId, role);
        return R.ok();
    }

    @Operation(summary = "移除群聊成员")
    @DeleteMapping("/{id}/members/{agentId}")
    public R<Void> removeMember(@PathVariable Long id, @PathVariable Long agentId) {
        groupConversationService.removeMember(id, agentId);
        return R.ok();
    }
}
```

- [ ] **Step 2: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 15: V125–V126 — Create orchestrator tables

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\h2\V125__create_orchestrator_task.sql`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\mysql\V125__create_orchestrator_task.sql`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\h2\V126__create_orchestrator_assignment.sql`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\resources\db\migration\mysql\V126__create_orchestrator_assignment.sql`

- [ ] **Step 1: Write V125 migrations** (H2 + MySQL, DDL from 数据库设计文档 4.3)

```sql
-- V125 H2:
CREATE TABLE IF NOT EXISTS mate_orchestrator_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    title VARCHAR(500) NOT NULL,
    plan_json CLOB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    total_assignments INT NOT NULL DEFAULT 0,
    completed_assignments INT NOT NULL DEFAULT 0,
    failed_assignments INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    aggregation_message_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ot_conversation FOREIGN KEY (conversation_id) REFERENCES mate_conversation(id) ON DELETE CASCADE,
    CONSTRAINT fk_ot_message FOREIGN KEY (message_id) REFERENCES mate_message(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ot_conversation ON mate_orchestrator_task(conversation_id);
CREATE INDEX IF NOT EXISTS idx_ot_status ON mate_orchestrator_task(status);
CREATE INDEX IF NOT EXISTS idx_ot_created ON mate_orchestrator_task(created_at);
```

MySQL version uses `MEDIUMTEXT` for `plan_json`, `DATETIME` for timestamps, `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`.

- [ ] **Step 2: Write V126 migrations** (H2 + MySQL, DDL from 数据库设计文档 4.4)

```sql
-- V126 H2:
CREATE TABLE IF NOT EXISTS mate_orchestrator_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    step_order INT NOT NULL,
    execution_mode VARCHAR(20) NOT NULL DEFAULT 'sequential',
    goal CLOB NOT NULL,
    dependency_on BIGINT,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    child_conversation_id BIGINT,
    result_summary VARCHAR(2000),
    error_message VARCHAR(2000),
    retry_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_oa_task FOREIGN KEY (task_id) REFERENCES mate_orchestrator_task(id) ON DELETE CASCADE,
    CONSTRAINT fk_oa_agent FOREIGN KEY (agent_id) REFERENCES mate_agent(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_oa_task ON mate_orchestrator_assignment(task_id);
CREATE INDEX IF NOT EXISTS idx_oa_agent ON mate_orchestrator_assignment(agent_id);
CREATE INDEX IF NOT EXISTS idx_oa_status ON mate_orchestrator_assignment(status);
CREATE INDEX IF NOT EXISTS idx_oa_child_conv ON mate_orchestrator_assignment(child_conversation_id);
```

MySQL version uses `TEXT` for `goal`, `DATETIME` for timestamps, `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`.

---

### Task 16: Orchestrator Entities + Mappers

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\orchestrator\model\OrchestratorTaskEntity.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\orchestrator\model\OrchestratorAssignmentEntity.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\orchestrator\repository\OrchestratorTaskMapper.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\orchestrator\repository\OrchestratorAssignmentMapper.java`

- [ ] **Step 1: Create OrchestratorTaskEntity**

```java
package vip.mate.orchestrator.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Orchestrator 任务实体 — 数据库设计文档 4.3 */
@Data
@TableName("mate_orchestrator_task")
public class OrchestratorTaskEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;
    private Long messageId;
    private String title;

    @TableField(value = "plan_json", updateStrategy = FieldStrategy.ALWAYS)
    private String planJson;

    private String status;
    private Integer totalAssignments;
    private Integer completedAssignments;
    private Integer failedAssignments;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long aggregationMessageId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: Create OrchestratorAssignmentEntity**

```java
package vip.mate.orchestrator.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Orchestrator 任务分派实体 — 数据库设计文档 4.4 */
@Data
@TableName("mate_orchestrator_assignment")
public class OrchestratorAssignmentEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long taskId;
    private Long agentId;
    private Integer stepOrder;
    private String executionMode;

    @TableField(value = "goal", updateStrategy = FieldStrategy.ALWAYS)
    private String goal;

    private Long dependencyOn;
    private String status;
    private Long childConversationId;
    private String resultSummary;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: Create Mappers** (same `@Mapper extends BaseMapper` pattern)

- [ ] **Step 4: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 17: OrchestratorService

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\orchestrator\service\OrchestratorService.java`

- [ ] **Step 1: Create OrchestratorService** (core scheduling logic)

```java
package vip.mate.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.BusinessException;
import vip.mate.orchestrator.model.*;
import vip.mate.orchestrator.repository.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Orchestrator 调度服务 — 产品设计文档 4.2 + API 文档 9.
 *
 * 核心流程：
 * 1. 群聊用户消息 → Orchestrator LLM 分析意图生成 Plan JSON
 * 2. Plan 保存到 mate_orchestrator_task
 * 3. 对每个 step 创建 mate_orchestrator_assignment
 * 4. 通过 DelegateAgentTool 调度子 Agent 执行
 * 5. 收集结果生成聚合消息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorService {

    private final OrchestratorTaskMapper taskMapper;
    private final OrchestratorAssignmentMapper assignmentMapper;

    /**
     * 创建任务（由 Orchestrator Agent 的 Plan-Execute 流程调用）
     * — API 文档 9. Orchestrator Scheduling
     */
    @Transactional
    public OrchestratorTaskEntity createTask(Long conversationId, Long messageId,
                                              String title, String planJson,
                                              List<Map<String, Object>> steps) {
        OrchestratorTaskEntity task = new OrchestratorTaskEntity();
        task.setConversationId(conversationId);
        task.setMessageId(messageId);
        task.setTitle(title);
        task.setPlanJson(planJson);
        task.setStatus("pending");
        task.setTotalAssignments(steps.size());
        task.setCompletedAssignments(0);
        task.setFailedAssignments(0);
        taskMapper.insert(task);

        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            OrchestratorAssignmentEntity assignment = new OrchestratorAssignmentEntity();
            assignment.setTaskId(task.getId());
            assignment.setAgentId(Long.valueOf(step.get("agentId").toString()));
            assignment.setStepOrder(i + 1);
            assignment.setExecutionMode((String) step.getOrDefault("mode", "sequential"));
            assignment.setGoal((String) step.get("goal"));
            if (step.containsKey("dependsOn")) {
                assignment.setDependencyOn(Long.valueOf(step.get("dependsOn").toString()));
            }
            assignment.setStatus("pending");
            assignment.setRetryCount(0);
            assignmentMapper.insert(assignment);
        }
        return task;
    }

    /**
     * 列出群聊的任务计划 — API 文档 9.
     */
    public IPage<OrchestratorTaskEntity> listTasks(Long conversationId, String status,
                                                    int page, int size) {
        Page<OrchestratorTaskEntity> pageReq = new Page<>(page, size);
        LambdaQueryWrapper<OrchestratorTaskEntity> qw = new LambdaQueryWrapper<>();
        if (conversationId != null) {
            qw.eq(OrchestratorTaskEntity::getConversationId, conversationId);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(OrchestratorTaskEntity::getStatus, status);
        }
        qw.orderByDesc(OrchestratorTaskEntity::getCreatedAt);
        return taskMapper.selectPage(pageReq, qw);
    }

    // ... additional methods: getTaskDetail, getAssignments, getAssignmentDetail,
    //     retryAssignments, cancelTask, updateAssignmentStatus
    // Full implementation in code
}
```

- [ ] **Step 2: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 18: OrchestratorController + SSE Event Publisher

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\orchestrator\controller\OrchestratorController.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\orchestrator\event\OrchestratorEventPublisher.java`

- [ ] **Step 1: Create OrchestratorController**

```java
package vip.mate.orchestrator.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.orchestrator.model.*;
import vip.mate.orchestrator.service.OrchestratorService;

import java.util.List;
import java.util.Map;

/**
 * Orchestrator 调度接口 — API 文档 9.
 */
@Tag(name = "Orchestrator 调度")
@RestController
@RequestMapping("/api/v1/orchestrator")
@RequiredArgsConstructor
public class OrchestratorController {

    private final OrchestratorService orchestratorService;

    @Operation(summary = "查询任务列表")
    @GetMapping("/tasks")
    public R<IPage<OrchestratorTaskEntity>> listTasks(
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(orchestratorService.listTasks(conversationId, status, page, size));
    }

    @Operation(summary = "获取任务详情")
    @GetMapping("/tasks/{taskId}")
    public R<OrchestratorTaskEntity> getTask(@PathVariable Long taskId) {
        return R.ok(orchestratorService.getTaskDetail(taskId));
    }

    @Operation(summary = "获取任务分派列表")
    @GetMapping("/tasks/{taskId}/assignments")
    public R<List<OrchestratorAssignmentEntity>> getAssignments(@PathVariable Long taskId) {
        return R.ok(orchestratorService.getAssignments(taskId));
    }

    @Operation(summary = "获取单个分派详情")
    @GetMapping("/assignments/{id}")
    public R<OrchestratorAssignmentEntity> getAssignment(@PathVariable Long id) {
        return R.ok(orchestratorService.getAssignmentDetail(id));
    }

    @Operation(summary = "重试失败的分派")
    @PostMapping("/tasks/{taskId}/retry")
    public R<Void> retry(@PathVariable Long taskId,
                         @RequestBody(required = false) Map<String, Object> body) {
        List<Long> ids = body != null && body.containsKey("assignmentIds")
            ? ((List<?>) body.get("assignmentIds")).stream().map(Object::toString).map(Long::valueOf).toList()
            : null;
        orchestratorService.retryAssignments(taskId, ids);
        return R.ok();
    }

    @Operation(summary = "取消任务")
    @PostMapping("/tasks/{taskId}/cancel")
    public R<Void> cancel(@PathVariable Long taskId) {
        orchestratorService.cancelTask(taskId);
        return R.ok();
    }
}
```

- [ ] **Step 2: Create OrchestratorEventPublisher** (SSE event helper)

```java
package vip.mate.orchestrator.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.web.ChatStreamTracker;

/**
 * 发布 Orchestrator 相关的 SSE 事件 — API 文档 1.7 SSE Protocol
 * 通过现有的 ChatStreamTracker 发送 orchestrator_plan 和 delegation_progress 事件
 */
@Slf4j
@RequiredArgsConstructor
public class OrchestratorEventPublisher {

    private final ChatStreamTracker streamTracker;

    public void publishPlan(String conversationId, Long taskId, String title, String planJson) {
        // Format SSE event: orchestrator_plan
        // Uses ChatStreamTracker.addEvent() pattern
    }

    public void publishDelegationProgress(String conversationId, String agentName,
                                           String status, String summary) {
        // Format SSE event: delegation_progress
    }
}
```

- [ ] **Step 3: Compile verify**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw compile -pl mateclaw-server -q`
Expected: BUILD SUCCESS

---

### Task 19: V127–V128, V131 — Create artifact + deployment tables

**Files:**
- Create: 6 migration files (3 h2 + 3 mysql) for V127 (mate_artifact), V128 (mate_artifact_version), V131 (mate_deploy_record)

- [ ] **Step 1: Write all 6 migration files** (DDL from 数据库设计文档 4.5–4.7, 4.9)

Same pattern as previous migrations. MySQL uses `DATETIME`, `TEXT`/`MEDIUMTEXT`, `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`. H2 uses `TIMESTAMP`, `CLOB`, and `IF NOT EXISTS` qualifiers.

---

### Task 20: Artifact Entities + Mappers

**Files:**
- Create: `ArtifactEntity.java`, `ArtifactVersionEntity.java`, `DeployRecordEntity.java`
- Create: `ArtifactMapper.java`, `ArtifactVersionMapper.java`, `DeployRecordMapper.java`

- [ ] **Step 1: Create ArtifactEntity** (fields per 数据库设计文档 4.5)

```java
package vip.mate.artifact.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_artifact")
public class ArtifactEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long conversationId;
    private Long messageId;
    private Long creatorAgentId;
    private Long workspaceId;
    private String artifactName;
    private String artifactType;
    private String filePath;
    private Integer currentVersion;
    private String deployStatus;
    private String deployUrl;
    private String tags;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: Create ArtifactVersionEntity** (fields per 数据库设计文档 4.6)

```java
@Data
@TableName("mate_artifact_version")
public class ArtifactVersionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long artifactId;
    private Integer versionNumber;
    private Long messageId;
    private String changeSummary;
    private String filePath;
    private String contentHash;
    private String diffFromPrev;
    private String tag;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: Create DeployRecordEntity** (fields per 数据库设计文档 4.9)

```java
@Data
@TableName("mate_deploy_record")
public class DeployRecordEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long artifactId;
    private Long versionId;
    private String deployTarget;
    private String deployUrl;
    private String status;
    private String errorLog;
    private Long deployedBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
```

- [ ] **Step 4: Create 3 Mappers** (same `@Mapper extends BaseMapper` pattern)

- [ ] **Step 5: Compile verify**

---

### Task 21: ArtifactStorageProvider + LocalFileSystemProvider

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\artifact\storage\ArtifactStorageProvider.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\artifact\storage\LocalFileSystemProvider.java`

- [ ] **Step 1: Create ArtifactStorageProvider interface**

```java
package vip.mate.artifact.storage;

import java.io.InputStream;

/** 产物存储抽象 — 设计文档 5.5 */
public interface ArtifactStorageProvider {
    String store(Long artifactId, int versionNumber, String filename, InputStream content);
    InputStream read(String storagePath);
    void delete(String storagePath);
}
```

- [ ] **Step 2: Create LocalFileSystemProvider**

```java
package vip.mate.artifact.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;

@Slf4j
@Component
public class LocalFileSystemProvider implements ArtifactStorageProvider {

    @Value("${mateclaw.workspace.base-dir:./workspace}")
    private String baseDir;

    @Override
    public String store(Long artifactId, int versionNumber, String filename, InputStream content) {
        Path dir = Paths.get(baseDir, "artifacts", artifactId.toString(), String.valueOf(versionNumber));
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(filename);
            Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            return file.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store artifact", e);
        }
    }

    @Override
    public InputStream read(String storagePath) {
        try {
            return Files.newInputStream(Paths.get(storagePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read artifact", e);
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            Files.deleteIfExists(Paths.get(storagePath));
        } catch (IOException e) {
            log.warn("Failed to delete artifact file: {}", storagePath, e);
        }
    }
}
```

---

### Task 22: ArtifactService + ArtifactDeployService

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\artifact\service\ArtifactService.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\artifact\service\ArtifactDeployService.java`

- [ ] **Step 1: Create ArtifactService**

```java
package vip.mate.artifact.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.artifact.model.*;
import vip.mate.artifact.repository.*;
import vip.mate.artifact.storage.ArtifactStorageProvider;
import vip.mate.exception.BusinessException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 产物管理服务 — 产品设计文档 4.4 + API 文档 10.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactService {

    private final ArtifactMapper artifactMapper;
    private final ArtifactVersionMapper versionMapper;
    private final ArtifactStorageProvider storageProvider;

    @Transactional
    public ArtifactEntity createArtifact(Long conversationId, Long messageId,
                                          Long creatorAgentId, Long workspaceId,
                                          String name, String type, InputStream content,
                                          String filename) {
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setConversationId(conversationId);
        artifact.setMessageId(messageId);
        artifact.setCreatorAgentId(creatorAgentId);
        artifact.setWorkspaceId(workspaceId);
        artifact.setArtifactName(name);
        artifact.setArtifactType(type);
        artifact.setCurrentVersion(1);
        artifact.setDeployStatus("none");
        artifactMapper.insert(artifact);

        String filePath = storageProvider.store(artifact.getId(), 1, filename, content);
        String hash = computeHash(filePath);

        ArtifactVersionEntity v = new ArtifactVersionEntity();
        v.setArtifactId(artifact.getId());
        v.setVersionNumber(1);
        v.setMessageId(messageId);
        v.setFilepath(filePath);
        v.setContentHash(hash);
        v.setChangeSummary("Initial version");
        versionMapper.insert(v);

        artifact.setFilePath(filePath);
        artifactMapper.updateById(artifact);
        return artifact;
    }

    // ... getArtifact, listArtifacts, listVersions, getVersion,
    //     createNewVersion (with diff computation), restoreVersion, updateTags
    // Full implementation in code

    private String computeHash(String filePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] content = storageProvider.read(filePath).readAllBytes();
            byte[] hash = md.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Hash computation failed", e);
        }
    }
}
```

- [ ] **Step 2: Create ArtifactDeployService** (stubbed)

```java
package vip.mate.artifact.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.artifact.model.*;
import vip.mate.artifact.repository.*;

import java.time.LocalDateTime;

/**
 * 部署服务 — 当前为 Noop 实现，API 文档 11.
 * DeployProvider 接口为后续接入 Vercel/static hosting 预留
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactDeployService {

    private final DeployRecordMapper deployRecordMapper;
    private final ArtifactMapper artifactMapper;

    public DeployRecordEntity deploy(Long artifactId, Long versionId, String deployTarget, Long userId) {
        DeployRecordEntity record = new DeployRecordEntity();
        record.setArtifactId(artifactId);
        record.setVersionId(versionId);
        record.setDeployTarget(deployTarget != null ? deployTarget : "local");
        record.setStatus("deployed");
        record.setDeployUrl("/api/v1/artifacts/" + artifactId + "/preview");
        record.setDeployedBy(userId);
        record.setCreatedAt(LocalDateTime.now());
        record.setCompletedAt(LocalDateTime.now());
        deployRecordMapper.insert(record);

        artifactMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ArtifactEntity>()
                .eq(ArtifactEntity::getId, artifactId)
                .set(ArtifactEntity::getDeployStatus, "deployed")
                .set(ArtifactEntity::getDeployUrl, record.getDeployUrl()));

        log.info("Artifact {} deployed (noop) → {}", artifactId, record.getDeployUrl());
        return record;
    }

    public DeployRecordEntity getDeployStatus(Long artifactId) {
        return deployRecordMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeployRecordEntity>()
                .eq(DeployRecordEntity::getArtifactId, artifactId)
                .orderByDesc(DeployRecordEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    public java.util.List<DeployRecordEntity> getDeployHistory(Long artifactId, int page, int size) {
        return deployRecordMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeployRecordEntity>()
                .eq(DeployRecordEntity::getArtifactId, artifactId)
                .orderByDesc(DeployRecordEntity::getCreatedAt)
                .last("LIMIT " + size + " OFFSET " + ((page - 1) * size)));
    }
}
```

---

### Task 23: ArtifactController

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\artifact\controller\ArtifactController.java`

- [ ] **Step 1: Create ArtifactController** (all endpoints from API 文档 10–11)

```java
package vip.mate.artifact.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.artifact.model.*;
import vip.mate.artifact.service.*;
import vip.mate.common.result.R;

import java.util.*;

@Tag(name = "产物管理")
@RestController
@RequestMapping("/api/v1/artifacts")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactService artifactService;
    private final ArtifactDeployService deployService;

    @Operation(summary = "获取产物列表")
    @GetMapping
    public R<Map<String, Object>> list(
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String deployStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(artifactService.listArtifacts(conversationId, type, deployStatus, page, size));
    }

    @Operation(summary = "获取产物详情")
    @GetMapping("/{id}")
    public R<ArtifactEntity> detail(@PathVariable Long id) {
        return R.ok(artifactService.getArtifact(id));
    }

    @Operation(summary = "获取版本历史")
    @GetMapping("/{id}/versions")
    public R<List<ArtifactVersionEntity>> versions(@PathVariable Long id) {
        return R.ok(artifactService.listVersions(id));
    }

    @Operation(summary = "获取指定版本")
    @GetMapping("/{id}/versions/{versionId}")
    public R<ArtifactVersionEntity> version(@PathVariable Long id, @PathVariable Long versionId) {
        return R.ok(artifactService.getVersion(id, versionId));
    }

    @Operation(summary = "版本 Diff")
    @GetMapping("/{id}/versions/diff")
    public R<Map<String, Object>> diff(@PathVariable Long id,
                                        @RequestParam int from,
                                        @RequestParam int to) {
        return R.ok(artifactService.computeDiff(id, from, to));
    }

    @Operation(summary = "回滚到指定版本")
    @PostMapping("/{id}/versions/{versionId}/restore")
    public R<ArtifactEntity> restore(@PathVariable Long id, @PathVariable Long versionId) {
        return R.ok(artifactService.restoreVersion(id, versionId));
    }

    @Operation(summary = "更新产物标签")
    @PutMapping("/{id}/tags")
    public R<Void> updateTags(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.get("tags");
        artifactService.updateTags(id, tags);
        return R.ok();
    }

    @Operation(summary = "部署产物")
    @PostMapping("/{id}/deploy")
    public R<DeployRecordEntity> deploy(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body,
                                         Authentication auth) {
        Long versionId = body.containsKey("versionId")
            ? Long.valueOf(body.get("versionId").toString()) : null;
        String target = (String) body.getOrDefault("deployTarget", "local");
        Long userId = Long.valueOf(auth.getName()); // simplified
        return R.ok(deployService.deploy(id, versionId, target, userId));
    }

    @Operation(summary = "查询部署状态")
    @GetMapping("/{id}/deploy/status")
    public R<DeployRecordEntity> deployStatus(@PathVariable Long id) {
        return R.ok(deployService.getDeployStatus(id));
    }

    @Operation(summary = "查询部署历史")
    @GetMapping("/{id}/deploy/history")
    public R<List<DeployRecordEntity>> deployHistory(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return R.ok(deployService.getDeployHistory(id, page, size));
    }
}
```

---

### Task 24: V129–V130 — Create message_pin + message_reaction tables

**Files:**
- Create: 4 migration files (2 h2 + 2 mysql) for V129, V130

Same DDL pattern per 数据库设计文档 4.7–4.8.

---

### Task 25: MessagePinEntity + MessageReactionEntity + Mappers

**Files:**
- Create: 2 entities + 2 mappers in `vip/mate/message/`

- [ ] **Step 1: Create MessagePinEntity**

```java
package vip.mate.message.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_message_pin")
public class MessagePinEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long messageId;
    private Long conversationId;
    private Long pinnedBy;
    private String note;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: Create MessageReactionEntity**

```java
package vip.mate.message.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_message_reaction")
public class MessageReactionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long messageId;
    private Long userId;
    private String reactionType;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: Create Mappers + Compile verify**

---

### Task 26: MessagePinService + MessageReactionService

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\message\service\MessagePinService.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\message\service\MessageReactionService.java`

- [ ] **Step 1: Create MessagePinService** (pin/unpin, list pins)

```java
package vip.mate.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.message.model.MessagePinEntity;
import vip.mate.message.repository.MessagePinMapper;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MessagePinService {

    private final MessagePinMapper pinMapper;

    @Transactional
    public MessagePinEntity pinMessage(Long messageId, Long conversationId, Long userId, String note) {
        // UK on message_id — check existing
        MessagePinEntity existing = pinMapper.selectOne(
            new LambdaQueryWrapper<MessagePinEntity>()
                .eq(MessagePinEntity::getMessageId, messageId));
        if (existing != null) {
            existing.setNote(note);
            pinMapper.updateById(existing);
            return existing;
        }
        MessagePinEntity pin = new MessagePinEntity();
        pin.setMessageId(messageId);
        pin.setConversationId(conversationId);
        pin.setPinnedBy(userId);
        pin.setNote(note);
        pinMapper.insert(pin);
        return pin;
    }

    public void unpinMessage(Long messageId) {
        pinMapper.delete(new LambdaQueryWrapper<MessagePinEntity>()
            .eq(MessagePinEntity::getMessageId, messageId));
    }

    public List<MessagePinEntity> listPins(Long conversationId) {
        return pinMapper.selectList(
            new LambdaQueryWrapper<MessagePinEntity>()
                .eq(MessagePinEntity::getConversationId, conversationId));
    }
}
```

- [ ] **Step 2: Create MessageReactionService** (add/remove reaction, get grouped)

```java
package vip.mate.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.message.model.MessageReactionEntity;
import vip.mate.message.repository.MessageReactionMapper;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageReactionService {

    private final MessageReactionMapper reactionMapper;

    public void addReaction(Long messageId, Long userId, String reactionType) {
        // UK on (message_id, user_id, reaction_type) — use insert ignore pattern
        MessageReactionEntity existing = reactionMapper.selectOne(
            new LambdaQueryWrapper<MessageReactionEntity>()
                .eq(MessageReactionEntity::getMessageId, messageId)
                .eq(MessageReactionEntity::getUserId, userId)
                .eq(MessageReactionEntity::getReactionType, reactionType));
        if (existing == null) {
            MessageReactionEntity r = new MessageReactionEntity();
            r.setMessageId(messageId);
            r.setUserId(userId);
            r.setReactionType(reactionType);
            reactionMapper.insert(r);
        }
    }

    public void removeReaction(Long messageId, Long userId, String reactionType) {
        reactionMapper.delete(new LambdaQueryWrapper<MessageReactionEntity>()
            .eq(MessageReactionEntity::getMessageId, messageId)
            .eq(MessageReactionEntity::getUserId, userId)
            .eq(MessageReactionEntity::getReactionType, reactionType));
    }

    public Map<String, List<MessageReactionEntity>> getReactions(Long messageId) {
        List<MessageReactionEntity> reactions = reactionMapper.selectList(
            new LambdaQueryWrapper<MessageReactionEntity>()
                .eq(MessageReactionEntity::getMessageId, messageId));
        return reactions.stream().collect(Collectors.groupingBy(MessageReactionEntity::getReactionType));
    }
}
```

---

### Task 27: Add reaction/regenerate/reply-chain endpoints to ConversationController

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\workspace\conversation\controller\ConversationController.java`

- [ ] **Step 1: Add message detail, reaction, regenerate, and reply-chain endpoints**

```java
private final vip.mate.message.service.MessageReactionService reactionService;
private final vip.mate.message.service.MessagePinService pinService;

@Operation(summary = "获取消息详情")
@GetMapping("/messages/{id}")
public R<Map<String, Object>> getMessage(@PathVariable Long id, Authentication auth) {
    // ... fetch MessageEntity by id, return with metadata
}

@Operation(summary = "重新生成消息")
@PostMapping("/messages/{id}/regenerate")
public R<Void> regenerate(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
    // ... trigger regeneration flow
}

@Operation(summary = "获取回复链")
@GetMapping("/messages/{id}/reply-chain")
public R<List<Object>> replyChain(@PathVariable Long id) {
    // ... traverse replyToId chain
}

@Operation(summary = "获取消息反馈")
@GetMapping("/messages/{id}/reactions")
public R<Map<String, Object>> getReactions(@PathVariable Long id) {
    return R.ok(Map.of("messageId", id, "reactions", reactionService.getReactions(id)));
}

@Operation(summary = "添加消息反馈")
@PostMapping("/messages/{id}/reactions")
public R<Void> addReaction(@PathVariable Long id, @RequestBody Map<String, String> body,
                            Authentication auth) {
    Long userId = Long.valueOf(auth.getName());
    reactionService.addReaction(id, userId, body.get("reactionType"));
    return R.ok();
}

@Operation(summary = "删除消息反馈")
@DeleteMapping("/messages/{id}/reactions/{reactionType}")
public R<Void> removeReaction(@PathVariable Long id, @PathVariable String reactionType,
                               Authentication auth) {
    Long userId = Long.valueOf(auth.getName());
    reactionService.removeReaction(id, userId, reactionType);
    return R.ok();
}

@Operation(summary = "获取会话的 Pin 消息列表")
@GetMapping("/{conversationId}/pins")
public R<List<MessagePinEntity>> listPins(@PathVariable String conversationId, Authentication auth) {
    String username = auth != null ? auth.getName() : "anonymous";
    if (!conversationService.isConversationOwner(conversationId, username)) {
        return R.fail(403, "无权访问该会话");
    }
    // Find conversation by conversationId to get its Long id
    return R.ok(pinService.listPins(conversationService.getConversationId(conversationId)));
}

@Operation(summary = "Pin 一条消息")
@PostMapping("/{conversationId}/pins")
public R<Void> pinMessage(@PathVariable String conversationId,
                           @RequestBody Map<String, Object> body,
                           Authentication auth) {
    String username = auth != null ? auth.getName() : "anonymous";
    if (!conversationService.isConversationOwner(conversationId, username)) {
        return R.fail(403, "无权操作该会话");
    }
    Long messageId = Long.valueOf(body.get("messageId").toString());
    String note = (String) body.getOrDefault("note", null);
    Long userId = Long.valueOf(auth.getName());
    Long convId = conversationService.getConversationId(conversationId);
    pinService.pinMessage(messageId, convId, userId, note);
    return R.ok();
}

@Operation(summary = "取消 Pin")
@DeleteMapping("/{conversationId}/pins/{messageId}")
public R<Void> unpinMessage(@PathVariable String conversationId,
                             @PathVariable Long messageId,
                             Authentication auth) {
    String username = auth != null ? auth.getName() : "anonymous";
    if (!conversationService.isConversationOwner(conversationId, username)) {
        return R.fail(403, "无权操作该会话");
    }
    pinService.unpinMessage(messageId);
    return R.ok();
}
```

---

### Task 28: Update ChatController — new SSE event types

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\chat\controller\ChatController.java`

- [ ] **Step 1: Add new SSE event emission methods**

The `ChatController` (or its underlying `ChatService`) needs to emit three new SSE event types during group chat streaming:

1. **`orchestrator_plan`** — emitted when the Orchestrator generates a task decomposition plan:
```java
// In SSE emit loop:
if (isGroupConversation && planGenerated) {
    emitter.send(SseEmitter.event()
        .name("orchestrator_plan")
        .data(Map.of("taskId", taskId, "title", title, "steps", steps)));
}
```

2. **`delegation_progress`** — emitted when a sub-agent reports progress:
```java
emitter.send(SseEmitter.event()
    .name("delegation_progress")
    .data(Map.of("agentName", agentName, "status", status, "summary", summary)));
```

3. **`artifact_preview`** — emitted when an artifact is generated:
```java
emitter.send(SseEmitter.event()
    .name("artifact_preview")
    .data(Map.of("artifactId", artifactId, "type", type, "previewUrl", previewUrl)));
```

These are conditional — only emitted for group chat conversations and when the relevant activity occurs. The implementation adds conditional checks in the existing SSE event loop, leveraging the `conversationType` field added in Phase 1.

---

### Task 29: Update DelegateAgentTool — group-context-aware delegation

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\tool\builtin\DelegateAgentTool.java`

- [ ] **Step 1: Add group context awareness**

In `delegateToAgent` and `delegateParallel` methods, check if the parent conversation is a group conversation. If so:
1. Fetch the `GroupConversationEntity` to get failure policy and max parallelism config
2. Create `OrchestratorAssignmentEntity` for tracking
3. Relay progress via SSE events using `OrchestratorEventPublisher`
4. On completion, update assignment status and result summary

```java
// Pseudo-structure of the modification:
private void handleGroupDelegation(String parentConversationId, String agentName,
                                    String task, ChildResult result) {
    // Check if parent is group conversation
    ConversationEntity parentConv = conversationMapper.selectOne(
        new LambdaQueryWrapper<ConversationEntity>()
            .eq(ConversationEntity::getConversationId, parentConversationId));
    if (parentConv == null || !"group".equals(parentConv.getConversationType())) {
        return; // Not a group — no orchestrator tracking needed
    }
    // Fetch group config for failure policy
    // Update assignment status
    // Publish delegation_progress SSE event
}
```

(The existing `delegateToAgent` / `delegateParallel` methods already accept a `ToolContext ctx` parameter — group context is extracted from this.)

---

### Task 30: Update ConversationVO

**Files:**
- Modify: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\main\java\vip\mate\workspace\conversation\vo\ConversationVO.java`

- [ ] **Step 1: Add new display fields**

```java
// Add to ConversationVO:
private String conversationType;
private Integer archived;
private LocalDateTime pinnedAt;
private LocalDateTime lastActiveAt;
private String lastMessagePreview;
private Integer unreadCount;

// Add to MessageVO (or create extended VO):
private String messageType;
private Long replyToId;
private Long senderAgentId;
private String senderAgentName;   // resolved from agent table
private String artifactRefs;
private Long regeneratedFromId;
```

---

### Task 31: Integration Tests

**Files:**
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\test\java\vip\mate\group\GroupConversationServiceTest.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\test\java\vip\mate\orchestrator\OrchestratorServiceTest.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\test\java\vip\mate\artifact\ArtifactServiceTest.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\test\java\vip\mate\message\MessagePinServiceTest.java`
- Create: `D:\code\Loom\mateclaw-dev\mateclaw-server\src\test\java\vip\mate\message\MessageReactionServiceTest.java`

- [ ] **Step 1: Write GroupConversationServiceTest**

```java
package vip.mate.group;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.group.service.GroupConversationService;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GroupConversationServiceTest {

    @Autowired
    private GroupConversationService groupService;

    @Test
    void shouldCreateGroupConversation() {
        var result = groupService.createGroup("testuser", 1L, "Test Group",
            100L, List.of(101L, 102L), "auto", "fail_tolerant", 4);
        assertThat(result).containsKey("conversationId");
        assertThat(result.get("conversationType")).isEqualTo("group");
    }

    @Test
    void shouldRejectGroupWithLessThanTwoAgents() {
        assertThatThrownBy(() -> groupService.createGroup("testuser", 1L, "Test",
            100L, List.of(101L), "auto", "fail_tolerant", 4))
            .hasMessageContaining("至少需要 2 个");
    }

    // ... additional tests: listGroups, addMember, removeMember, updateConfig
}
```

- [ ] **Step 2: Write OrchestratorServiceTest**

```java
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrchestratorServiceTest {

    @Autowired
    private OrchestratorService orchestratorService;

    @Test
    void shouldCreateTaskAndAssignments() {
        var steps = List.of(
            Map.of("agentId", 101L, "goal", "Design layout", "mode", "sequential"),
            Map.of("agentId", 102L, "goal", "Add interactivity", "mode", "sequential", "dependsOn", 1)
        );
        var task = orchestratorService.createTask(1L, 1L, "Build page",
            "{\"steps\":[...]}", steps);
        assertThat(task.getId()).isNotNull();
        assertThat(task.getTotalAssignments()).isEqualTo(2);

        var assignments = orchestratorService.getAssignments(task.getId());
        assertThat(assignments).hasSize(2);
    }
}
```

- [ ] **Step 3: Write ArtifactServiceTest** (create, version, diff, rollback)

- [ ] **Step 4: Write MessagePinServiceTest + MessageReactionServiceTest**

- [ ] **Step 5: Run tests**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw test -pl mateclaw-server`
Expected: All new tests pass

---

### Task 32: Update ArchUnit Rules

**Files:**
- Modify: Existing ArchUnit test class in `src/test/java/`

- [ ] **Step 1: Add new packages to layered architecture rules**

Add `vip.mate.group..`, `vip.mate.orchestrator..`, `vip.mate.artifact..`, `vip.mate.message..` to the existing ArchUnit `layeredArchitecture()` rule, ensuring they follow the same controller → service → mapper dependency direction.

---

### Task 33: Full Compile & Startup Verification

- [ ] **Step 1: Full project compile**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw clean compile -pl mateclaw-server`
Expected: BUILD SUCCESS

- [ ] **Step 2: Start application with H2**

Run: `cd D:/code/Loom/mateclaw-dev && ./mvnw spring-boot:run -pl mateclaw-server -Dspring-boot.run.profiles=dev`
Expected: Application starts without Flyway errors, all V120–V131 migrations applied

- [ ] **Step 3: Verify Swagger UI**

Open: `http://localhost:18088/swagger-ui.html`
Expected: New endpoints visible under "群聊会话管理", "Orchestrator 调度", "产物管理" tags

- [ ] **Step 4: Smoke test key endpoints with curl**

```bash
# Test group creation
curl -X POST http://localhost:18088/api/v1/conversations/group \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -H "X-Workspace-Id: 1" \
  -d '{"title":"Test Group","orchestratorAgentId":3,"agentIds":[5,7],"schedulingMode":"auto"}'

# Test artifact list
curl http://localhost:18088/api/v1/artifacts \
  -H "Authorization: Bearer <token>"

# Test orchestrator task list
curl http://localhost:18088/api/v1/orchestrator/tasks \
  -H "Authorization: Bearer <token>"
```

---

### Task 34: Summary — Files Changed

**Phase 1 (Extend existing): 14 files**
- 6 migration files (V120–V122, h2 + mysql)
- 3 modified entities (Agent, Conversation, Message)
- 2 modified services (AgentService, ConversationService)
- 2 modified controllers (AgentController, ConversationController)
- 1 modified VO (ConversationVO)

**Phase 2 (New modules): ~65 files**
- 18 migration files (V123–V131, h2 + mysql)
- 9 entities + 9 mappers
- 6 services (GroupConversation, Orchestrator, Artifact, ArtifactDeploy, MessagePin, MessageReaction)
- 4 controllers (GroupConversation, Orchestrator, Artifact, + extended ConversationController)
- 1 SSE event publisher
- 1 storage interface + 1 local FS implementation
- 5 integration test classes
- 1 ArchUnit rule update
- 2 modified existing files (ChatController, DelegateAgentTool)

**Total: ~85 files, ~7,300 LoC**
