package vip.mate.group.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Flux;
import vip.mate.agent.model.AgentEntity;

import java.util.List;
import java.util.Map;

/**
 * In-process orchestrator service that directly calls the LLM API
 * using Agent01's configuration from agents.yml, without needing
 * a separate arther-agent process on port 8091.
 */
@Slf4j
@Service("groupOrchestratorService")
public class GroupOrchestratorService {

    private final WebClient webClient;
    private final String model;
    private final String systemPrompt;

    private static final String ORCHESTRATOR_AGENT_ID = "000001";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public GroupOrchestratorService() {
        Map<String, Object> config = loadAgentConfig();
        Map<String, Object> module = (Map<String, Object>) config.get("module");
        Map<String, Object> aiApi = (Map<String, Object>) module.get("ai-api");
        Map<String, Object> chatModel = (Map<String, Object>) module.get("chat-model");

        String baseUrl = (String) aiApi.get("base-url");
        String completionsPath = (String) aiApi.get("completions-path");
        String apiKey = (String) aiApi.get("api-key");
        this.model = (String) chatModel.get("model");

        // Agent01's instruction is the system prompt (module.agents[0].instruction)
        var agentList = (List<Map<String, String>>) module.get("agents");
        this.systemPrompt = agentList != null && !agentList.isEmpty()
                ? agentList.get(0).get("instruction")
                : "";

        String fullUrl = baseUrl;
        if (completionsPath != null && !completionsPath.isBlank()) {
            fullUrl = baseUrl.replaceAll("/+$", "") + "/" + completionsPath.replaceAll("^/+", "");
        }

        this.webClient = WebClient.builder()
                .baseUrl(fullUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();

        log.info("GroupOrchestratorService initialized: model={}, url={}", model, fullUrl);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadAgentConfig() {
        try {
            Yaml yaml = new Yaml();
            var resource = new ClassPathResource("agent/agents.yml");
            Map<String, Object> root = yaml.load(resource.getInputStream());
            var ai = (Map<String, Object>) root.get("ai");
            var agent = (Map<String, Object>) ai.get("agent");
            var config = (Map<String, Object>) agent.get("config");
            var tables = (Map<String, Object>) config.get("tables");
            return (Map<String, Object>) tables.get("Agent01");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load agents.yml for Orchestrator", e);
        }
    }

    /**
     * Call Agent01 Orchestrator via direct LLM API.
     * Returns a Flux of text lines (streamed as SSE from the LLM).
     */
    public Flux<String> callOrchestrator(String userId, String prompt) {
        log.info("Calling LLM orchestrator agentId={} userId={}", ORCHESTRATOR_AGENT_ID, userId);

        try {
            var body = Map.of(
                    "model", model,
                    "stream", true,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", prompt)
                    )
            );

            return webClient.post()
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .filter(line -> line.startsWith("data: ") && !line.contains("[DONE]"))
                    .map(line -> {
                        try {
                            var node = objectMapper.readTree(line.substring(6));
                            var choices = node.path("choices");
                            if (choices.isArray() && choices.size() > 0) {
                                var delta = choices.get(0).path("delta");
                                var content = delta.path("content");
                                return content.isMissingNode() ? "" : content.asText();
                            }
                            return "";
                        } catch (Exception e) {
                            return "";
                        }
                    })
                    .filter(text -> !text.isEmpty())
                    .doOnError(err -> log.error("Orchestrator LLM call failed: {}", err.getMessage()));
        } catch (Exception e) {
            log.error("Orchestrator call failed", e);
            return Flux.error(e);
        }
    }

    /**
     * Build the prompt sent to Agent01, containing the available agent list
     * and the user's task message.
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
}
