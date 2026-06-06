# 群聊 Orchestrator AI 任务分配 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the local_cli Orchestrator with arther-agent's Agent01 (from agents.yml) for group chat task assignment, and add @AgentName highlighting in the frontend.

**Architecture:** Backend proxy bridge — mateclaw-server calls arther-agent:8091 REST API via new ArtherAgentClient, relays SSE to frontend, reuses existing AgentMentionDispatcher for @AgentName interception. Frontend removes Orchestrator selector, adds @AgentName highlight pre-processing in markdown pipeline.

**Tech Stack:** Java 21 + Spring WebClient + Vue 3 + marked + DOMPurify + Naive UI

---

### Task 1: agents.yml — Add @Agent dedup constraint

**Files:**
- Modify: `mateclaw-dev/arther-agent/src/main/resources/agent/agents.yml:44-46`

- [ ] **Step 1: Add the dedup rule to Agent01 instruction**

Open `mateclaw-dev/arther-agent/src/main/resources/agent/agents.yml`.

Find the `## 输出格式` section. Add a new rule after line 45 (`- 必须使用纯文本，每条任务指派独占一行...`):

```yaml
                instruction: |
                  ...existing content...

                  ## 输出格式
                  - 必须使用纯文本，每条任务指派独占一行，格式严格为：`@智能体名称 任务描述`
                  - 每个智能体在一轮输出中只能被 @ 指派一次。在任务描述中引用其他智能体的依赖关系时，去掉 @ 前缀，直接使用智能体名称（例如"等待 设计Agent 交付设计稿后"而非"等待 @设计Agent 交付设计稿后"）。可在任务描述中用"(可与 某某 并行)"标注并行关系，但不加 @。
                  - 对于可并行的子任务，在任务描述中标注"(可与 @某某 并行)"。
```

Wait, the existing line 46 already has `(可与 @某某 并行)` — let me keep the precise edit. Actually, the simplest change is to insert the new rule after the format line on line 45:

```yaml
                  - 必须使用纯文本，每条任务指派独占一行，格式严格为：`@智能体名称 任务描述`
                  - 每个智能体在一轮输出中只能被 @ 指派一次。在任务描述中引用其他智能体时去掉 @ 前缀，直接使用智能体名称（例如"等待 设计Agent 交付设计稿后"，而非"等待 @设计Agent 交付设计稿后"）。
                  - 对于可并行的子任务，在任务描述中标注"(可与 某某 并行)"，不加 @。
```

Also update the example output to remove secondary @mentions. Change lines 64-68:

```yaml
                  **你的输出**：
                  ---
                  活动报名页需要设计、开发、测试串行完成，预算审计可并行推进。
                  @设计Agent 请完成活动报名页的UI设计稿，参考《活动需求文档v2》，确保适配移动端，最迟明天12:00交付设计稿到共享盘。(可与 财务Agent 并行)
                  @研发Agent 等待 设计Agent 交付设计稿后，进行H5页面开发并部署到测试环境，需包含表单验证和支付对接，请在2026-06-25前完成提测。
                  @测试Agent 等待 研发Agent 提测后，对活动报名页进行全功能测试和兼容性测试，输出测试报告，所有P0用例通过后方可上线，请在2026-06-28前完成。
                  @财务Agent 请审计本次活动的所有预算项，包括设计、开发、服务器、推广费用，生成审计报告，截止时间2026-06-30。
                  ---
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/arther-agent/src/main/resources/agent/agents.yml
git commit -m "fix(agents.yml): add @Agent non-duplication rule to Agent01 instruction"
```

---

### Task 2: ArtherAgentClient.java — New HTTP client for arther-agent

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/client/ArtherAgentClient.java`

- [ ] **Step 1: Verify target directory exists**

```bash
ls -d "mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/client/" 2>/dev/null || mkdir -p "mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/client/"
```

- [ ] **Step 2: Create ArtherAgentClient.java**

Create `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/client/ArtherAgentClient.java`:

```java
package vip.mate.group.client;

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
    public static String extractTextFromSseLine(String sseLine) {
        if (sseLine == null || !sseLine.startsWith("data: ")) return null;
        String json = sseLine.substring(6).trim();
        // Quick check: does it contain "textDelta"?
        if (!json.contains("\"textDelta\"")) return null;
        // Extract "text" field value
        int textIdx = json.indexOf("\"text\":\"");
        if (textIdx == -1) return null;
        int start = textIdx + 8;
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
```

- [ ] **Step 3: Add arther-agent base URL config to application.yml**

Append to `mateclaw-dev/mateclaw-server/src/main/resources/application.yml`:

```yaml
# arther-agent engine base URL for Orchestrator calls
arther:
  agent:
    base-url: http://127.0.0.1:8091
```

- [ ] **Step 4: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/client/ArtherAgentClient.java \
        mateclaw-dev/mateclaw-server/src/main/resources/application.yml
git commit -m "feat: add ArtherAgentClient for calling arther-agent orchestrator API"
```

---

### Task 3: GroupConversationService.java — Remove local_cli orchestrator logic

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/service/GroupConversationService.java`
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/controller/GroupConversationController.java`

- [ ] **Step 1: Remove orchestratorAgentId parameter from createGroup**

In `GroupConversationService.java:96-98`, change the method signature. Old:

```java
public Map<String, Object> createGroup(String username, Long workspaceId, String title,
                                        Long orchestratorAgentId, List<Long> agentIds,
                                        String schedulingMode, String failurePolicy, Integer maxParallelTasks) {
```

New:

```java
public Map<String, Object> createGroup(String username, Long workspaceId, String title,
                                        List<Long> agentIds,
                                        String schedulingMode, String failurePolicy, Integer maxParallelTasks) {
```

- [ ] **Step 2: Remove orchestrator validation and creation logic**

In `GroupConversationService.java:103-118`, remove the entire orchestrator agent validation block. Old (lines 103-118):

```java
        if (orchestratorAgentId == null) {
            orchestratorAgentId = findOrCreateDefaultOrchestrator(workspaceId);
            log.info("Auto-assigned orchestratorAgentId={} for group chat", orchestratorAgentId);
        }

        AgentEntity orchestrator = agentMapper.selectById(orchestratorAgentId);
        if (orchestrator == null) {
            throw new MateClawException("err.group.invalid_orchestrator", "指定的 Orchestrator Agent 不存在");
        }
        boolean validOrch = "orchestrator".equals(orchestrator.getAgentType())
                || ("local_cli".equals(orchestrator.getAgentType())
                    && "claude_code".equals(orchestrator.getCliType()));
        if (!validOrch) {
            throw new MateClawException("err.group.invalid_orchestrator",
                    "Orchestrator 必须是 Claude Code 类型");
        }
```

Replace with (just keep the comment):

```java
        // Orchestrator is now handled by arther-agent Agent01 (agents.yml "000001").
        // No local_cli Orchestrator needed — the first user message in the group
        // will be routed to Agent01 via ArtherAgentClient.
```

- [ ] **Step 3: Update conversation Entity creation to use a placeholder agentId**

In `GroupConversationService.java:121-131`, change `conv.setAgentId(orchestratorAgentId)` to use the first member agent or 0:

```java
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId(UUID.randomUUID().toString());
        conv.setTitle(title);
        conv.setAgentId(0L);  // group chat uses arther-agent orchestrator, no single agent
        conv.setUsername(username);
```

- [ ] **Step 4: Update GroupConversationEntity creation to remove orchestratorAgentId**

In `GroupConversationService.java:134-142`, change `gc.setOrchestratorAgentId(orchestratorAgentId)` to 0L:

```java
        GroupConversationEntity gc = new GroupConversationEntity();
        gc.setConversationId(conv.getId());
        gc.setOrchestratorAgentId(0L);  // orchestored by arther-agent Agent01
        gc.setSchedulingMode(schedulingMode != null ? schedulingMode : "auto");
```

- [ ] **Step 5: Remove addMemberInternal for orchestrator**

In `GroupConversationService.java:144-145`, remove:

```java
        // Add orchestrator as member
        addMemberInternal(conv.getId(), orchestratorAgentId, "orchestrator");
```

Replace with a comment:

```java
        // Agent01 from arther-agent acts as orchestrator (not a DB member)
```

- [ ] **Step 6: Remove findOrCreateDefaultOrchestrator() method**

Remove the entire method at lines 378-404 of `GroupConversationService.java` (the `findOrCreateDefaultOrchestrator` method).

- [ ] **Step 7: Remove generateClaudeMdFiles method**

Remove the `generateClaudeMdFiles()` method at lines 161-178 and its helper methods `buildOrchestratorClaudeMd()` at lines 40-56, `buildMemberClaudeMd()` at lines 61-69, `writeClaudeMdFile()` at lines 74-86, `sanitizeFilename()` at lines 88-90, and `getClaudeMdPath()` at lines 353-356.

Also remove the `CLAUDE_MD_BASE_DIR` constant at line 34-35.

- [ ] **Step 8: Update GroupConversationController to remove orchestratorId and CLAUDE.md generation**

In `GroupConversationController.java:36-53`, change the `create()` method. Old:

```java
        Long orchestratorId = body.get("orchestratorAgentId") != null
            ? Long.valueOf(body.get("orchestratorAgentId").toString()) : null;
        List<Long> agentIds = safeConvertToLongList(body.get("agentIds"));
        String schedulingMode = (String) body.getOrDefault("schedulingMode", "auto");
        String failurePolicy = (String) body.getOrDefault("failurePolicy", "fail_tolerant");
        Integer maxParallel = body.get("maxParallelTasks") != null
            ? Integer.valueOf(body.get("maxParallelTasks").toString()) : 8;
        Map<String, Object> result = groupConversationService.createGroup(username, workspaceId, title,
            orchestratorId, agentIds, schedulingMode, failurePolicy, maxParallel);
        Long conversationDbId = Long.valueOf(result.get("conversationId").toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> groupConfig = (Map<String, Object>) result.get("groupConfig");
        Long actualOrchestratorId = Long.valueOf(groupConfig.get("orchestratorAgentId").toString());
        try {
            groupConversationService.generateClaudeMdFiles(conversationDbId, actualOrchestratorId, agentIds);
        } catch (Exception e) {
            log.warn("CLAUDE.md generation failed for group {}: {}", conversationDbId, e.getMessage());
        }
        return R.ok(result);
```

New:

```java
        List<Long> agentIds = safeConvertToLongList(body.get("agentIds"));
        String schedulingMode = (String) body.getOrDefault("schedulingMode", "auto");
        String failurePolicy = (String) body.getOrDefault("failurePolicy", "fail_tolerant");
        Integer maxParallel = body.get("maxParallelTasks") != null
            ? Integer.valueOf(body.get("maxParallelTasks").toString()) : 8;
        Map<String, Object> result = groupConversationService.createGroup(username, workspaceId, title,
            agentIds, schedulingMode, failurePolicy, maxParallel);
        return R.ok(result);
```

- [ ] **Step 9: Compile to verify no compilation errors**

```bash
cd mateclaw-dev && ./mvnw clean compile -pl mateclaw-server -am 2>&1 | tail -20
```

Expected: BUILD SUCCESS

- [ ] **Step 10: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/service/GroupConversationService.java \
        mateclaw-dev/mateclaw-server/src/main/java/vip/mate/group/controller/GroupConversationController.java
git commit -m "refactor: remove local_cli orchestrator logic, use arther-agent Agent01 instead"
```

---

### Task 4: ChatController.java — Route group chat messages to arther-agent

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/channel/web/ChatController.java`

- [ ] **Step 1: Add ArtherAgentClient import and field**

Add to imports (around line 25-27):

```java
import vip.mate.group.client.ArtherAgentClient;
```

Add field to the class (around line 68):

```java
    private final ArtherAgentClient artherAgentClient;
```

The constructor is `@RequiredArgsConstructor`, so Spring will auto-inject it.

- [ ] **Step 2: Replace the orchestrator flux source for group chats**

Find the section at lines 654-680. Currently:

```java
                var agentFlux = agentService.chatStructuredStream(agentId, promptText, conversationId, username, request.getThinkingLevel(), webOrigin);

                if (isGroupChat && agentNameMap != null && convDbId != null) {
                    mentionDispatcher.resetForTurn(convId);
                    agentFlux = agentFlux.doOnNext(delta -> {
                        ...
                    }).doOnComplete(() -> {
                        ...
                    });
                }
```

Replace with:

```java
                Flux<AgentService.StreamDelta> agentFlux;

                if (isGroupChat && agentNameMap != null && convDbId != null) {
                    // ── Group chat: use arther-agent Agent01 as Orchestrator ──
                    List<AgentEntity> memberAgents = new ArrayList<>(agentNameMap.values());
                    String orchPrompt = artherAgentClient.buildOrchestratorPrompt(memberAgents, message);

                    mentionDispatcher.resetForTurn(convId);

                    agentFlux = artherAgentClient.callOrchestrator(username, orchPrompt)
                            .map(sseLine -> {
                                String text = ArtherAgentClient.extractTextFromSseLine(sseLine);
                                if (text != null) {
                                    // Feed text through line buffer for @AgentName detection
                                    for (int i = 0; i < text.length(); i++) {
                                        char c = text.charAt(i);
                                        if (c == '\n') {
                                            String line = lineBuffer.toString();
                                            lineBuffer.setLength(0);
                                            mentionDispatcher.dispatchIfComplete(convDbId, convId,
                                                    agentNameMap, line, groupSemaphore);
                                        } else {
                                            lineBuffer.append(c);
                                        }
                                    }
                                    // Return content as StreamDelta for broadcast
                                    return new AgentService.StreamDelta(text, null);
                                }
                                // Non-textDelta events (toolCall, toolResult, etc.) —
                                // don't broadcast, but keep the flux alive
                                return new AgentService.StreamDelta("", null);
                            })
                            .doOnComplete(() -> {
                                if (lineBuffer.length() > 0) {
                                    mentionDispatcher.dispatchIfComplete(convDbId, convId,
                                            agentNameMap, lineBuffer.toString(), groupSemaphore);
                                }
                            })
                            .doOnError(err -> {
                                log.error("arther-agent orchestrator stream failed for group {}: {}",
                                        convId, err.getMessage());
                                broadcastEvent(convId, "error", Map.of(
                                        "message", "Orchestrator 服务不可用: " + err.getMessage()
                                ));
                            });
                } else {
                    // ── Direct / non-group chat: use existing agent stream ──
                    agentFlux = agentService.chatStructuredStream(agentId, promptText, conversationId,
                            username, request.getThinkingLevel(), webOrigin);

                    if (isGroupChat && agentNameMap != null && convDbId != null) {
                        mentionDispatcher.resetForTurn(convId);
                        agentFlux = agentFlux.doOnNext(delta -> {
                            if (delta.content() != null) {
                                String text = delta.content();
                                for (int i = 0; i < text.length(); i++) {
                                    char c = text.charAt(i);
                                    if (c == '\n') {
                                        String line = lineBuffer.toString();
                                        lineBuffer.setLength(0);
                                        mentionDispatcher.dispatchIfComplete(convDbId, convId,
                                                agentNameMap, line, groupSemaphore);
                                    } else {
                                        lineBuffer.append(c);
                                    }
                                }
                            }
                        }).doOnComplete(() -> {
                            if (lineBuffer.length() > 0) {
                                mentionDispatcher.dispatchIfComplete(convDbId, convId,
                                        agentNameMap, lineBuffer.toString(), groupSemaphore);
                            }
                        });
                    }
                }
```

Note: Add `import java.util.ArrayList;` at the top if not already present.

- [ ] **Step 3: Remove the group context injection for group chats (no longer needed)**

The existing code at lines 598-602 injects `buildGroupMemberContextPrompt()` into the prompt. For group chats going through arther-agent, this is redundant since `ArtherAgentClient.buildOrchestratorPrompt()` already formats the context. The group context check at lines 598-602 should stay for the non-group branch. No change needed — the `if` already works correctly since `isGroupChat` routes to arther-agent, and non-group chats won't have `groupCtx`.

- [ ] **Step 4: Compile to verify no compilation errors**

```bash
cd mateclaw-dev && ./mvnw clean compile -pl mateclaw-server -am 2>&1 | tail -20
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/channel/web/ChatController.java
git commit -m "feat: route group chat messages to arther-agent Agent01 orchestrator"
```

---

### Task 5: AgentSelector.vue — Remove Orchestrator selector

**Files:**
- Modify: `AIagent_frontend/src/components/agent/AgentSelector.vue`

- [ ] **Step 1: Remove orchestrator-related JS**

In `<script setup>`, remove the following lines:

Remove line 16: `const orchestratorAgentId = ref(null)`

Remove lines 18-20: `const orchestratorCandidates = computed(...)` block:

```js
const orchestratorCandidates = computed(() =>
  props.agents.filter(a => a.agentType === 'local_cli' && a.cliType === 'claude_code')
)
```

- [ ] **Step 2: Update handleCreate() — remove orchestratorAgentId from emit**

In `handleCreate()` (lines 34-53), replace the else branch. Old:

```js
  } else {
    if (selectedAgentIds.value.length < 2) return
    emit('create', {
      mode: 'group',
      title: groupTitle.value,
      agentIds: selectedAgentIds.value.map(id => Number(id)),
      orchestratorAgentId: orchestratorAgentId.value
        ? Number(orchestratorAgentId.value)
        : (orchestratorCandidates.value[0]?.id != null ? Number(orchestratorCandidates.value[0].id) : null),
      schedulingMode: 'auto',
      failurePolicy: 'fail_tolerant',
      maxParallelTasks: 4
    })
  }
```

New:

```js
  } else {
    if (selectedAgentIds.value.length < 2) return
    emit('create', {
      mode: 'group',
      title: groupTitle.value,
      agentIds: selectedAgentIds.value.map(id => Number(id)),
      schedulingMode: 'auto',
      failurePolicy: 'fail_tolerant',
      maxParallelTasks: 4
    })
  }
```

- [ ] **Step 3: Remove NSelect import (if no longer used)**

In line 3, check if `NSelect` and `NSpace` are used elsewhere. `NSpace` is still used in the template for buttons. Remove `NSelect` and `NRadio`, `NRadioGroup` from imports. New line 3:

```js
import { NModal, NAvatar, NTag, NCheckbox, NButton, NInput, NSpace } from 'naive-ui'
```

Wait — `NRadio` and `NRadioGroup` are still used in the direct mode template. Keep them. Just remove `NSelect`. New line 3:

```js
import { NModal, NAvatar, NTag, NCheckbox, NButton, NInput, NRadio, NRadioGroup, NSpace } from 'naive-ui'
```

No — actually `NSpace` is still used. `NRadio` and `NRadioGroup` are used in direct mode. `NSelect` is the only one not used after removal. So the import should be:

```js
import { NModal, NAvatar, NTag, NCheckbox, NButton, NInput, NRadio, NRadioGroup, NSpace } from 'naive-ui'
```

- [ ] **Step 4: Remove the NSelect template block**

In the template, remove lines 119-128 (the `<div class="group-config">` block):

```html
        <div class="group-config" style="margin-top: 12px">
          <NSpace vertical :size="8">
            <NSelect
              v-model:value="orchestratorAgentId"
              :options="orchestratorCandidates.map(a => ({ label: a.name, value: a.id }))"
              placeholder="选择 Orchestrator (可选)"
              clearable
            />
          </NSpace>
        </div>
```

- [ ] **Step 5: Verify the frontend compiles**

```bash
cd AIagent_frontend && npx vite build 2>&1 | tail -10
```

Expected: build succeeds without errors

- [ ] **Step 6: Commit**

```bash
git add AIagent_frontend/src/components/agent/AgentSelector.vue
git commit -m "refactor(frontend): remove Orchestrator selector from create group modal"
```

---

### Task 6: ConversationSidebar.vue — Remove orchestratorAgentId from createGroup call

**Files:**
- Modify: `AIagent_frontend/src/components/chat/ConversationSidebar.vue`

- [ ] **Step 1: Remove orchestratorAgentId from the createGroup call**

In `handleCreateConversation()` (lines 46-53), remove the `orchestratorAgentId` line. Old:

```js
      const result = await convStore.createGroup({
        title: config.title,
        orchestratorAgentId: config.orchestratorAgentId,
        agentIds: config.agentIds,
        schedulingMode: config.schedulingMode,
        failurePolicy: config.failurePolicy,
        maxParallelTasks: config.maxParallelTasks
      })
```

New:

```js
      const result = await convStore.createGroup({
        title: config.title,
        agentIds: config.agentIds,
        schedulingMode: config.schedulingMode,
        failurePolicy: config.failurePolicy,
        maxParallelTasks: config.maxParallelTasks
      })
```

- [ ] **Step 2: Verify frontend compiles**

```bash
cd AIagent_frontend && npx vite build 2>&1 | tail -10
```

- [ ] **Step 3: Commit**

```bash
git add AIagent_frontend/src/components/chat/ConversationSidebar.vue
git commit -m "refactor(frontend): remove orchestratorAgentId from group creation call"
```

---

### Task 7: useMarkdown.js — Add highlightAgentMentions function

**Files:**
- Modify: `AIagent_frontend/src/composables/useMarkdown.js`

- [ ] **Step 1: Add the highlightAgentMentions function**

In `AIagent_frontend/src/composables/useMarkdown.js`, append after the `renderMarkdown` function:

```js
const AGENT_MENTION_RE = /@([^\s,，。；;:：\n]+)/g

export function highlightAgentMentions(text) {
  if (!text) return ''
  return text.replace(AGENT_MENTION_RE, '<span class="agent-mention">$&</span>')
}
```

Full file after edit:

```js
import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({
  breaks: true,
  gfm: true
})

export function renderMarkdown(text) {
  if (!text) return ''
  const raw = marked.parse(text)
  return DOMPurify.sanitize(raw)
}

const AGENT_MENTION_RE = /@([^\s,，。；;:：\n]+)/g

export function highlightAgentMentions(text) {
  if (!text) return ''
  return text.replace(AGENT_MENTION_RE, '<span class="agent-mention">$&</span>')
}
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/composables/useMarkdown.js
git commit -m "feat(frontend): add highlightAgentMentions for @AgentName highlighting"
```

---

### Task 8: MessageBubble.vue — Apply @AgentName highlighting in rendering

**Files:**
- Modify: `AIagent_frontend/src/components/chat/MessageBubble.vue`

- [ ] **Step 1: Import highlightAgentMentions**

Change line 3 from:

```js
import { renderMarkdown } from '@/composables/useMarkdown'
```

To:

```js
import { renderMarkdown, highlightAgentMentions } from '@/composables/useMarkdown'
```

- [ ] **Step 2: Update rendedContent computed to pre-highlight**

In lines 41-45, change:

```js
const renderedContent = computed(() => {
  if (props.message.messageType === 'text' || props.message.messageType === 'system') {
    return renderMarkdown(props.message.content || '')
  }
  return ''
})
```

To:

```js
const renderedContent = computed(() => {
  if (props.message.messageType === 'text' || props.message.messageType === 'system') {
    const highlighted = highlightAgentMentions(props.message.content || '')
    return renderMarkdown(highlighted)
  }
  return ''
})
```

- [ ] **Step 3: Add .agent-mention CSS**

In the `<style scoped>` block, add after the existing `.markdown-body :deep(blockquote)` rule (around line 339):

```css
.msg-text :deep(.agent-mention) {
  background: #E8F0FE;
  color: #1A56DB;
  padding: 2px 6px;
  border-radius: 6px;
  font-weight: 600;
  white-space: nowrap;
}
```

- [ ] **Step 4: Verify frontend compiles**

```bash
cd AIagent_frontend && npx vite build 2>&1 | tail -10
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add AIagent_frontend/src/components/chat/MessageBubble.vue
git commit -m "feat(frontend): render @AgentName as highlighted tag in message bubbles"
```

---

### Task 9: Integration verification

- [ ] **Step 1: Compile entire backend**

```bash
cd mateclaw-dev && ./mvnw clean compile 2>&1 | tail -20
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Verify frontend build**

```bash
cd AIagent_frontend && npx vite build 2>&1 | tail -10
```

Expected: dist/ output, no errors

- [ ] **Step 3: Run git status to verify all changes are clean**

```bash
git status
```

- [ ] **Step 4: Final review — check the commit log**

```bash
git log --oneline -10
```

Expected: The 6 commits from Tasks 1-8 are in order.
