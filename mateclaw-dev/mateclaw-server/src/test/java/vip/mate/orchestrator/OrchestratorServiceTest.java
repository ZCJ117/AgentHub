package vip.mate.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.MateClawApplication;
import vip.mate.orchestrator.service.OrchestratorService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link OrchestratorService} — task creation, retrieval,
 * and lifecycle management.
 * <p>
 * Operations are validated against an H2 in-memory database with the full
 * Flyway schema applied.
 */
@SpringBootTest(
    classes = MateClawApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:orch_svc_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.ai.dashscope.api-key=test-key",
    "spring.main.web-application-type=none"
})
@Transactional
class OrchestratorServiceTest {

    @Autowired
    private OrchestratorService orchestratorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("should create task with assignments and populate step order")
    void shouldCreateTaskWithAssignments() {
        seedAgent(101L);
        seedAgent(102L);

        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> s1 = new LinkedHashMap<>(); s1.put("agentId", 101L); s1.put("goal", "Design layout"); s1.put("mode", "sequential");
        Map<String, Object> s2 = new LinkedHashMap<>(); s2.put("agentId", 102L); s2.put("goal", "Add interactivity"); s2.put("mode", "parallel");
        steps.add(s1); steps.add(s2);
        var task = orchestratorService.createTask(1L, 1L, "Build page",
            "{\"steps\":[{\"order\":1,\"goal\":\"Design layout\"}]}", steps);
        assertThat(task.getId()).isNotNull();
        assertThat(task.getTotalAssignments()).isEqualTo(2);

        var assignments = orchestratorService.getAssignments(task.getId());
        assertThat(assignments).hasSize(2);
        assertThat(assignments.get(0).getStepOrder()).isEqualTo(1);
        assertThat(assignments.get(1).getStepOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("should cancel task and transition status to cancelled")
    void shouldCancelTask() {
        seedAgent(101L);

        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> s = new LinkedHashMap<>(); s.put("agentId", 101L); s.put("goal", "Test"); s.put("mode", "sequential");
        steps.add(s);
        var task = orchestratorService.createTask(1L, 1L, "Test task", "{}", steps);
        orchestratorService.cancelTask(task.getId());
        var updated = orchestratorService.getTaskDetail(task.getId());
        assertThat(updated.getStatus()).isEqualTo("cancelled");
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
