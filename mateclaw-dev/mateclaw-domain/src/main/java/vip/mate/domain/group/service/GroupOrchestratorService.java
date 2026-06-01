package vip.mate.domain.group.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import vip.mate.domain.agent.model.AgentEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
    public Flux<String> callOrchestrator(String userId, String prompt, List<Map<String, String>> history) {
        log.info("Calling LLM orchestrator agentId={} userId={} historySize={}",
                ORCHESTRATOR_AGENT_ID, userId, history != null ? history.size() : 0);

        try {
            List<Map<String, String>> allMessages = new ArrayList<>();
            allMessages.add(Map.of("role", "system", "content", systemPrompt));
            if (history != null) {
                allMessages.addAll(history);
            }
            allMessages.add(Map.of("role", "user", "content", prompt));

            var body = Map.of(
                    "model", model,
                    "stream", true,
                    "thinking", Map.of("type", "enabled"),
                    "messages", allMessages
            );

            AtomicInteger rawChunks = new AtomicInteger(0);
            AtomicInteger textChunks = new AtomicInteger(0);

            return webClient.post()
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), response ->
                            response.bodyToMono(String.class)
                                    .defaultIfEmpty("(empty body)")
                                    .flatMap(bodyText -> {
                                        log.error("Orchestrator API error: status={}, body={}",
                                                response.statusCode(), bodyText);
                                        return Mono.error(
                                                new RuntimeException("Orchestrator API " + response.statusCode() + ": " + bodyText));
                                    }))
                    .bodyToFlux(String.class)
                    .doOnNext(raw -> {
                        int count = rawChunks.incrementAndGet();
                        if (count <= 2 || count % 100 == 0) {
                            log.debug("Orchestrator SSE raw chunk #{}: {}", count,
                                    raw.length() > 150 ? raw.substring(0, 150) + "..." : raw);
                        }
                    })
                    .concatMap(raw -> Flux.fromStream(
                            Stream.of(raw.split("\n\n"))
                                    .filter(block -> !block.isBlank())
                    ))
                    .map(GroupOrchestratorService::stripSsePrefix)
                    .filter(line -> {
                        boolean match = line.startsWith("{") && !line.contains("[DONE]");
                        if (!match && !line.isBlank() && !line.contains("[DONE]")) {
                            log.debug("Orchestrator SSE non-JSON line (len={}): {}", line.length(),
                                    line.length() > 120 ? line.substring(0, 120) + "..." : line);
                        }
                        return match;
                    })
                    .map(line -> {
                        try {
                            var node = objectMapper.readTree(line);
                            var choices = node.path("choices");
                            if (choices.isArray() && choices.size() > 0) {
                                var delta = choices.get(0).path("delta");
                                var content = delta.path("content");
                                if (content.isMissingNode() || content.isNull()) return "";
                                return content.asText();
                            }
                            return "";
                        } catch (Exception e) {
                            log.debug("Orchestrator SSE parse failed: {}", e.getMessage());
                            return "";
                        }
                    })
                    .filter(text -> !text.isEmpty())
                    .doOnNext(text -> textChunks.incrementAndGet())
                    .doOnComplete(() -> log.info("Orchestrator stream completed: rawChunks={}, textChunks={}",
                            rawChunks.get(), textChunks.get()))
                    .doOnError(err -> log.error("Orchestrator LLM call failed: {}", err.getMessage()));
        } catch (Exception e) {
            log.error("Orchestrator call failed", e);
            return Flux.error(e);
        }
    }

    /**
     * Strip the SSE {@code data:} or {@code data: } prefix from a line,
     * also trimming any trailing {@code \r}.
     */
    static String stripSsePrefix(String line) {
        String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        if (trimmed.startsWith("data: ")) {
            return trimmed.substring(6);
        }
        if (trimmed.startsWith("data:")) {
            return trimmed.substring(5);
        }
        return trimmed;
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

    /**
     * Build the prompt for Agent01 to aggregate sub-agent execution results
     * and generate a summary report for the user.
     */
    public String buildAggregationPrompt(
            String originalTask,
            List<Map<String, Object>> agentResults) {

        var sb = new StringBuilder();
        sb.append("你是一个任务协调者。以下是本轮任务分配的执行结果汇总，请撰写一份简明的总结报告。\n\n");
        sb.append("## 原始任务\n").append(originalTask).append("\n\n");
        sb.append("## 子任务执行结果\n\n");

        for (var result : agentResults) {
            String agentName = (String) result.get("agentName");
            String status = (String) result.get("status");
            String task = (String) result.get("task");
            String output = (String) result.getOrDefault("output", "");
            String error = (String) result.getOrDefault("error", "");
            String waitingReason = (String) result.getOrDefault("waitingReason", "");

            switch (status) {
                case "completed" -> {
                    sb.append("### ✅ ").append(agentName).append(" — 已完成\n");
                    sb.append("**任务：** ").append(task).append("\n");
                    sb.append("**输出：**\n");
                    if (output.length() > 4000) {
                        sb.append(output, 0, 2000)
                          .append("\n\n...（省略 ").append(output.length() - 4000)
                          .append(" 字符）...\n\n")
                          .append(output, output.length() - 2000, output.length());
                    } else {
                        sb.append(output);
                    }
                    sb.append("\n\n");
                }
                case "failed" -> {
                    sb.append("### ❌ ").append(agentName).append(" — 执行失败\n");
                    sb.append("**任务：** ").append(task).append("\n");
                    sb.append("**错误：** ").append(error != null ? error : "未知错误").append("\n\n");
                }
                case "waiting" -> {
                    sb.append("### ⏸ ").append(agentName).append(" — 等待中（上游失败）\n");
                    sb.append("**任务：** ").append(task).append("\n");
                    sb.append("**原因：** ").append(waitingReason != null ? waitingReason : "依赖的上游任务未完成").append("\n\n");
                }
            }
        }

        sb.append("---\n\n");
        sb.append("请总结：\n");
        sb.append("1. 本轮整体完成情况（成功数/失败数/等待数）\n");
        sb.append("2. 已完成任务的关键成果\n");
        sb.append("3. 失败任务的原因和建议下一步（如：可重新指派、可跳过等）\n");
        sb.append("4. 等待中的任务说明（因依赖未满足）");

        return sb.toString();
    }
}
