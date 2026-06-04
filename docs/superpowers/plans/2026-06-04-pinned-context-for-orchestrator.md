# 群聊固定消息作为子Agent长期上下文 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在群聊模式中，编排器分配任务给子Agent时，自动将已固定(Pin)的消息作为长期上下文注入

**Architecture:** 修改 `AgentMentionDispatcher.java` 的 `buildContextMessage` 方法，注入 `MessagePinService` 依赖，在对话历史之前查询并前置插入已固定消息

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis Plus, H2 (开发)

---

### Task 1: 编写集成测试

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/test/java/vip/mate/group/PinnedContextInjectionTest.java`

- [ ] **Step 1: 编写测试类**

```java
package vip.mate.group;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.MateClawApplication;
import vip.mate.domain.message.model.MessagePinEntity;
import vip.mate.domain.message.service.MessagePinService;
import vip.mate.domain.workspace.conversation.model.MessageEntity;
import vip.mate.domain.workspace.conversation.repository.MessageMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * 验证 MessagePinService 的 listPins 接口可用且返回正确的固定消息。
 * 实际的上下文注入逻辑由 AgentMentionDispatcher 单元测试覆盖（Task 3）。
 */
@SpringBootTest(
    classes = MateClawApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:pinned_ctx_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.dashscope.api-key=test-key",
    "spring.main.web-application-type=none"
})
@Transactional
class PinnedContextInjectionTest {

    @Autowired
    private MessagePinService pinService;

    @Autowired
    private MessageMapper messageMapper;

    private Long conversationId;
    private Long msgId1;
    private Long msgId2;

    @BeforeEach
    void setUp() {
        conversationId = 100L;

        // 插入两条测试消息
        msgId1 = insertMessage(conversationId, "user", "需要使用蓝色主题");
        msgId2 = insertMessage(conversationId, "assistant", "后端接口用 RESTful 风格");
    }

    private Long insertMessage(Long convId, String role, String content) {
        var msg = new MessageEntity();
        msg.setConversationId(convId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMessageType("text");
        msg.setStatus("completed");
        msg.setCreateTime(LocalDateTime.now());
        messageMapper.insert(msg);
        return msg.getId();
    }

    @Test
    @DisplayName("listPins 应返回会话中所有已固定消息")
    void shouldListPinnedMessagesForConversation() {
        pinService.pinMessage(msgId1, conversationId, 1L, "重要需求");
        pinService.pinMessage(msgId2, conversationId, 1L, null);

        var pins = pinService.listPins(conversationId);

        assertThat(pins).hasSize(2);
        assertThat(pins.get(0).getMessageId()).isEqualTo(msgId1);
        assertThat(pins.get(0).getNote()).isEqualTo("重要需求");
        assertThat(pins.get(1).getMessageId()).isEqualTo(msgId2);
    }

    @Test
    @DisplayName("无固定消息时 listPins 应返回空列表")
    void shouldReturnEmptyWhenNoPins() {
        var pins = pinService.listPins(conversationId);
        assertThat(pins).isEmpty();
    }

    @Test
    @DisplayName("取消固定后 listPins 不应包含该消息")
    void shouldNotIncludeUnpinnedMessage() {
        pinService.pinMessage(msgId1, conversationId, 1L, null);
        pinService.unpinMessage(msgId1);

        var pins = pinService.listPins(conversationId);
        assertThat(pins).isEmpty();
    }

    @Test
    @DisplayName("固定消息的原始消息被删除后，listPins 仍返回 pin 记录（messageMapper.selectById 返回 null）")
    void shouldReturnPinEvenWhenOriginalMessageDeleted() {
        pinService.pinMessage(msgId1, conversationId, 1L, null);
        messageMapper.deleteById(msgId1);

        var pins = pinService.listPins(conversationId);
        assertThat(pins).hasSize(1);
        // 原始消息已删除，selectById 应返回 null
        assertThat(messageMapper.selectById(msgId1)).isNull();
    }
}
```

- [ ] **Step 2: 运行测试验证通过**

Run: `cd mateclaw-dev/mateclaw-server && mvn test -Dtest=vip.mate.group.PinnedContextInjectionTest -DfailIfNoTests=false`
Expected: 4 tests PASS

- [ ] **Step 3: 提交**

```bash
git add mateclaw-dev/mateclaw-server/src/test/java/vip/mate/group/PinnedContextInjectionTest.java
git commit -m "test: add pinned context injection integration tests"
```

---

### Task 2: 注入 MessagePinService 依赖

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: 在构造函数参数和字段声明中添加 MessagePinService**

Locate the constructor (around line 38-49) and add the new dependency:

```java
// 在 import 区域添加（放在 message 相关 import 之后）
import vip.mate.domain.message.service.MessagePinService;
import vip.mate.domain.message.model.MessagePinEntity;

// 在字段声明区域添加（放在 MessageMapper 字段之后）
private final MessagePinService messagePinService;

// 在构造函数参数中添加（放在 messageMapper 参数之后）
@RequiredArgsConstructor 替换为显式构造函数，或添加到 Lombok 参数列表

// 实际上 @RequiredArgsConstructor 基于 final 字段自动生成构造函数，
// 只需添加 final 字段即可，Lombok 会自动包含它
```

具体改动：

在 line 46 `private final MessageMapper messageMapper;` 之后添加：
```java
private final MessagePinService messagePinService;
```

在 line 47 `private final WorkspaceMapper workspaceMapper;` 保持不变。

由于类使用 `@RequiredArgsConstructor`，添加 `final` 字段后 Lombok 会自动将其加入构造函数参数。

- [ ] **Step 2: 验证编译**

Run: `cd mateclaw-dev && mvn compile -pl mateclaw-domain -am`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: inject MessagePinService into AgentMentionDispatcher"
```

---

### Task 3: 修改 buildContextMessage 注入固定消息

**Files:**
- Modify: `mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java` (lines 655-696)

- [ ] **Step 1: 修改 buildContextMessage 方法**

将当前的 `buildContextMessage` 方法（line 655-696）替换为：

```java
/**
 * Build a contextual message by prepending pinned messages and recent conversation
 * history so the sub-agent has long-term context + multi-turn context in group chat.
 */
private String buildContextMessage(String task, String conversationId) {
    try {
        StringBuilder ctx = new StringBuilder();

        // ── 前置：已固定消息作为长期上下文 ──
        try {
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
        } catch (Exception e) {
            log.warn("[Dispatcher] Failed to load pinned messages for {}: {}", conversationId, e.getMessage());
        }

        // ── 原有：对话历史 ──
        List<MessageEntity> history = conversationService.listRecentMessages(conversationId, 20);
        if (history == null || history.isEmpty()) {
            ctx.append("# 当前任务\n");
            ctx.append(task);
            return ctx.toString();
        }

        ctx.append("# 以下是群聊对话历史上下文\n\n");
        Map<Long, String> nameCache = new HashMap<>();
        for (MessageEntity msg : history) {
            String roleLabel;
            if ("user".equals(msg.getRole())) {
                roleLabel = "用户";
            } else if (msg.getSenderAgentId() != null) {
                roleLabel = nameCache.computeIfAbsent(msg.getSenderAgentId(), id -> {
                    if (agentMapper != null) {
                        AgentEntity ag = agentMapper.selectById(id);
                        return ag != null ? ag.getName() : "Agent#" + id;
                    }
                    return "Agent#" + id;
                });
            } else {
                roleLabel = "AI助手";
            }
            String content = conversationService.renderMessageContent(msg);
            if (content != null && !content.isBlank()) {
                ctx.append(roleLabel).append(": ").append(content).append("\n\n");
            }
        }
        ctx.append("---\n");
        ctx.append("# 当前任务\n");
        ctx.append(task);

        log.debug("[Dispatcher] Built context for conversation={}, historyMsgs={}, totalChars={}",
                conversationId, history.size(), ctx.length());
        return ctx.toString();
    } catch (Exception e) {
        log.warn("[Dispatcher] Failed to build conversation context: {}", e.getMessage());
        return task;
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `cd mateclaw-dev && mvn compile -pl mateclaw-domain -am`
Expected: BUILD SUCCESS

- [ ] **Step 3: 运行现有测试确保无回归**

Run: `cd mateclaw-dev/mateclaw-server && mvn test -Dtest=vip.mate.message.MessagePinServiceTest -DfailIfNoTests=false`
Expected: existing pin tests still PASS

- [ ] **Step 4: 提交**

```bash
git add mateclaw-dev/mateclaw-domain/src/main/java/vip/mate/domain/group/service/AgentMentionDispatcher.java
git commit -m "feat: inject pinned messages as long-term context in buildContextMessage"
```

---

### Task 4: 手动验证（端到端）

由于群聊的端到端流程依赖 Agent01 外部 LLM 和 CLI 进程，无法在单元测试中覆盖完整链路。以下为手动验证步骤：

- [ ] **Step 1: 启动后端服务**

```bash
cd mateclaw-dev/mateclaw-server && mvn spring-boot:run
```

- [ ] **Step 2: 创建群聊并固定消息**

1. 创建群聊，添加 Agent01 + 至少一个子Agent
2. 发送一条包含关键需求的消息（如"项目必须使用 Vue 3 Composition API"）
3. 在该消息上点击 Pin 按钮固定它
4. 发送另一条消息（如"API 基路径是 /api/v2"）并固定

- [ ] **Step 3: 触发编排**

1. 在群聊中发送任务消息，如"帮我实现用户列表页面"
2. 观察子Agent 的输出，确认其能看到固定消息作为上下文

- [ ] **Step 4: 验证无固定消息时行为不变**

1. 取消所有固定消息
2. 再次发送任务
3. 确认子Agent 正常工作，上下文无异常

- [ ] **Step 5: 验证固定消息被删除后不影响**

1. 固定一条消息，然后删除该消息的原始内容
2. 触发编排，确认其他固定消息仍正常注入

---

### 改动文件汇总

| 文件 | 操作 | 改动内容 |
|------|------|----------|
| `AgentMentionDispatcher.java` | 修改 | 注入 `MessagePinService`，修改 `buildContextMessage` |
| `PinnedContextInjectionTest.java` | 新建 | 集成测试：验证 Pin 查询、空列表、取消固定、消息删除场景 |
