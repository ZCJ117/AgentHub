# Group Chat @AgentName Multi-Agent Dispatch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reimplement group chat so the Orchestrator dispatches tasks via `@Agent名: 任务` text lines, and member agents are spawned on-demand with per-agent CLAUDE.md files.

**Architecture:** Group creation generates CLAUDE.md files for orchestrator + members stored in temp dir. When user sends a message, only the orchestrator CLI starts. A new `AgentMentionDispatcher` intercepts the orchestrator's streaming output, detects `@Agent名: 任务` lines via regex, spawns the named agent's CLI with its CLAUDE.md, and streams responses back as separate SSE messages with sender attribution.

**Tech Stack:** Java 21 / Spring Boot 3 / Reactor Flux / Vue 3 + Pinia / Claude Code CLI / Node.js adapter

---

### File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `GroupConversationService.java` | Modify | Add `generateClaudeMd()` methods; build agentNameMap |
| **`AgentMentionDispatcher.java`** | **Create** | Parse @AgentName lines, spawn agents, multiplex SSE |
| `ChatController.java` | Modify | Wire dispatcher into group chat Flux; broadcast new SSE events |
| `LocalCliProcessManager.java` | Modify | Accept `CLAUDE_MD_PATH` env var in spawn() |
| `claude-adapter.mjs` | Modify | Forward `CLAUDE_MD_PATH` env var to `claude` CLI |
| `ChatStreamTracker.java` | Modify | Add `agent_message_start` / `agent_message_complete` broadcast helpers |
| `stores/chat.js` | Modify | Handle multi-agent SSE events; route content_delta by agentName |

---

### Task 1: GroupConversationService — Generate CLAUDE.md Files at Group Creation

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/service/GroupConversationService.java`

- [ ] **Step 1: Add CLAUDE.md generation methods and temp directory constant**

Add to `GroupConversationService.java` after line 26 (the `agentMapper` field):

```java
private static final String CLAUDE_MD_BASE_DIR =
        System.getProperty("java.io.tmpdir") + "/agenthub-claude-md";

/**
 * Generate the Orchestrator's CLAUDE.md content with the agent list.
 */
private String buildOrchestratorClaudeMd(List<Long> memberAgentIds) {
    StringBuilder sb = new StringBuilder();
    sb.append("你是一个 Orchestrator 调度者。你的任务是根据用户的输入，为我提供的多个 Agent 分派任务。\n");
    sb.append("你必须使用 \"@Agent名称\" 的形式来指派任务。\n");
    sb.append("用户的消息可能直接是对全体说的，也可能是对特定 Agent 说的。\n");
    sb.append("你需要解析用户意图，生成清晰的任务指令，并通过 \"@Agent名称: 具体任务描述\" 的方式，将子任务分发给对应的 Agent。\n");
    sb.append("你不需要自己完成具体工作，你只负责分解、分配和协调。\n");
    sb.append("如果用户没有明确指定 Agent，你要根据 Agent 的能力自行判断并分配。\n");
    sb.append("在输出中，你对每个 Agent 的指派必须独占一行，格式严格为：@Agent名: 任务内容\n\n");
    sb.append("可用 Agent 列表：\n\n");

    for (Long agentId : memberAgentIds) {
        AgentEntity ag = agentMapper.selectById(agentId);
        if (ag == null) continue;
        sb.append("· Agent名称: ").append(ag.getName()).append("\n");
        sb.append("  能力: ").append(ag.getDescription() != null ? ag.getDescription() : "通用助手").append("\n");
    }
    return sb.toString();
}

/**
 * Generate a member agent's CLAUDE.md content.
 */
private String buildMemberClaudeMd(AgentEntity agent) {
    return "你是 " + agent.getName() + "，你的角色是 " +
            (agent.getDescription() != null ? agent.getDescription() : "通用助手") + "。\n" +
            "你正在一个多 Agent 群聊中与其他 Agent 协作。\n" +
            "对话中，当看到以 \"@" + agent.getName() + "\" 开头的消息时，那是指派给你的任务。\n" +
            "你应当只回复与任务相关的内容，用第一人称的专家口吻。\n" +
            "在回复时，先简要确认你收到了任务，然后给出你的专业意见或产出。\n" +
            "不要模拟其他 Agent 的回复。\n";
}

/**
 * Write CLAUDE.md content to a temp file. Returns the absolute path.
 */
private String writeClaudeMdFile(Long conversationDbId, String agentName, String content) {
    try {
        File dir = new File(CLAUDE_MD_BASE_DIR + "/" + conversationDbId);
        dir.mkdirs();
        File file = new File(dir, sanitizeFilename(agentName) + ".md");
        java.nio.file.Files.writeString(file.toPath(), content, java.nio.charset.StandardCharsets.UTF_8);
        log.info("Generated CLAUDE.md for {} at {}", agentName, file.getAbsolutePath());
        return file.getAbsolutePath();
    } catch (IOException e) {
        log.error("Failed to write CLAUDE.md for {}: {}", agentName, e.getMessage());
        return null;
    }
}

private String sanitizeFilename(String name) {
    return name.replaceAll("[\\\\/:*?\"<>|]", "_");
}
```

Add the required imports at the top of the file:

```java
import java.io.File;
import java.io.IOException;
```

- [ ] **Step 2: Call CLAUDE.md generation in createGroup()**

Insert after line 91 (after the `for (Long agentId : agentIds)` loop that adds members), before `return buildGroupResponse(conv, gc);`:

```java
// Generate CLAUDE.md files for orchestrator and each member agent
List<Long> memberAgentIds = agentIds.stream()
        .filter(id -> !id.equals(orchestratorAgentId))
        .collect(Collectors.toList());
writeClaudeMdFile(conv.getId(), orchestrator.getName(), buildOrchestratorClaudeMd(memberAgentIds));
for (Long agentId : memberAgentIds) {
    AgentEntity memberAgent = agentMapper.selectById(agentId);
    if (memberAgent != null) {
        writeClaudeMdFile(conv.getId(), memberAgent.getName(), buildMemberClaudeMd(memberAgent));
    }
}
```

- [ ] **Step 3: Add a public lookup method for CLAUDE.md paths**

Add after `buildGroupMemberContextPrompt()` (around line 264):

```java
/**
 * Get the CLAUDE.md file path for an agent in a group conversation.
 */
public String getClaudeMdPath(Long conversationDbId, String agentName) {
    File file = new File(CLAUDE_MD_BASE_DIR + "/" + conversationDbId + "/" + sanitizeFilename(agentName) + ".md");
    return file.exists() ? file.getAbsolutePath() : null;
}

/**
 * Build a name→agent lookup map for @AgentName dispatch.
 */
public Map<String, AgentEntity> buildAgentNameMap(Long conversationDbId) {
    List<GroupMemberEntity> members = groupMemberMapper.selectList(
            new LambdaQueryWrapper<GroupMemberEntity>()
                    .eq(GroupMemberEntity::getConversationId, conversationDbId));
    Map<String, AgentEntity> map = new LinkedHashMap<>();
    for (GroupMemberEntity m : members) {
        AgentEntity ag = agentMapper.selectById(m.getAgentId());
        if (ag != null) {
            map.put(ag.getName(), ag);
        }
    }
    return map;
}
```

- [ ] **Step 4: Commit**

```bash
cd D:/code/Loom && git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/service/GroupConversationService.java
git commit -m "feat: generate per-agent CLAUDE.md files at group creation"
```

---

### Task 2: LocalCliProcessManager — Accept CLAUDE_MD_PATH Env Var

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/cli/LocalCliProcessManager.java`
- Modify: `mateclaw-dev/adapters/claude-adapter.mjs`

- [ ] **Step 1: Add claudeMdPath parameter to spawn()**

Change the `spawn()` method signature (line 86-87) from:

```java
public boolean spawn(String agentId, String cliType,
                     String agentName, String systemPrompt) {
```

To:

```java
public boolean spawn(String agentId, String cliType,
                     String agentName, String systemPrompt,
                     String claudeMdPath) {
```

And inside the method, after line 111 (`pb.environment().put("SYSTEM_PROMPT", systemPrompt != null ? systemPrompt : "");`), add:

```java
if (claudeMdPath != null && !claudeMdPath.isBlank()) {
    pb.environment().put("CLAUDE_MD_PATH", claudeMdPath);
}
```

- [ ] **Step 2: Update claude-adapter.mjs to forward CLAUDE_MD_PATH**

In `mateclaw-dev/adapters/claude-adapter.mjs`, after line 52 (`env.CLAUDE_CODE_SYSTEM_PROMPT = systemPrompt;`), add:

```javascript
if (process.env.CLAUDE_MD_PATH) {
    env.CLAUDE_MD_PATH = process.env.CLAUDE_MD_PATH;
}
```

- [ ] **Step 3: Update all callers of spawn() to pass claudeMdPath**

In `BridgedAgent.java` line 117-118, change:

```java
boolean spawned = processManager.spawn(
        agentId, cliType, agentName, systemPrompt);
```

To:

```java
boolean spawned = processManager.spawn(
        agentId, cliType, agentName, systemPrompt, null);
```

(The `null` for claudeMdPath is fine for non-group-chat usage. The AgentMentionDispatcher will pass the actual path when spawning member agents.)

- [ ] **Step 4: Commit**

```bash
cd D:/code/Loom && git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/cli/LocalCliProcessManager.java mateclaw-dev/adapters/claude-adapter.mjs mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/BridgedAgent.java
git commit -m "feat: support CLAUDE_MD_PATH env var in LocalCliProcessManager and claude-adapter"
```

---

### Task 3: Create AgentMentionDispatcher

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/service/AgentMentionDispatcher.java`

- [ ] **Step 1: Create the class file**

Create the new file with this content:

```java
package vip.mate.group.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import vip.mate.agent.AgentService;
import vip.mate.agent.cli.LocalCliProcessManager;
import vip.mate.agent.model.AgentEntity;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.workspace.conversation.ConversationService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts the Orchestrator's streaming output to detect @AgentName: task lines,
 * spawns the named agent's CLI on-demand, and multiplexes agent responses into the SSE stream.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMentionDispatcher {

    private final GroupConversationService groupConversationService;
    private final ConversationService conversationService;
    private final AgentService agentService;
    private final LocalCliProcessManager processManager;
    private final ChatStreamTracker streamTracker;
    private final vip.mate.workspace.conversation.repository.MessageMapper messageMapper;

    /** Regex: @AgentName: task content */
    private static final Pattern AGENT_PATTERN = Pattern.compile("^@(\\S+):\\s*(.+)$");

    /** Track active agent streams per conversation to avoid duplicate spawns */
    private final Map<String, Map<String, Boolean>> dispatchedAgents = new ConcurrentHashMap<>();

    /**
     * Test a complete line against the @AgentName pattern.
     * If matched, spawn the agent and stream its response.
     *
     * @param conversationDbId  database ID of the conversation (Long)
     * @param conversationId     string conversation ID
     * @param agentNameMap       name → AgentEntity lookup
     * @param line               a complete line from the orchestrator's output
     * @param semaphore          concurrency limiter
     * @return true if the line was an @AgentName dispatch
     */
    public boolean dispatchIfComplete(Long conversationDbId, String conversationId,
                                       Map<String, AgentEntity> agentNameMap,
                                       String line, Semaphore semaphore) {
        Matcher m = AGENT_PATTERN.matcher(line.trim());
        if (!m.matches()) return false;

        String agentName = m.group(1);
        String task = m.group(2).trim();

        // Dedup: don't spawn the same agent twice in one turn
        Map<String, Boolean> convDispatched = dispatchedAgents.computeIfAbsent(
                conversationId, k -> new ConcurrentHashMap<>());
        if (convDispatched.putIfAbsent(agentName, Boolean.TRUE) != null) {
            log.info("[Dispatcher] Agent {} already dispatched in this turn, skipping", agentName);
            return true; // still consume the line, just don't re-spawn
        }

        AgentEntity agent = agentNameMap.get(agentName);
        if (agent == null) {
            log.warn("[Dispatcher] @Agent name '{}' not found in group members", agentName);
            return true; // consumed but cannot dispatch
        }

        String claudeMdPath = groupConversationService.getClaudeMdPath(conversationDbId, agentName);

        // Broadcast agent_message_start
        streamTracker.broadcastObject(conversationId, "agent_message_start", Map.of(
                "agentName", agentName,
                "agentId", String.valueOf(agent.getId()),
                "taskDescription", task
        ));

        // Run agent in a virtual thread so orchestrator stream is not blocked
        Thread.startVirtualThread(() -> {
            try {
                if (!semaphore.tryAcquire(180, TimeUnit.SECONDS)) {
                    log.warn("[Dispatcher] Semaphore timeout for agent={}", agentName);
                    broadcastAgentError(conversationId, agentName, "等待槽位超时");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                spawnAndStreamAgent(agent, agentName, task, claudeMdPath, conversationId, conversationDbId);
            } finally {
                semaphore.release();
            }
        });

        return true;
    }

    private void spawnAndStreamAgent(AgentEntity agent, String agentName, String task,
                                      String claudeMdPath, String conversationId,
                                      Long conversationDbId) {
        String agentIdStr = String.valueOf(agent.getId());
        StringBuilder fullResponse = new StringBuilder();

        try {
            // Spawn CLI process with the agent's CLAUDE.md
            boolean spawned = processManager.spawn(
                    agentIdStr, agent.getCliType(), agentName,
                    agent.getSystemPrompt(), claudeMdPath);

            if (!spawned && !processManager.isRunning(agentIdStr)) {
                log.error("[Dispatcher] Failed to spawn CLI for agent={}", agentName);
                broadcastAgentError(conversationId, agentName, "Agent CLI 启动失败");
                return;
            }

            // Create a Flux to collect the agent's response
            Flux.<AgentService.StreamDelta>create(sink -> {
                processManager.registerResponseSink(agentIdStr, sink);
                sink.onDispose(() -> {
                    processManager.unregisterResponseSink(agentIdStr);
                    processManager.terminate(agentIdStr);
                });

                // Send the task as a chat_request
                Map<String, Object> chatPayload = new java.util.LinkedHashMap<>();
                chatPayload.put("message", task);
                chatPayload.put("conversationId", conversationId);
                chatPayload.put("systemPrompt", agent.getSystemPrompt() != null ? agent.getSystemPrompt() : "");
                vip.mate.agent.bridge.model.BridgeFrame request =
                        vip.mate.agent.bridge.model.BridgeFrame.of("chat_request", chatPayload);
                processManager.sendFrame(agentIdStr, request);
            }, FluxSink.OverflowStrategy.LATEST)
            .doOnNext(delta -> {
                if (delta.getContent() != null) {
                    fullResponse.append(delta.getContent());
                    streamTracker.broadcastObject(conversationId, "content_delta", Map.of(
                            "delta", delta.getContent(),
                            "agentName", agentName
                    ));
                }
            })
            .doOnComplete(() -> {
                String responseText = fullResponse.toString();
                if (!responseText.isBlank()) {
                    try {
                        var msg = conversationService.saveMessage(conversationId, "assistant", responseText);
                        msg.setSenderAgentId(agent.getId());
                        messageMapper.updateById(msg);
                    } catch (Exception e) {
                        log.error("[Dispatcher] Failed to save message for agent={}: {}", agentName, e.getMessage());
                    }
                }
                streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                        "agentName", agentName,
                        "status", "completed"
                ));
                processManager.terminate(agentIdStr);
                log.info("[Dispatcher] Agent {} completed, response length={}", agentName, responseText.length());
            })
            .doOnError(err -> {
                log.error("[Dispatcher] Agent {} error: {}", agentName, err.getMessage());
                broadcastAgentError(conversationId, agentName, err.getMessage());
                processManager.terminate(agentIdStr);
            })
            .subscribe();

        } catch (Exception e) {
            log.error("[Dispatcher] Failed to spawn agent {}: {}", agentName, e.getMessage());
            broadcastAgentError(conversationId, agentName, e.getMessage());
        }
    }

    private void broadcastAgentError(String conversationId, String agentName, String error) {
        streamTracker.broadcastObject(conversationId, "agent_message_complete", Map.of(
                "agentName", agentName,
                "status", "error",
                "error", error
        ));
    }

    /** Reset dispatch tracking for a new turn */
    public void resetForTurn(String conversationId) {
        Map<String, Boolean> convDispatched = dispatchedAgents.get(conversationId);
        if (convDispatched != null) {
            convDispatched.clear();
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
cd D:/code/Loom && git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/service/AgentMentionDispatcher.java
git commit -m "feat: add AgentMentionDispatcher for @AgentName streaming dispatch"
```

---

### Task 4: ChatController — Wire Dispatcher Into Group Chat Stream

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/channel/web/ChatController.java`

- [ ] **Step 1: Inject AgentMentionDispatcher**

Add the field injection near the other `@Autowired` / constructor parameters (around line 1-50, find the constructor or field declarations):

```java
private final AgentMentionDispatcher mentionDispatcher;
```

Add to the class constructor parameters. If `ChatController` uses `@RequiredArgsConstructor`, just add the field and Lombok will handle it.

- [ ] **Step 2: Add group chat Flux interceptor**

After line 618 (the `agentService.chatStructuredStream(...)` call), wrap the Flux to intercept @AgentName lines for group conversations. Replace lines 618-626:

```java
Disposable disposable = agentService.chatStructuredStream(agentId, promptText, conversationId, username, request.getThinkingLevel(), webOrigin)
        .doOnNext(delta -> {
            if (emitterDone.get()) return;
            try {
                accumulator.accept(delta, conversationId);
            } catch (Exception e) {
                log.warn("SSE broadcast error: {}", e.getMessage());
            }
        })
```

With:

```java
// For group chats, intercept orchestrator output to detect @AgentName lines
boolean isGroupChat = "group".equals(conversationType);
final Map<String, AgentEntity> agentNameMap = isGroupChat
        ? groupConversationService.buildAgentNameMap(conversationDbId)
        : null;
final Semaphore groupSemaphore = isGroupChat
        ? new Semaphore(maxParallelTasks != null ? maxParallelTasks : 4)
        : null;
final StringBuilder lineBuffer = isGroupChat ? new StringBuilder() : null;
final Long convDbId = isGroupChat ? conversationDbId : null;

Flux<AgentService.StreamDelta> agentFlux = agentService.chatStructuredStream(
        agentId, promptText, conversationId, username, request.getThinkingLevel(), webOrigin);

if (isGroupChat && agentNameMap != null && convDbId != null) {
    mentionDispatcher.resetForTurn(conversationId);
    agentFlux = agentFlux.doOnNext(delta -> {
        if (delta.getContent() != null) {
            String text = delta.getContent();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    String line = lineBuffer.toString();
                    lineBuffer.setLength(0);
                    mentionDispatcher.dispatchIfComplete(convDbId, conversationId,
                            agentNameMap, line, groupSemaphore);
                } else {
                    lineBuffer.append(c);
                }
            }
        }
    }).doOnComplete(() -> {
        // Flush remaining buffer
        if (lineBuffer.length() > 0) {
            mentionDispatcher.dispatchIfComplete(convDbId, conversationId,
                    agentNameMap, lineBuffer.toString(), groupSemaphore);
        }
    });
}

Disposable disposable = agentFlux
        .doOnNext(delta -> {
            if (emitterDone.get()) return;
            try {
                accumulator.accept(delta, conversationId);
            } catch (Exception e) {
                log.warn("SSE broadcast error: {}", e.getMessage());
            }
        })
```

- [ ] **Step 3: Resolve conversationDbId and maxParallelTasks earlier in the method**

The `conversationDbId` and `maxParallelTasks` need to be resolved before this code runs. Find where `conversationDbId` is first used (likely around the `getOrCreateLocalCid` call) and ensure these variables are available at the point where we add the interceptor.

Add before the Flux wrapper (around line 594-599 area):

```java
// Resolve group config for @AgentName dispatch
Long conversationDbId = null;
Integer maxParallelTasks = null;
String conversationType = null;
try {
    var conv = conversationService.getByConversationId(conversationId);
    if (conv != null) {
        conversationDbId = conv.getId();
        conversationType = conv.getConversationType();
        if ("group".equals(conversationType)) {
            var gc = groupConversationService.getGroupConfig(conversationDbId);
            if (gc != null && gc.containsKey("maxParallelTasks")) {
                maxParallelTasks = (Integer) gc.get("maxParallelTasks");
            }
        }
    }
} catch (Exception e) {
    log.debug("Could not resolve conversation metadata: {}", e.getMessage());
}
```

- [ ] **Step 4: Add required imports**

Add these imports at the top of ChatController.java:

```java
import vip.mate.group.service.AgentMentionDispatcher;
import java.util.concurrent.Semaphore;
```

- [ ] **Step 5: Add getGroupConfig method to GroupConversationService**

In `GroupConversationService.java`, add a method to get the group config by conversation DB ID:

```java
public Map<String, Object> getGroupConfig(Long conversationDbId) {
    GroupConversationEntity gc = groupConversationMapper.selectOne(
            new LambdaQueryWrapper<GroupConversationEntity>()
                    .eq(GroupConversationEntity::getConversationId, conversationDbId));
    if (gc == null) return null;
    return Map.of(
            "orchestratorAgentId", gc.getOrchestratorAgentId(),
            "schedulingMode", gc.getSchedulingMode(),
            "failurePolicy", gc.getFailurePolicy(),
            "maxParallelTasks", gc.getMaxParallelTasks()
    );
}
```

- [ ] **Step 6: Commit**

```bash
cd D:/code/Loom && git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/channel/web/ChatController.java mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/service/GroupConversationService.java
git commit -m "feat: wire AgentMentionDispatcher into group chat stream in ChatController"
```

---

### Task 5: Frontend — Handle Multi-Agent SSE Events in chat.js

**Files:**
- Modify: `AIagent_frontend/src/stores/chat.js`

- [ ] **Step 1: Add multi-agent streaming state**

After line 28 (`const replyTo = ref(null)`), add:

```js
// Multi-agent group chat: maps agentName → local message id
const agentStreams = ref(new Map())
```

- [ ] **Step 2: Add agent_message_start handler**

After line 227 (the `artifact_preview` handler), add:

```js
sse.on('agent_message_start', (data) => {
  const agentId = addMessageLocal('assistant', '', {
    status: 'streaming',
    senderAgentName: data.agentName,
    senderAgentId: data.agentId
  })
  agentStreams.value.set(data.agentName, agentId)
})
```

- [ ] **Step 3: Modify content_delta handler to route by agentName**

Replace lines 173-189 (the `content_delta` handler) with:

```js
sse.on('content_delta', (data) => {
  contentReceived = true
  clearTimeout(contentTimeoutId)
  contentTimeoutId = setTimeout(() => {
    if (isStreaming.value) {
      streamError.value = 'Agent 响应超时，请重试'
      updateMessage(assistantId, { status: 'error' })
      isStreaming.value = false
      sse.disconnect()
    }
  }, NO_CONTENT_TIMEOUT)

  // Route to correct agent bubble in group chat, or orchestrator otherwise
  const targetId = data.agentName
    ? agentStreams.value.get(data.agentName)
    : assistantId
  if (targetId) {
    const msg = messages.value.find(m => m.id === targetId)
    if (msg) {
      updateMessage(targetId, { content: (msg.content || '') + String(data.delta || '') })
    }
  }
})
```

- [ ] **Step 4: Add agent_message_complete handler**

After the `agent_message_start` handler added in Step 2, add:

```js
sse.on('agent_message_complete', (data) => {
  const agentId = agentStreams.value.get(data.agentName)
  if (agentId) {
    updateMessage(agentId, {
      status: data.status === 'error' ? 'error' : 'completed'
    })
    agentStreams.value.delete(data.agentName)
  }
})
```

- [ ] **Step 5: Clear agent streams when conversation changes**

In `initConversation()` (around line 98-99), add after `replyTo.value = null`:

```js
agentStreams.value.clear()
```

- [ ] **Step 6: Clear agent streams in clearMessages()**

In `clearMessages()` (around line 392-400), add after `replyTo.value = null`:

```js
agentStreams.value.clear()
```

- [ ] **Step 7: Return agentStreams from the store**

Add to the return statement (around line 402-410):

```js
agentStreams,
```

- [ ] **Step 8: Commit**

```bash
cd D:/code/Loom && git add AIagent_frontend/src/stores/chat.js
git commit -m "feat: handle multi-agent SSE events (agent_message_start/complete) in chat store"
```

---

### Task 6: Integration Verification

- [ ] **Step 1: Compile backend**

```bash
cd D:/code/Loom/mateclaw-dev && ./mvnw clean compile -pl mateclaw-server -am
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Verify frontend builds**

```bash
cd D:/code/Loom/AIagent_frontend && npm run build
```

Expected: Build completes without errors

- [ ] **Step 3: Manual test flow**

1. Start backend: `cd D:/code/Loom/mateclaw-dev && ./mvnw spring-boot:run`
2. Start frontend: `cd D:/code/Loom/AIagent_frontend && npm run dev`
3. Open http://localhost:3000, login
4. Click "新建群聊", select 2+ agents with `local_cli` / `claude_code` type
5. Select an orchestrator (also claude_code type)
6. Create the group
7. Send a message like "帮我设计一个登录页面"
8. Verify:
   - Orchestrator replies with @Agent名: task lines
   - Each @mentioned agent spawns a CLI window and replies
   - Each agent's reply appears as a separate message bubble with the agent's name
   - CLAUDE.md files are generated in `%TEMP%/agenthub-claude-md/{convId}/`

- [ ] **Step 4: Commit any fixes**

```bash
cd D:/code/Loom && git add -A && git commit -m "fix: integration adjustments for group chat @AgentName dispatch"
```
