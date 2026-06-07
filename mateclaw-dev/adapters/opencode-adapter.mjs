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

// ── Interval send queue ──
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
        process.stderr.write('[opencode-adapter] send error: ' + e.message + '\n');
    }
    sendTimer = setTimeout(drainQueue, 10);
}

function sendNow(type, payload = {}) {
    const frame = JSON.stringify({ type, seq: 0, ts: Date.now(), payload });
    try {
        fs.writeSync(STDOUT_FD, frame + '\n');
    } catch (e) {
        process.stderr.write('[opencode-adapter] send error: ' + e.message + '\n');
    }
}

function sendText(delta) {
    sendQueue.push({ type: 'text', payload: { delta } });
    if (!sendTimer) sendTimer = setTimeout(drainQueue, 0);
}

// Resolve opencode.exe on Windows. npm global installs create .cmd shims in
// the npm bin dir but the actual .exe lives deep in node_modules/. spawn()
// without shell cannot execute .cmd files, so we locate the .exe directly.
function findOpencodeExe() {
    if (process.platform !== 'win32') return null;
    const candidates = [];
    const appData = process.env.APPDATA;
    const localAppData = process.env.LOCALAPPDATA;
    if (appData) candidates.push(path.join(appData, 'npm', 'node_modules', 'opencode-ai', 'bin', 'opencode.exe'));
    if (localAppData) candidates.push(path.join(localAppData, 'npm', 'node_modules', 'opencode-ai', 'bin', 'opencode.exe'));
    candidates.push('C:\\Program Files\\nodejs\\node_modules\\opencode-ai\\bin\\opencode.exe');
    for (const c of candidates) {
        if (fs.existsSync(c)) return c;
    }
    return null;
}

// Handshake
sendNow('ready');

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
            sendNow('ready');
            break;
        }

        case 'chat_request': {
            const message = frame.payload?.message || '';
            const conversationId = frame.payload?.conversationId || 'default';
            const args = ['run', message, '--format', 'json', '--print-logs'];

            const env = { ...process.env };
            if (systemPrompt) env.OPENCODE_SYSTEM_PROMPT = systemPrompt;
            const opencodeBin = process.env.OPENCODE_BIN || findOpencodeExe() || 'opencode';

            const child = spawn(opencodeBin, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });

            const ts = Date.now();
            const logFile = path.join(os.tmpdir(), `agenthub-opencode-${ts}.log`);
            const cleanLogFile = path.join(os.tmpdir(), `agenthub-opencode-clean-${ts}.log`);
            fs.writeFileSync(logFile, '');
            fs.writeFileSync(cleanLogFile, '﻿');
            process.stderr.write(`[opencode-adapter] Log file: ${logFile}\n`);

            const logStream = fs.createWriteStream(logFile, { flags: 'a' });
            const cleanLogStream = fs.createWriteStream(cleanLogFile, { flags: 'a' });

            function sendTextDelta(delta) {
                sendText(delta);
                cleanLogStream.write(delta);
                forwardedCount++;
            }

            let buffer = '';
            let receivedCount = 0;
            let forwardedCount = 0;
            const toolIdToName = {};  // track toolId → toolName for tool_result enrichment

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
                            case 'text':
                                if (event.part?.text) sendTextDelta(event.part.text);
                                break;
                            case 'step_start': case 'step_finish': break;
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
                                        else if (block.type === 'tool_use') {
                                            toolIdToName[block.id] = block.name;
                                            sendNow('tool_call', { toolName: block.name, toolInput: block.input, toolId: block.id });
                                        }
                                    }
                                }
                                if (event.delta?.text) sendTextDelta(event.delta.text);
                                break;
                            }
                            case 'content_block_delta':
                                if (event.delta?.text) sendTextDelta(event.delta.text);
                                break;
                            case 'tool_use':
                                toolIdToName[event.id] = event.name;
                                sendNow('tool_call', { toolName: event.name, toolInput: event.input, toolId: event.id });
                                break;
                            case 'tool_result':
                                sendNow('tool_result', { toolId: event.tool_use_id, toolName: toolIdToName[event.tool_use_id] || '', content: event.content, success: true });
                                break;
                            case 'user': {
                                const userBlocks = event.message?.content;
                                if (userBlocks && Array.isArray(userBlocks)) {
                                    for (const block of userBlocks) {
                                        if (block.type === 'tool_result')
                                            sendNow('tool_result', { toolId: block.tool_use_id, toolName: toolIdToName[block.tool_use_id] || '', content: block.content, success: true });
                                    }
                                }
                                break;
                            }
                            case 'system': case 'init': break;
                            case 'result': break;
                            default: {
                                const textLike = event.text || event.content || event.delta?.text;
                                if (textLike && typeof textLike === 'string' && textLike.length < 5000)
                                    sendTextDelta(textLike);
                            }
                        }
                    } catch (processingError) {
                        process.stderr.write('[opencode-adapter] Dispatch error: ' + processingError.message + '\n');
                    }
                }
            });

            child.stderr.on('data', (chunk) => {
                logStream.write(chunk);
                process.stderr.write('[opencode] ' + chunk);
            });

            let finalEventSent = false;
            function sendFinal(type, payload = {}) {
                if (finalEventSent) return;
                finalEventSent = true;
                sendNow(type, payload);
            }

            child.on('close', (code) => {
                function flushThenFinish() {
                    if (sendQueue.length > 0) {
                        drainQueue();
                        setTimeout(flushThenFinish, 15);
                        return;
                    }
                    logStream.end();
                    cleanLogStream.end();
                    process.stderr.write(`[opencode-adapter] received=${receivedCount} forwarded=${forwardedCount}\n`);
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
                const hint = err.code === 'ENOENT' ? ' — "opencode" not found.' : '';
                sendNow('error', { message: 'Failed to start opencode: ' + err.message + hint });
                sendFinal('done', { exitCode: 1 });
                rl.close();
                process.exit(1);
            });

            const title = `AgentHub — ${agentName || 'OpenCode'}`;
            if (process.platform === 'win32') {
                spawn('cmd', ['/c', 'start', title, 'powershell', '-NoExit', '-Command',
                    `chcp 65001 > $null; $Host.UI.RawUI.WindowTitle = '${title}'; Write-Host 'AgentHub — ${agentName || 'OpenCode'}'; Get-Content -Path '${cleanLogFile}' -Encoding UTF8 -Wait`],
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
            process.stderr.write('[opencode-adapter] Unknown frame type: ' + frame.type + '\n');
    }
});

rl.on('close', () => process.exit(0));
