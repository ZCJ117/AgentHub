package vip.mate.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.MateClawApplication;
import vip.mate.group.service.GroupConversationService;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link GroupConversationService} — group conversation CRUD.
 * <p>
 * Each test runs against an isolated H2 in-memory database with full Flyway
 * migration applied. Tests that require pre-existing DB rows (e.g. an
 * orchestrator-type agent) seed the necessary fixture data directly.
 */
@SpringBootTest(
    classes = MateClawApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:group_svc_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.dashscope.api-key=test-key",
    "spring.main.web-application-type=none"
})
@Transactional
class GroupConversationServiceTest {

    @Autowired
    private GroupConversationService groupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("should create group conversation with valid inputs")
    void shouldCreateGroupConversation() {
        seedAgent(101L);
        seedAgent(102L);

        var result = groupService.createGroup("testuser", 1L, "Test Group",
            java.util.List.of(101L, 102L), "auto", "fail_tolerant", 4);
        assertThat(result).containsKey("conversationId");
        assertThat(result.get("conversationType")).isEqualTo("group");
    }

    @Test
    @DisplayName("should reject group with less than two agents")
    void shouldRejectGroupWithLessThanTwoAgents() {
        assertThatThrownBy(() -> groupService.createGroup("testuser", 1L, "Test",
            java.util.List.of(101L), "auto", "fail_tolerant", 4))
            .hasMessageContaining("至少需要 2 个");
    }

    @Test
    @DisplayName("should list groups for user (empty result when none created)")
    void shouldListGroupsForUser() {
        var page = groupService.listGroups("testuser", 1, 20);
        assertThat(page).isNotNull();
    }

    // ── helpers ──

    private void seedAgent(long agentId) {
        jdbcTemplate.update(
            "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                "workspace_id, create_time, update_time, deleted) " +
                "KEY(id) VALUES (?, ?, 'react', '', 10, TRUE, 1, " +
                "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
            agentId, "agent-" + agentId);
    }

}
