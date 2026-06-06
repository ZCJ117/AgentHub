package vip.mate.infra.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;
import vip.mate.domain.agent.AgentService;
import vip.mate.infra.agent.bridge.model.BridgeFrame;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
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

    @Value("${mateclaw.local-cli.opencode-bin:opencode}")
    private String opencodeBin;

    @Value("${mateclaw.local-cli.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${mateclaw.local-cli.force-kill-seconds:5}")
    private int forceKillSeconds;

    /** agentId → 进程上下文 */
    private final ConcurrentHashMap<String, ProcessContext> processes = new ConcurrentHashMap<>();

    /** agentId → pending error (received before sink registration) */
    private final ConcurrentHashMap<String, Throwable> pendingErrors = new ConcurrentHashMap<>();

    @PostConstruct
    void initAdaptDir() {
        File dir = new File(adaptersDir);
        if (!dir.isAbsolute()) {
            adaptersDir = new File(System.getProperty("user.dir"), adaptersDir).getAbsolutePath();
        }
        log.info("[CliPM] Adapters directory: {}", adaptersDir);
    }

    private record ProcessContext(
            Process process,
            BufferedWriter stdinWriter,
            BufferedReader reader,
            Thread stdoutReaderThread,
            FluxSink<AgentService.StreamDelta> responseSink,
            long spawnTime,
            String cliType,
            String workDir
    ) {}

    /** Persistent Claude Code session IDs for --resume across chat requests */
    private final ConcurrentHashMap<String, String> sessionIds = new ConcurrentHashMap<>();

    public String getSessionId(String agentId) {
        return sessionIds.get(agentId);
    }

    // ── Public API ───────────────────────────────────────────────────

    public boolean isRunning(String agentId) {
        ProcessContext ctx = processes.get(agentId);
        return ctx != null && ctx.process().isAlive();
    }

    /**
     * 启动 CLI 子进程。返回 true 表示成功，false 表示已在运行。
     */
    public boolean spawn(String agentId, String cliType,
                         String agentName, String systemPrompt,
                         String claudeMdPath,
                         String workingDir) {
        if (isRunning(agentId)) {
            log.warn("[CliPM] Agent {} already running, skip spawn", agentId);
            return false;
        }

        String effectiveCliType = cliType != null ? cliType : "claude_code";
        if (cliType == null) {
            log.warn("[CliPM] Agent {} has null cliType, defaulting to claude_code", agentId);
        }
        String adapterPath = adaptersDir + "/" + switch (effectiveCliType) {
            case "claude_code" -> "claude-adapter.mjs";
            case "open_code" -> "opencode-adapter.mjs";
            default -> throw new IllegalArgumentException("Unknown cliType: " + effectiveCliType);
        };

        File adapterFile = new File(adapterPath);
        if (!adapterFile.exists()) {
            throw new IllegalStateException(
                    "Local CLI adapter not found: " + adapterFile.getAbsolutePath()
                    + ". Ensure the adapters/ directory (claude-adapter.mjs, opencode-adapter.mjs)"
                    + " is deployed, or set mateclaw.local-cli.adapters-dir in application.yml");
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(nodeBin, adapterPath);
            pb.environment().put("AGENT_ID", agentId);
            pb.environment().put("AGENT_NAME", agentName);
            pb.environment().put("SYSTEM_PROMPT", systemPrompt != null ? systemPrompt : "");
            pb.environment().put("OPENCODE_BIN", opencodeBin);
            if (claudeMdPath != null && !claudeMdPath.isBlank()) {
                pb.environment().put("CLAUDE_MD_PATH", claudeMdPath);
            }
            if (workingDir != null && !workingDir.isBlank()) {
                File dir = new File(workingDir);
                if (!dir.isDirectory()) {
                    throw new IllegalStateException(
                            "Workspace working directory does not exist: " + workingDir);
                }
                pb.directory(dir);
                log.info("[CliPM] Set working directory for agent={}: {}", agentId, workingDir);
            }
            Process p = pb.start();

            var stdinWriter = new BufferedWriter(
                    new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));

            long now = System.currentTimeMillis();
            ProcessContext ctx = new ProcessContext(
                    p, stdinWriter, null, null, null, now, cliType, workingDir);

            // 创建 stdout 读取线程（先不启动，等握手完成后再启动以避免竞态）
            var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            Thread readerThread = new Thread(
                    () -> stdoutLoop(agentId, reader),
                    "cli-stdout-" + agentId);
            readerThread.setDaemon(true);

            // stderr 读取线程 — 独立日志，避免污染 BridgeFrame 解析
            var stderrReader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
            Thread stderrThread = new Thread(
                    () -> stderrLoop(agentId, stderrReader),
                    "cli-stderr-" + agentId);
            stderrThread.setDaemon(true);
            stderrThread.start();

            processes.put(agentId, new ProcessContext(
                    p, stdinWriter, reader, readerThread, null, now, cliType, workingDir));

            // 握手：等待适配器 ready
            String firstLine = readLineWithTimeout(reader, 10, TimeUnit.SECONDS);
            if (firstLine == null) {
                terminate(agentId);
                throw new IllegalStateException(
                        "Local CLI adapter did not respond within 10 seconds."
                        + " Ensure Node.js is installed and the adapter script is not hanging.");
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

            // 握手完成后启动 stdout 读取线程，避免其与握手争夺同一个 BufferedReader
            readerThread.start();

            log.info("[CliPM] Spawned {} for agent={} name={} pid={}",
                    cliType, agentId, agentName, p.pid());
            return true;

        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to start local CLI process for " + cliType
                    + ". Check that Node.js ('" + nodeBin + "') is installed and '"
                    + adapterPath + "' is accessible.", e);
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
        pendingErrors.remove(agentId);
        if (ctx == null) return;

        try {
            // 优雅退出：直接写 stdin，不通过 sendFrame()（后者会查 processes map，ctx 已被 remove）
            try {
                BridgeFrame terminateFrame = BridgeFrame.of("terminate", Map.of());
                String json = terminateFrame.toJson();
                synchronized (ctx.stdinWriter()) {
                    ctx.stdinWriter().write(json);
                    ctx.stdinWriter().newLine();
                    ctx.stdinWriter().flush();
                }
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
        } finally {
            try { ctx.stdinWriter().close(); } catch (Exception ignored) {}
            try { ctx.reader().close(); } catch (Exception ignored) {}
        }
        log.info("[CliPM] Terminated agent={} pid={}", agentId, ctx.process().pid());
    }

    // ── Response sink management ─────────────────────────────────────

    public void registerResponseSink(String agentId,
                                      FluxSink<AgentService.StreamDelta> sink) {
        // Replay pending error if one was received before sink registration
        Throwable pending = pendingErrors.remove(agentId);
        if (pending != null) {
            sink.error(pending);
            return;
        }
        processes.compute(agentId, (id, ctx) -> {
            if (ctx == null || !ctx.process().isAlive()) {
                sink.error(new IllegalStateException("Agent " + agentId + " is not running"));
                return null;
            }
            return new ProcessContext(
                    ctx.process(), ctx.stdinWriter(),
                    ctx.reader(),
                    ctx.stdoutReaderThread(), sink,
                    ctx.spawnTime(), ctx.cliType(), ctx.workDir());
        });
    }

    public void unregisterResponseSink(String agentId) {
        processes.computeIfPresent(agentId, (id, ctx) ->
                new ProcessContext(
                        ctx.process(), ctx.stdinWriter(),
                        ctx.reader(),
                        ctx.stdoutReaderThread(), null,
                        ctx.spawnTime(), ctx.cliType(), ctx.workDir()));
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

    private void stderrLoop(String agentId, BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[CliPM:{}] {}", agentId, line);
            }
        } catch (IOException e) {
            log.debug("[CliPM] stderr reader ended for agent={}: {}", agentId, e.getMessage());
        }
    }

    private void stdoutLoop(String agentId, BufferedReader reader) {
        try {
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                try {
                    BridgeFrame frame = BridgeFrame.parse(line);
                    if ("text".equals(frame.getType())) {
                        log.info("[CliPM] stdout line#{} type=text agent={} len={}",
                                lineCount, agentId, line.length());
                    }
                    dispatch(agentId, frame);
                } catch (Exception e) {
                    log.warn("[CliPM] Failed to parse stdout line#{} for agent={}: {} — line[0:100]={}",
                            lineCount, agentId, e.getMessage(),
                            line.length() > 100 ? line.substring(0, 100) : line);
                }
            }
            log.info("[CliPM] stdoutLoop ended for agent={}, totalLines={}", agentId, lineCount);
        } catch (IOException e) {
            log.info("[CliPM] stdout reader ended for agent={}: {}", agentId, e.getMessage());
        } finally {
            // stdout 关闭 → 仅在进程确实死亡时才清理上下文
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
                log.info("[CliPM] text frame agent={} deltaLen={} sinkActive={}",
                        agentId, delta.length(),
                        processes.get(agentId) != null && processes.get(agentId).responseSink() != null);
                pushToSink(agentId, new AgentService.StreamDelta(delta, null));
            }
            case "tool_call" ->
                pushToSink(agentId,
                        AgentService.StreamDelta.event("tool_call", frame.getPayload()));

            case "tool_result" -> {
                java.util.Map<String, Object> payload =
                        new java.util.LinkedHashMap<>(frame.getPayload());
                ProcessContext ctx = processes.get(agentId);
                if (ctx != null && ctx.workDir() != null && !ctx.workDir().isBlank()) {
                    payload.putIfAbsent("workspaceBasePath", ctx.workDir());
                }
                pushToSink(agentId,
                        AgentService.StreamDelta.event("tool_result", payload));
            }

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

            case "error" -> {
                String msg = frame.getPayload() != null
                        ? String.valueOf(frame.getPayload()
                            .getOrDefault("message", "unknown"))
                        : "unknown";
                RuntimeException ex = new RuntimeException("Local CLI error: " + msg);
                ProcessContext errorCtx = processes.get(agentId);
                if (errorCtx != null && errorCtx.responseSink() != null) {
                    errorSink(agentId, ex);
                } else {
                    pendingErrors.put(agentId, ex);
                    log.warn("[CliPM] Error received before sink ready for agent={}: {}", agentId, msg);
                }
            }

            case "session_info" -> {
                if (frame.getPayload() != null && frame.getPayload().get("sessionId") != null) {
                    String sid = frame.getPayload().get("sessionId").toString();
                    sessionIds.put(agentId, sid);
                    log.info("[CliPM] Captured session_id={} for agent={}", sid, agentId);
                }
            }
            case "pong" -> {} // heartbeat response, ignore

            default ->
                log.warn("[CliPM] Unhandled frame type '{}' from agent={} payloadKeys={}",
                        frame.getType(), agentId,
                        frame.getPayload() != null ? frame.getPayload().keySet() : "null");
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[CliPM] Shutting down, terminating {} processes...", processes.size());
        for (String agentId : processes.keySet()) {
            terminate(agentId);
        }
        readerPool.shutdownNow();
        try {
            if (!readerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[CliPM] readerPool did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[CliPM] Interrupted while waiting for readerPool termination");
        }
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
