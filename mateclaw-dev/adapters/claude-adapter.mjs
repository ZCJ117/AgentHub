import { spawn } from 'child_process';
import { createInterface } from 'readline';
import fs from 'fs';
import path from 'path';
import os from 'os';

const rl = createInterface({ input: process.stdin });

// Force unbuffered stdout
if (process.stdout._handle && typeof process.stdout._handle.setBlocking === 'function') {
    process.stdout._handle.setBlocking(true);
}
const STDOUT_FD = process.stdout.fd || 1;

// ── Interval send queue: spaces out frame delivery so Java/SSE/frontend
//     render progressively instead of as a single burst ──
const sendQueue = [];
let sendTimer = null;

function drainQueue() {
    if (sendQueue.length === 0) {
        sendTimer = null;
        return;
    }
    const item = sendQueue.shift();
    const frame = JSON.stringify({ type: item.type, seq: 0, ts: Date.now(), payload: item.payload });
    try {
        fs.writeSync(STDOUT_FD, frame + '\n');
    } catch (e) {
        process.stderr.write('[claude-adapter] send error: ' + e.message + '\n');
    }
    sendTimer = setTimeout(drainQueue, 10);
}

// Direct send for lifecycle events (ready, done, error — must not be delayed)
function sendNow(type, payload = {}) {
    const frame = JSON.stringify({ type, seq: 0, ts: Date.now(), payload });
    try {
        fs.writeSync(STDOUT_FD, frame + '\n');
    } catch (e) {
        process.stderr.write('[claude-adapter] send error: ' + e.message + '\n');
    }
}

function sendText(delta) {
    sendQueue.push({ type: 'text', payload: { delta } });
    if (!sendTimer) sendTimer = setTimeout(drainQueue, 0);
}

// Handshake
sendNow('ready');

let agentId = null;
let agentName = null;
let systemPrompt = '';
let currentSessionId = null;

rl.on('line', (line) => {
    let frame;
    try {
        frame = JSON.parse(line);
    } catch {
        process.stderr.write('[claude-adapter] Invalid JSON: ' + line + '\n');
        return;
    }

    switch (frame.type) {
        case 'agent_info': {
            agentId = frame.payload?.agentId;
            agentName = frame.payload?.agentName;
            systemPrompt = frame.payload?.systemPrompt || '';
            sendNow('ready');
            break;
        }

        case 'chat_request': {
            const message = frame.payload?.message || '';
            const conversationId = frame.payload?.conversationId || 'default';
            const resumeId = frame.payload?.sessionId || currentSessionId;

            const args = ['-p', message, '--output-format', 'stream-json', '--verbose', '--dangerously-skip-permissions'];
            if (resumeId) args.push('--resume', resumeId);

            const env = { ...process.env };
            if (systemPrompt) env.CLAUDE_CODE_SYSTEM_PROMPT = systemPrompt;
            if (process.env.CLAUDE_MD_PATH) env.CLAUDE_MD_PATH = process.env.CLAUDE_MD_PATH;

            const child = spawn('claude', args, { env, stdio: ['ignore', 'pipe', 'pipe'] });

            const logFile = path.join(os.tmpdir(), `agenthub-claude-${Date.now()}.log`);
            const cleanLogFile = path.join(os.tmpdir(), `agenthub-claude-clean-${Date.now()}.log`);
            const logStream = fs.createWriteStream(logFile);
            const cleanLogStream = fs.createWriteStream(cleanLogFile);
            cleanLogStream.write('﻿');
            process.stderr.write(`[claude-adapter] Log file: ${logFile}\n`);

            function sendTextDelta(delta) {
                process.stderr.write(`[claude-adapter] sendText len=${delta.length}: ${delta.slice(0, 60)}\n`);
                sendText(delta);
                cleanLogStream.write(delta);
                forwardedCount++;
            }

            let buffer = '';
            let receivedCount = 0;
            let forwardedCount = 0;

            child.stdout.on('data', (chunk) => {
                logStream.write(chunk);
                buffer += chunk.toString();
                const lines = buffer.split('\n');
                buffer = lines.pop();

                for (const l of lines) {
                    if (!l.trim()) continue;
                    let event;
                    try { event = JSON.parse(l); }
                    catch { continue; }
                    receivedCount++;

                    try {
                        switch (event.type) {
                            case 'stream_event':
                                if (event.delta?.text) sendTextDelta(event.delta.text);
                                if (event.delta?.partial_json) sendTextDelta(event.delta.partial_json);
                                break;
                            case 'content_block_start':
                                if (event.content_block?.type === 'text' && event.content_block?.text)
                                    sendTextDelta(event.content_block.text);
                                break;
                            case 'content_block_stop': break;
                            case 'assistant': {
                                const blocks = event.message?.content;
                                if (blocks && Array.isArray(blocks)) {
                                    for (const block of blocks) {
                                        if (block.type === 'text' && block.text)
                                            sendTextDelta(block.text);
                                        else if (block.type === 'tool_use')
                                            sendNow('tool_call', { toolName: block.name, toolInput: block.input, toolId: block.id });
                                    }
                                }
                                break;
                            }
                            case 'content_block_delta':
                                if (event.delta?.text) sendTextDelta(event.delta.text);
                                break;
                            case 'tool_use':
                                sendNow('tool_call', { toolName: event.name, toolInput: event.input, toolId: event.id });
                                break;
                            case 'tool_result':
                                sendNow('tool_result', { toolId: event.tool_use_id, content: event.content });
                                break;
                            case 'user': {
                                const userBlocks = event.message?.content;
                                if (userBlocks && Array.isArray(userBlocks)) {
                                    for (const block of userBlocks) {
                                        if (block.type === 'tool_result')
                                            sendNow('tool_result', { toolId: block.tool_use_id, content: block.content });
                                    }
                                }
                                break;
                            }
                            case 'system': case 'init':
                                if (event.session_id && !currentSessionId) currentSessionId = event.session_id;
                                break;
                            case 'result': break;
                            default: {
                                const textLike = event.text || event.content || event.delta?.text;
                                if (textLike && typeof textLike === 'string' && textLike.length < 5000)
                                    sendTextDelta(textLike);
                            }
                        }
                    } catch (processingError) {
                        process.stderr.write('[claude-adapter] Dispatch error: ' + processingError.message + '\n');
                    }
                }
            });

            child.stderr.on('data', (chunk) => {
                logStream.write(chunk);
                process.stderr.write('[claude] ' + chunk);
            });

            let finalEventSent = false;
            function sendFinal(type, payload = {}) {
                if (finalEventSent) return;
                finalEventSent = true;
                sendNow(type, payload);
            }

            child.on('close', (code) => {
                // Drain remaining queue before sending final events
                function flushThenFinish() {
                    if (sendQueue.length > 0) {
                        drainQueue();
                        setTimeout(flushThenFinish, 15);
                        return;
                    }
                    logStream.end();
                    cleanLogStream.end();
                    process.stderr.write(`[claude-adapter] received=${receivedCount} forwarded=${forwardedCount} sessionId=${currentSessionId || 'none'}\n`);
                    if (currentSessionId) sendNow('session_info', { sessionId: currentSessionId, conversationId });
                    if (buffer.trim() && !finalEventSent) sendTextDelta(buffer);
                    sendFinal('done', { exitCode: code });
                    rl.close();
                    process.exit(0);
                }
                flushThenFinish();
            });

            child.on('error', (err) => {
                logStream.end();
                cleanLogStream.end();
                const hint = err.code === 'ENOENT' ? ' — "claude" not found. Install Claude Code CLI.' : '';
                sendNow('error', { message: 'Failed to start claude: ' + err.message + hint });
                sendFinal('done', { exitCode: 1 });
                rl.close();
                process.exit(1);
            });

            const title = `AgentHub — ${agentName || 'Claude Code'}`;
            if (process.platform === 'win32') {
                spawn('cmd', ['/c', 'start', title, 'powershell', '-NoExit', '-Command',
                    `chcp 65001 > $null; $Host.UI.RawUI.WindowTitle = '${title}'; Write-Host 'AgentHub — ${agentName || 'Claude Code'}'; Get-Content -Path '${cleanLogFile}' -Encoding UTF8 -Wait`],
                    { stdio: 'ignore', detached: true });
            } else if (process.platform === 'darwin') {
                spawn('osascript', ['-e', `tell app "Terminal" to do script "tail -f ${cleanLogFile}; exit"`],
                    { stdio: 'ignore', detached: true });
            } else {
                spawn('x-terminal-emulator', ['-e', `tail -f ${cleanLogFile}`],
                    { stdio: 'ignore', detached: true });
            }
            break;
        }

        case 'stop_request': {
            sendNow('done', { delta: '', stopped: true });
            break;
        }

        case 'terminate': {
            process.exit(0);
        }

        default:
            process.stderr.write('[claude-adapter] Unknown frame type: ' + frame.type + '\n');
    }
});

rl.on('close', () => process.exit(0));
