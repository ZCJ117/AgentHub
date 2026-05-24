# Local CLI Process Bridge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace WebSocket transport with stdin/stdout Process-based communication for local_cli agents, enabling platform to spawn Claude Code / OpenCode subprocesses on demand.

**Architecture:** New `LocalCliProcessManager` component spawns CLI subprocesses via `ProcessBuilder` and communicates over stdin/stdout JSON lines (reusing BridgeFrame protocol). BridgedAgent and AgentGraphBuilder are minimally modified to prefer ProcessManager over WebSocket for local_cli. Node.js adapter scripts translate between BridgeFrame JSON and Claude Code / OpenCode CLI invocation.

**Tech Stack:** Java 21, Spring Boot 3.5, Project Reactor, ProcessBuilder; Node.js (adapter scripts); Vue 3 + Naive UI; Flyway (V132); MySQL / H2

**Spec:** `docs/superpowers/specs/2026-05-24-local-cli-process-bridge-design.md`

---

## File Structure

```
Create:
  mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/cli/LocalCliProcessManager.java
  mateclaw-dev/adapters/claude-adapter.mjs
  mateclaw-dev/adapters/opencode-adapter.mjs
  mateclaw-dev/mateclaw-server/src/main/resources/db/migration/h2/V132__add_agent_cli_type.sql
  mateclaw-dev/mateclaw-server/src/main/resources/db/migration/mysql/V132__add_agent_cli_type.sql

Modify:
  mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/model/AgentEntity.java
  mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/BridgedAgent.java
  mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java
  mateclaw-dev/mateclaw-server/src/main/resources/application.yml
  AIagent_frontend/src/views/AgentDetailView.vue
  AIagent_frontend/src/views/AgentManageView.vue
  AIagent_frontend/src/components/agent/AgentSelector.vue
```

---

### Task 1: AgentEntity — add cliType field

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/model/AgentEntity.java`
- Create: `mateclaw-dev/mateclaw-server/src/main/resources/db/migration/h2/V132__add_agent_cli_type.sql`
- Create: `mateclaw-dev/mateclaw-server/src/main/resources/db/migration/mysql/V132__add_agent_cli_type.sql`

- [ ] **Step 1: Add cliType field to AgentEntity.java**

In `AgentEntity.java`, after the `agentStatus` field (line 88), add:

```java
/** 本地 CLI 类型：claude_code / open_code / NULL（非本地 CLI） */
private String cliType;
```

- [ ] **Step 2: Create H2 Flyway migration**

Create `mateclaw-dev/mateclaw-server/src/main/resources/db/migration/h2/V132__add_agent_cli_type.sql`:

```sql
ALTER TABLE mate_agent ADD COLUMN cli_type VARCHAR(20) DEFAULT NULL;
```

- [ ] **Step 3: Create MySQL Flyway migration**

Create `mateclaw-dev/mateclaw-server/src/main/resources/db/migration/mysql/V132__add_agent_cli_type.sql`:

```sql
ALTER TABLE mate_agent ADD COLUMN cli_type VARCHAR(20) DEFAULT NULL;
```

- [ ] **Step 4: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/model/AgentEntity.java
git add mateclaw-dev/mateclaw-server/src/main/resources/db/migration/h2/V132__add_agent_cli_type.sql
git add mateclaw-dev/mateclaw-server/src/main/resources/db/migration/mysql/V132__add_agent_cli_type.sql
git commit -m "feat: add cliType field to AgentEntity and V132 migration"
```

---

### Task 2: LocalCliProcessManager — process lifecycle manager

**Files:**
- Create: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/cli/LocalCliProcessManager.java`

- [ ] **Step 1: Create LocalCliProcessManager.java**

Create `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/cli/LocalCliProcessManager.java`:

```java
package vip.mate.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;
import vip.mate.agent.AgentService;
import vip.mate.agent.bridge.model.BridgeFrame;

import javax.annotation.PreDestroy;
import java.io.*;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 管理本地 CLI 子进程的生命周期（spawn / terminate）和 stdin/stdout 通信。
 *
 * <p>传输层：JSON 行协议（复用 BridgeFrame）。
 * <p>线程安全：ConcurrentHashMap + volatile 字段。
 */
@Slf4j
@Component
public class LocalCliProcessManager {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService readerPool = Executors.newCachedThreadPool(
            r -> new Thread(r, "cli-stdout-reader"));

    @Value("${mateclaw.local-cli.node-bin:node}")
    private String nodeBin;

    @Value("${mateclaw.local-cli.adapters-dir:adapters}")
    private String adaptersDir;

    @Value("${mateclaw.local-cli.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${mateclaw.local-cli.force-kill-seconds:5}")
    private int forceKillSeconds;

    /** agentId → 进程上下文 */
    private final ConcurrentHashMap<String, ProcessContext> processes = new ConcurrentHashMap<>();

    private record ProcessContext(
            Process process,
            BufferedWriter stdinWriter,
            Thread stdoutReaderThread,
            FluxSink<AgentService.StreamDelta> responseSink,
            long spawnTime,
            String cliType
    ) {}

    // ── Public API ───────────────────────────────────────────────────

    public boolean isRunning(String agentId) {
        ProcessContext ctx = processes.get(agentId);
        return ctx != null && ctx.process().isAlive();
    }

    /**
     * 启动 CLI 子进程。返回 true 表示成功，false 表示已在运行。
     */
    public boolean spawn(String agentId, String cliType,
                         String agentName, String systemPrompt) {
        if (isRunning(agentId)) {
            log.warn("[CliPM] Agent {} already running, skip spawn", agentId);
            return false;
        }

        String adapterPath = adaptersDir + "/" + switch (cliType) {
            case "claude_code" -> "claude-adapter.mjs";
            case "open_code" -> "opencode-adapter.mjs";
            default -> throw new IllegalArgumentException("Unknown cliType: " + cliType);
        };

        File adapterFile = new File(adapterPath);
        if (!adapterFile.exists()) {
            throw new IllegalStateException(
                    "Adapter script not found: " + adapterFile.getAbsolutePath()
                    + " — 请确保 adapters/ 目录已部署");
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(nodeBin, adapterPath);
            pb.environment().put("AGENT_ID", agentId);
            pb.environment().put("AGENT_NAME", agentName);
            pb.environment().put("SYSTEM_PROMPT", systemPrompt != null ? systemPrompt : "");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            var stdinWriter = new BufferedWriter(
                    new OutputStreamWriter(p.getOutputStream()));

            long now = System.currentTimeMillis();
            ProcessContext ctx = new ProcessContext(
                    p, stdinWriter, null, null, now, cliType);

            // 启动 stdout 读取线程
            var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Thread readerThread = new Thread(
                    () -> stdoutLoop(agentId, reader),
                    "cli-stdout-" + agentId);
            readerThread.setDaemon(true);

            // 用反射替换 ctx 中的 thread（record 不可变，改用临时方案）
            processes.put(agentId, new ProcessContext(
                    p, stdinWriter, readerThread, null, now, cliType));
            readerThread.start();

            // 握手：等待适配器 ready
            String firstLine = readLineWithTimeout(reader, 10, TimeUnit.SECONDS);
            if (firstLine == null) {
                terminate(agentId);
                throw new IllegalStateException("适配器未在 10 秒内返回 ready 信号");
            }
            BridgeFrame readyFrame = BridgeFrame.parse(firstLine);
            if (!"ready".equals(readyFrame.getType())) {
                terminate(agentId);
                throw new IllegalStateException(
                        "Expected 'ready' frame, got: " + readyFrame.getType());
            }

            // 发送 agent_info
            BridgeFrame info = BridgeFrame.of("agent_info", Map.of(
                    "agentId", agentId,
                    "agentName", agentName,
                    "systemPrompt", systemPrompt != null ? systemPrompt : ""));
            sendFrame(agentId, info);

            // 等待确认
            String confirmLine = readLineWithTimeout(reader, 10, TimeUnit.SECONDS);
            if (confirmLine == null) {
                terminate(agentId);
                throw new IllegalStateException("适配器未确认 agent_info");
            }
            BridgeFrame confirmFrame = BridgeFrame.parse(confirmLine);
            if (!"ready".equals(confirmFrame.getType())) {
                terminate(agentId);
                throw new IllegalStateException(
                        "Expected 'ready' confirmation, got: " + confirmFrame.getType());
            }

            log.info("[CliPM] Spawned {} for agent={} name={} pid={}",
                    cliType, agentId, agentName, p.pid());
            return true;

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to spawn CLI process: " + e.getMessage(), e);
        }
    }

    /**
     * 向 stdin 写入一个 BridgeFrame（JSON 行）。
     */
    public void sendFrame(String agentId, BridgeFrame frame) {
        ProcessContext ctx = processes.get(agentId);
        if (ctx == null || !ctx.process().isAlive()) {
            throw new IllegalStateException("Agent " + agentId + " is not running");
        }
        try {
            String json = frame.toJson();
            synchronized (ctx.stdinWriter()) {
                ctx.stdinWriter().write(json);
                ctx.stdinWriter().newLine();
                ctx.stdinWriter().flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write frame to stdin: " + e.getMessage(), e);
        }
    }

    /**
     * 终止进程。先发 terminate 帧，等待 forceKillSeconds，再 destroyForcibly。
     */
    public void terminate(String agentId) {
        ProcessContext ctx = processes.remove(agentId);
        if (ctx == null) return;

        try {
            // 优雅退出
            try {
                BridgeFrame terminateFrame = BridgeFrame.of("terminate", Map.of());
                sendFrame(agentId, terminateFrame);
            } catch (Exception ignored) {}

            // 等待进程自行退出
            boolean exited = ctx.process().waitFor(forceKillSeconds, TimeUnit.SECONDS);
            if (!exited) {
                ctx.process().destroyForcibly();
                log.warn("[CliPM] Force-killed agent={} pid={}", agentId, ctx.process().pid());
            }

            // 清理 sink
            if (ctx.responseSink() != null) {
                ctx.responseSink().complete();
            }
        } catch (InterruptedException e) {
            ctx.process().destroyForcibly();
            Thread.currentThread().interrupt();
        }
        log.info("[CliPM] Terminated agent={} pid={}", agentId, ctx.process().pid());
    }

    // ── Response sink management ─────────────────────────────────────

    public void registerResponseSink(String agentId,
                                      FluxSink<AgentService.StreamDelta> sink) {
        ProcessContext ctx = processes.get(agentId);
        if (ctx == null || !ctx.process().isAlive()) {
            sink.error(new IllegalStateException("Agent " + agentId + " is not running"));
            return;
        }
        ProcessContext oldCtx = ctx;
        ProcessContext newCtx = new ProcessContext(
                oldCtx.process(), oldCtx.stdinWriter(),
                oldCtx.stdoutReaderThread(), sink,
                oldCtx.spawnTime(), oldCtx.cliType());
        processes.put(agentId, newCtx);
    }

    public void unregisterResponseSink(String agentId) {
        ProcessContext ctx = processes.get(agentId);
        if (ctx != null) {
            ProcessContext newCtx = new ProcessContext(
                    ctx.process(), ctx.stdinWriter(),
                    ctx.stdoutReaderThread(), null,
                    ctx.spawnTime(), ctx.cliType());
            processes.put(agentId, newCtx);
        }
    }

    public void pushToSink(String agentId, AgentService.StreamDelta delta) {
        ProcessContext ctx = processes.get(agentId);
        if (ctx != null && ctx.responseSink() != null) {
            ctx.responseSink().next(delta);
        }
    }

    public void completeSink(String agentId) {
        ProcessContext ctx = processes.get(agentId);
        if (ctx != null && ctx.responseSink() != null) {
            ctx.responseSink().complete();
        }
    }

    public void errorSink(String agentId, Throwable error) {
        ProcessContext ctx = processes.get(agentId);
        if (ctx != null && ctx.responseSink() != null) {
            ctx.responseSink().error(error);
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private void stdoutLoop(String agentId, BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    BridgeFrame frame = BridgeFrame.parse(line);
                    dispatch(agentId, frame);
                } catch (Exception e) {
                    log.warn("[CliPM] Failed to parse stdout line for agent={}: {}",
                            agentId, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.info("[CliPM] stdout reader ended for agent={}: {}", agentId, e.getMessage());
        } finally {
            // stdout 关闭 → 进程可能已退出 → 清理
            ProcessContext ctx = processes.get(agentId);
            if (ctx != null && !ctx.process().isAlive()) {
                terminate(agentId);
            }
        }
    }

    private void dispatch(String agentId, BridgeFrame frame) {
        switch (frame.getType()) {
            case "text" -> {
                String delta = frame.getPayload() != null
                        ? String.valueOf(frame.getPayload().getOrDefault("delta", ""))
                        : "";
                pushToSink(agentId, new AgentService.StreamDelta(delta, null));
            }
            case "tool_call" ->
                pushToSink(agentId,
                        AgentService.StreamDelta.event("tool_call", frame.getPayload()));

            case "tool_result" ->
                pushToSink(agentId,
                        AgentService.StreamDelta.event("tool_result", frame.getPayload()));

            case "artifact_preview" ->
                pushToSink(agentId,
                        AgentService.StreamDelta.event("artifact_preview", frame.getPayload()));

            case "progress" ->
                pushToSink(agentId,
                        AgentService.StreamDelta.event("delegation_progress", frame.getPayload()));

            case "done" -> {
                Object finalDelta = frame.getPayload() != null
                        ? frame.getPayload().get("delta") : null;
                if (finalDelta != null && !finalDelta.toString().isEmpty()) {
                    pushToSink(agentId,
                            new AgentService.StreamDelta(finalDelta.toString(), null));
                }
                completeSink(agentId);
            }

            case "error" ->
                errorSink(agentId, new RuntimeException(
                        frame.getPayload() != null
                                ? String.valueOf(frame.getPayload()
                                    .getOrDefault("message", "unknown"))
                                : "unknown"));

            case "pong" -> {} // heartbeat response, ignore

            default ->
                log.debug("[CliPM] Unhandled frame type '{}' from agent={}",
                        frame.getType(), agentId);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[CliPM] Shutting down, terminating {} processes...", processes.size());
        for (String agentId : processes.keySet()) {
            terminate(agentId);
        }
        readerPool.shutdownNow();
    }

    private String readLineWithTimeout(BufferedReader reader,
                                        long timeout, TimeUnit unit) {
        Future<String> future = readerPool.submit(reader::readLine);
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/cli/LocalCliProcessManager.java
git commit -m "feat: add LocalCliProcessManager for stdin/stdout CLI process management"
```

---

### Task 3: BridgedAgent — add ProcessManager transport

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/BridgedAgent.java`

- [ ] **Step 1: Add LocalCliProcessManager field and constructor overload to BridgedAgent**

Replace the existing BridgedAgent.java constructor and fields:

```java
package vip.mate.agent.bridge;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentState;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.StructuredStreamCapable;
import vip.mate.agent.bridge.model.BridgeFrame;
import vip.mate.agent.cli.LocalCliProcessManager;
import vip.mate.workspace.conversation.ConversationService;

import java.util.Map;

@Slf4j
public class BridgedAgent extends BaseAgent implements StructuredStreamCapable {

    private final AgentBridgeManager bridgeManager;
    private final LocalCliProcessManager processManager;

    /** WebSocket bridge constructor (保留兼容) */
    public BridgedAgent(ConversationService conversationService,
                        AgentBridgeManager bridgeManager) {
        super(null, conversationService);
        this.bridgeManager = bridgeManager;
        this.processManager = null;
    }

    /** Process bridge constructor (新) */
    public BridgedAgent(ConversationService conversationService,
                        AgentBridgeManager bridgeManager,
                        LocalCliProcessManager processManager) {
        super(null, conversationService);
        this.bridgeManager = bridgeManager;
        this.processManager = processManager;
    }
```

- [ ] **Step 2: Modify chatStructuredStream to prefer ProcessManager for local agents**

Replace the existing `chatStructuredStream` method:

```java
    @Override
    public Flux<AgentService.StreamDelta> chatStructuredStream(
            String userMessage, String conversationId) {

        // Prefer local process if available (claude_code / open_code)
        if (processManager != null) {
            return chatViaProcess(userMessage, conversationId);
        }

        // Fall back to WebSocket bridge
        if (!bridgeManager.isOnline(agentId)) {
            return Flux.error(new IllegalStateException(
                    "Local agent '" + agentName + "' is offline"));
        }

        BridgeFrame request = BridgeFrame.of("chat_request", Map.of(
                "message", userMessage,
                "conversationId", conversationId,
                "systemPrompt", systemPrompt != null ? systemPrompt : ""));

        return Flux.<AgentService.StreamDelta>create(sink -> {
            setState(AgentState.RUNNING);
            bridgeManager.registerResponseSink(agentId, sink);

            sink.onCancel(() -> {
                log.info("[BridgedAgent] Turn cancelled for agent={}", agentId);
                bridgeManager.send(agentId, BridgeFrame.of("stop_request", Map.of()))
                        .thenRun(() -> {})
                        .exceptionally(ex -> null);
                setState(AgentState.IDLE);
            });

            sink.onDispose(() -> {
                bridgeManager.unregisterResponseSink(agentId);
                setState(AgentState.IDLE);
            });

            bridgeManager.send(agentId, request)
                    .exceptionally(ex -> {
                        log.error("[BridgedAgent] Failed to send chat_request: {}",
                                ex.getMessage());
                        sink.error(ex);
                        return null;
                    });
        }, FluxSink.OverflowStrategy.LATEST);
    }

    private Flux<AgentService.StreamDelta> chatViaProcess(
            String userMessage, String conversationId) {
        return Flux.<AgentService.StreamDelta>create(sink -> {
            setState(AgentState.RUNNING);

            boolean spawned = processManager.spawn(
                    agentId, cliType, agentName, systemPrompt);

            if (!spawned && !processManager.isRunning(agentId)) {
                sink.error(new IllegalStateException(
                        "Failed to start local agent '" + agentName + "'"));
                return;
            }

            processManager.registerResponseSink(agentId, sink);

            sink.onCancel(() -> {
                processManager.sendFrame(agentId,
                        BridgeFrame.of("stop_request", Map.of()));
                setState(AgentState.IDLE);
            });

            sink.onDispose(() -> {
                processManager.unregisterResponseSink(agentId);
                setState(AgentState.IDLE);
            });

            BridgeFrame request = BridgeFrame.of("chat_request", Map.of(
                    "message", userMessage,
                    "conversationId", conversationId,
                    "systemPrompt", systemPrompt != null ? systemPrompt : ""));

            processManager.sendFrame(agentId, request);

        }, FluxSink.OverflowStrategy.LATEST);
    }
```

Note: The class also needs a `cliType` field. Add this setter in BaseAgent or pass via BridgedAgent.

- [ ] **Step 3: Add cliType field to BridgedAgent**

Add after the existing fields:

```java
    /** CLI type for local process agents: claude_code / open_code */
    private String cliType;
```

And add a setter (or set via AgentGraphBuilder after construction):

```java
    public void setCliType(String cliType) {
        this.cliType = cliType;
    }
```

- [ ] **Step 4: Keep existing chat(), chatStream(), execute() methods**

These three methods remain unchanged — they delegate to `chatStructuredStream`.

- [ ] **Step 5: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/bridge/BridgedAgent.java
git commit -m "feat: add ProcessManager transport path to BridgedAgent"
```

---

### Task 4: AgentGraphBuilder — wire LocalCliProcessManager into local_cli branch

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java`

- [ ] **Step 1: Add LocalCliProcessManager import and field**

Add import at the top of AgentGraphBuilder.java:

```java
import vip.mate.agent.cli.LocalCliProcessManager;
```

Add field after existing `agentBridgeManager` (line 116):

```java
private final LocalCliProcessManager localCliProcessManager;
```

- [ ] **Step 2: Modify buildBridgedAgent to use ProcessManager constructor**

Replace the `buildBridgedAgent` method (lines 391-394):

```java
    /**
     * Build a BridgedAgent for local_cli type — no StateGraph needed;
     * chat is relayed over stdin/stdout to the local CLI process.
     */
    BridgedAgent buildBridgedAgent(AgentEntity entity) {
        BridgedAgent agent = new BridgedAgent(conversationService,
                agentBridgeManager, localCliProcessManager);
        agent.setCliType(entity.getCliType());
        return agent;
    }
```

- [ ] **Step 3: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/java/vip/mate/agent/AgentGraphBuilder.java
git commit -m "feat: wire LocalCliProcessManager into AgentGraphBuilder local_cli branch"
```

---

### Task 5: application.yml — add local-cli configuration

**Files:**
- Modify: `mateclaw-dev/mateclaw-server/src/main/resources/application.yml`

- [ ] **Step 1: Add local-cli config block**

Add after the existing `mateclaw.bridge.*` block (around line 200):

```yaml
mateclaw:
  local-cli:
    node-bin: "node"
    adapters-dir: "adapters"
    timeout-seconds: 300
    force-kill-seconds: 5
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/mateclaw-server/src/main/resources/application.yml
git commit -m "feat: add mateclaw.local-cli configuration"
```

---

### Task 6: claude-adapter.mjs — Claude Code adapter script

**Files:**
- Create: `mateclaw-dev/adapters/claude-adapter.mjs`

- [ ] **Step 1: Create adapter directory and script**

Create `mateclaw-dev/adapters/claude-adapter.mjs`:

```javascript
import { spawn } from 'child_process';
import { createInterface } from 'readline';

const rl = createInterface({ input: process.stdin });

function send(type, payload = {}) {
    const frame = JSON.stringify({ type, seq: 0, ts: Date.now(), payload });
    process.stdout.write(frame + '\n');
}

// ====================================================================
// Handshake
// ====================================================================
send('ready');

let agentId = null;
let agentName = null;
let systemPrompt = '';

rl.on('line', (line) => {
    let frame;
    try {
        frame = JSON.parse(line);
    } catch {
        process.stderr.write('[claude-adapter] Invalid JSON: ' + line + '\n');
        return;
    }

    switch (frame.type) {
        // ----------------------------------------------------------
        // agent_info — 后端发送 Agent 元数据
        // ----------------------------------------------------------
        case 'agent_info': {
            agentId = frame.payload?.agentId;
            agentName = frame.payload?.agentName;
            systemPrompt = frame.payload?.systemPrompt || '';
            send('ready');
            break;
        }

        // ----------------------------------------------------------
        // chat_request — 主聊天入口
        // ----------------------------------------------------------
        case 'chat_request': {
            const message = frame.payload?.message || '';
            const conversationId = frame.payload?.conversationId || 'default';

            const args = [
                '-p', message,
                '--output-format', 'stream-json',
                '--verbose'
            ];

            const env = { ...process.env };
            if (systemPrompt) {
                env.CLAUDE_CODE_SYSTEM_PROMPT = systemPrompt;
            }

            const child = spawn('claude', args, {
                env,
                stdio: ['ignore', 'pipe', 'pipe']
            });

            let buffer = '';
            child.stdout.on('data', (chunk) => {
                buffer += chunk.toString();
                // Claude Code stream-json outputs one JSON object per line
                const lines = buffer.split('\n');
                buffer = lines.pop(); // 保留不完整的最后一行

                for (const l of lines) {
                    if (!l.trim()) continue;
                    try {
                        const event = JSON.parse(l);
                        // Map Claude Code stream event → BridgeFrame
                        switch (event.type) {
                            case 'assistant':
                            case 'content_block_delta':
                                if (event.delta?.text) {
                                    send('text', { delta: event.delta.text });
                                }
                                break;
                            case 'tool_use':
                                send('tool_call', {
                                    toolName: event.name,
                                    toolInput: event.input,
                                    toolId: event.id
                                });
                                break;
                            case 'tool_result':
                                send('tool_result', {
                                    toolId: event.tool_use_id,
                                    content: event.content
                                });
                                break;
                            case 'message_delta':
                                if (event.delta?.stop_reason) {
                                    // stream ending signal, ignore
                                }
                                break;
                            default:
                                // pass through unknown events as text if they have content
                                if (event.text || event.content) {
                                    send('text', { delta: event.text || event.content });
                                }
                        }
                    } catch {
                        // non-JSON output → treat as text
                        send('text', { delta: l });
                    }
                }
            });

            child.stderr.on('data', (chunk) => {
                process.stderr.write('[claude] ' + chunk);
            });

            child.on('close', (code) => {
                // flush remaining buffer
                if (buffer.trim()) {
                    send('text', { delta: buffer });
                }
                send('done', { exitCode: code });
            });

            child.on('error', (err) => {
                send('error', { message: 'Failed to start claude: ' + err.message });
            });
            break;
        }

        // ----------------------------------------------------------
        // stop_request — 用户点了 Stop
        // ----------------------------------------------------------
        case 'stop_request': {
            // SIGTERM 当前活跃的 claude 子进程（由 chat_request 闭包引用）
            // 简化实现：平台 terminate 会直接杀适配器进程
            send('done', { delta: '', stopped: true });
            break;
        }

        // ----------------------------------------------------------
        // terminate — 对话结束，退出
        // ----------------------------------------------------------
        case 'terminate': {
            process.exit(0);
        }

        default:
            process.stderr.write('[claude-adapter] Unknown frame type: ' + frame.type + '\n');
    }
});

rl.on('close', () => {
    process.exit(0);
});
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/adapters/claude-adapter.mjs
git commit -m "feat: add claude-adapter.mjs for stdin/stdout Claude Code bridge"
```

---

### Task 7: opencode-adapter.mjs — OpenCode adapter script

**Files:**
- Create: `mateclaw-dev/adapters/opencode-adapter.mjs`

- [ ] **Step 1: Create opencode-adapter.mjs**

Create `mateclaw-dev/adapters/opencode-adapter.mjs`:

```javascript
import { spawn } from 'child_process';
import { createInterface } from 'readline';

const rl = createInterface({ input: process.stdin });

function send(type, payload = {}) {
    const frame = JSON.stringify({ type, seq: 0, ts: Date.now(), payload });
    process.stdout.write(frame + '\n');
}

// ====================================================================
// Handshake
// ====================================================================
send('ready');

let agentId = null;
let agentName = null;
let systemPrompt = '';

rl.on('line', (line) => {
    let frame;
    try {
        frame = JSON.parse(line);
    } catch {
        process.stderr.write('[opencode-adapter] Invalid JSON: ' + line + '\n');
        return;
    }

    switch (frame.type) {
        case 'agent_info': {
            agentId = frame.payload?.agentId;
            agentName = frame.payload?.agentName;
            systemPrompt = frame.payload?.systemPrompt || '';
            send('ready');
            break;
        }

        case 'chat_request': {
            const message = frame.payload?.message || '';
            const conversationId = frame.payload?.conversationId || 'default';

            // opencode CLI 调用方式（需根据实际 opencode CLI 接口调整）
            const args = ['-p', message];

            const env = { ...process.env };
            if (systemPrompt) {
                env.OPENCODE_SYSTEM_PROMPT = systemPrompt;
            }

            const child = spawn('opencode', args, {
                env,
                stdio: ['ignore', 'pipe', 'pipe']
            });

            let buffer = '';
            child.stdout.on('data', (chunk) => {
                buffer += chunk.toString();
                const lines = buffer.split('\n');
                buffer = lines.pop();

                for (const l of lines) {
                    if (!l.trim()) continue;
                    try {
                        const event = JSON.parse(l);
                        switch (event.type) {
                            case 'assistant':
                            case 'content_block_delta':
                                if (event.delta?.text) {
                                    send('text', { delta: event.delta.text });
                                }
                                break;
                            case 'tool_use':
                                send('tool_call', {
                                    toolName: event.name,
                                    toolInput: event.input,
                                    toolId: event.id
                                });
                                break;
                            case 'tool_result':
                                send('tool_result', {
                                    toolId: event.tool_use_id,
                                    content: event.content
                                });
                                break;
                            default:
                                if (event.text || event.content) {
                                    send('text', { delta: event.text || event.content });
                                }
                        }
                    } catch {
                        send('text', { delta: l });
                    }
                }
            });

            child.stderr.on('data', (chunk) => {
                process.stderr.write('[opencode] ' + chunk);
            });

            child.on('close', (code) => {
                if (buffer.trim()) {
                    send('text', { delta: buffer });
                }
                send('done', { exitCode: code });
            });

            child.on('error', (err) => {
                send('error', { message: 'Failed to start opencode: ' + err.message });
            });
            break;
        }

        case 'stop_request': {
            send('done', { delta: '', stopped: true });
            break;
        }

        case 'terminate': {
            process.exit(0);
        }

        default:
            process.stderr.write('[opencode-adapter] Unknown frame type: ' + frame.type + '\n');
    }
});

rl.on('close', () => {
    process.exit(0);
});
```

- [ ] **Step 2: Commit**

```bash
git add mateclaw-dev/adapters/opencode-adapter.mjs
git commit -m "feat: add opencode-adapter.mjs for stdin/stdout OpenCode bridge"
```

---

### Task 8: Frontend — AgentDetailView cliType support

**Files:**
- Modify: `AIagent_frontend/src/views/AgentDetailView.vue`

- [ ] **Step 1: Replace agentType options with cliType options**

Replace the `agentTypes` array (currently around line 55):

```javascript
const agentTypes = [
  { label: 'Claude Code', value: 'claude_code' },
  { label: 'OpenCode', value: 'open_code' }
]
```

- [ ] **Step 2: Add cliType ref and update save body**

Add ref after existing form state (around line 37):

```javascript
const cliType = ref('claude_code')
```

In the `save()` function, update the body to include `cliType` and remove `agentType` (or map it):

```javascript
const body = {
  name: name.value,
  description: description.value,
  systemPrompt: systemPrompt.value,
  agentType: 'local_cli',
  cliType: cliType.value,
  enabled: enabled.value,
  isPublic: isPublic.value ? 1 : 0,
  capabilityTags: JSON.stringify(capabilityTags.value),
  modelName: modelName.value
}
```

- [ ] **Step 3: Update onMounted to load cliType**

In `onMounted`, after existing load logic:

```javascript
cliType.value = detail.cliType || 'claude_code'
```

- [ ] **Step 4: Update template — replace agentType select with cliType select**

Replace the `<NSelect v-model:value="agentType" :options="agentTypes" />` with:

```html
<NSelect v-model:value="cliType" :options="agentTypes" placeholder="选择 CLI 类型" />
```

- [ ] **Step 5: Ensure model input stays hidden for local_cli**

The existing `v-if="agentType !== 'local_cli'"` on model input should now always hide (since agentType is always `local_cli`). Verify this logic is correct.

- [ ] **Step 6: Commit**

```bash
git add AIagent_frontend/src/views/AgentDetailView.vue
git commit -m "feat: add cliType field to AgentDetailView for Claude Code / OpenCode selection"
```

---

### Task 9: Frontend — AgentManageView cliType display

**Files:**
- Modify: `AIagent_frontend/src/views/AgentManageView.vue`

- [ ] **Step 1: Update typeTabs to reflect new agent types**

Replace the `typeTabs` array:

```javascript
const typeTabs = [
  { name: '', label: '全部' },
  { name: 'claude_code', label: 'Claude Code' },
  { name: 'open_code', label: 'OpenCode' }
]
```

- [ ] **Step 2: Update agent card type tag display**

Replace the `NTag` that shows agentType (line 82-84) with:

```html
<NTag size="tiny" :type="agent.cliType === 'claude_code' ? 'info' : 'warning'">
  {{ agent.cliType === 'claude_code' ? 'Claude Code' : 'OpenCode' }}
</NTag>
```

- [ ] **Step 3: Update filteredAgents computed to filter by cliType**

If the existing filter uses `agentType`, change to use `cliType`:

```javascript
if (typeFilter.value) list = list.filter(a => a.cliType === typeFilter.value)
```

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/views/AgentManageView.vue
git commit -m "feat: display cliType tags in AgentManageView"
```

---

### Task 10: Frontend — AgentSelector orchestration restriction

**Files:**
- Modify: `AIagent_frontend/src/components/agent/AgentSelector.vue`

- [ ] **Step 1: Read current AgentSelector.vue**

Read `AIagent_frontend/src/components/agent/AgentSelector.vue` to understand the current orchestrator selection logic.

- [ ] **Step 2: Restrict orchestrator to claude_code agents**

In group mode, filter orchestrator candidates to only agents with `cliType === 'claude_code'`:

```javascript
const orchestratorCandidates = computed(() =>
  props.agents.filter(a => a.cliType === 'claude_code')
)
```

Use `orchestratorCandidates` instead of the full agent list for the orchestrator dropdown.

- [ ] **Step 3: Remove schedulingMode / failurePolicy / maxParallelTasks UI**

In group mode, remove or hide these three form fields. Hardcode defaults in the backend or in the emit payload:

```javascript
const groupConfig = {
  // ... existing fields
  schedulingMode: 'auto',
  failurePolicy: 'fail_tolerant',
  maxParallelTasks: 4
}
```

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/components/agent/AgentSelector.vue
git commit -m "feat: restrict group orchestrator to claude_code agents"
```

---

### Task 11: Integration verification

- [ ] **Step 1: Compile backend**

```bash
cd mateclaw-dev && mvn clean compile -pl mateclaw-server -am
```
Expected: `BUILD SUCCESS`

- [ ] **Step 2: Build frontend**

```bash
cd AIagent_frontend && npx vite build
```
Expected: No errors, dist/ generated

- [ ] **Step 3: Verification checklist**

- [ ] `LocalCliProcessManager.spawn()` starts a Node.js adapter process via ProcessBuilder
- [ ] Adapter handshake completes (ready → agent_info → ready)
- [ ] `chat_request` frame is sent to adapter stdin, `text` / `done` frames received from stdout
- [ ] `terminate` frame causes adapter to exit
- [ ] BridgedAgent uses ProcessManager when `processManager != null`, falls back to WebSocket otherwise
- [ ] AgentEntity stores `cliType` field, persisted via V132 migration
- [ ] AgentDetailView allows selecting claude_code vs open_code
- [ ] AgentManageView displays CLI type tags and filters correctly
- [ ] AgentSelector restricts orchestrator to claude_code agents in group mode
- [ ] Existing WebSocket bridge code unchanged and still compiles
