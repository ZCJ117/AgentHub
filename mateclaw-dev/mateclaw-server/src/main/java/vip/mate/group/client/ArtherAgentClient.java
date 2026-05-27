package vip.mate.group.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import vip.mate.agent.model.AgentEntity;

import java.util.List;
import java.util.Map;

/**
 * HTTP client that calls the arther-agent engine's REST API
 * to invoke Agent01 (the built-in Orchestrator) for group chat task assignment.
 */
@Slf4j
@Component
public class ArtherAgentClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String ORCHESTRATOR_AGENT_ID = "000001";

    public ArtherAgentClient(@Value("${arther.agent.base-url:http://127.0.0.1:8091}") String baseUrl) {
        this.webClient = WebClient.create(baseUrl);
        log.info("ArtherAgentClient initialized with base-url={}", baseUrl);
    }

    /**
     * Call Agent01 Orchestrator SSE stream.
     * Returns a Flux of raw SSE text lines from the arther-agent.
     */
    public Flux<String> callOrchestrator(String userId, String prompt) {
        var body = Map.of(
                "agentId", ORCHESTRATOR_AGENT_ID,
                "userId", userId,
                "message", prompt
        );
        log.info("Calling arther-agent orchestrator agentId={} userId={}", ORCHESTRATOR_AGENT_ID, userId);

        return webClient.post()
                .uri("/api/v1/chat_stream")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnError(err -> log.error("arther-agent orchestrator call failed: {}", err.getMessage()));
    }

    /**
     * Build the prompt sent to Agent01, containing the available agent list
     * and the user's task message. Agent01's instruction (in agents.yml) tells
     * it to output @AgentName task assignments.
     */
    public String buildOrchestratorPrompt(List<AgentEntity> memberAgents, String taskMessage) {
        var sb = new StringBuilder();
        sb.append("可用智能体：\n");
        for (AgentEntity ag : memberAgents) {
            sb.append(ag.getName());
            if (ag.getDescription() != null && !ag.getDescription().isBlank()) {
                sb.append("（").append(ag.getDescription()).append("）");
            }
            sb.append("\n");
        }
        sb.append("\n任务请求：").append(taskMessage);
        return sb.toString();
    }

    /**
     * Parse a raw SSE line from arther-agent and extract the text content.
     * arther-agent emits: data: {"type":"textDelta","text":"..."}
     * Returns null if the line is not a textDelta event.
     */
    public String extractTextFromSseLine(String sseLine) {
        if (sseLine == null || !sseLine.startsWith("data: ")) return null;
        String json = sseLine.substring(6).trim();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!"textDelta".equals(node.path("type").asText())) return null;
            JsonNode textNode = node.get("text");
            return textNode != null ? textNode.asText() : null;
        } catch (Exception e) {
            // Non-JSON SSE lines (comments, empty lines) are silently skipped
            return null;
        }
    }
}
