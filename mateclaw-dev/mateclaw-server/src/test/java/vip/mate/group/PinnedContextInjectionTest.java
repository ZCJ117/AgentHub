package vip.mate.group;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.MateClawApplication;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long conversationId;
    private Long msgId1;
    private Long msgId2;

    @BeforeEach
    void setUp() {
        conversationId = 100L;

        // 插入测试会话（满足 mate_message_pin.conversation_id → mate_conversation.id 外键约束）
        jdbcTemplate.update(
            "INSERT INTO mate_conversation (id, conversation_id, create_time, update_time) VALUES (?, ?, ?, ?)",
            conversationId, "test-conv-" + conversationId, LocalDateTime.now(), LocalDateTime.now());

        // 插入两条测试消息
        msgId1 = insertMessage(conversationId, "user", "需要使用蓝色主题");
        msgId2 = insertMessage(conversationId, "assistant", "后端接口用 RESTful 风格");
    }

    private Long insertMessage(Long convId, String role, String content) {
        var msg = new MessageEntity();
        msg.setConversationId(String.valueOf(convId));
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
    @DisplayName("原始消息被删除后，pin 记录被级联删除，listPins 返回空列表")
    void shouldCascadeDeletePinWhenOriginalMessageDeleted() {
        pinService.pinMessage(msgId1, conversationId, 1L, null);

        // 原始消息删除 → 外键 ON DELETE CASCADE 触发，pin 记录一并删除
        messageMapper.deleteById(msgId1);

        var pins = pinService.listPins(conversationId);
        assertThat(pins).isEmpty();
        assertThat(messageMapper.selectById(msgId1)).isNull();
    }
}
