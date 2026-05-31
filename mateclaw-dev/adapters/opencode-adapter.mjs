import { spawn, execSync } from 'child_process';
import { createInterface } from 'readline';
import pty from 'node-pty';
import fs from 'fs';
import path from 'path';
import os from 'os';

const rl = createInterface({ input: process.stdin });

// Force unbuffered stdout: set blocking mode and use raw fd writes
if (process.stdout._handle && typeof process.stdout._handle.setBlocking === 'function') {
    process.stdout._handle.setBlocking(true);
}
const STDOUT_FD = process.stdout.fd || 1;

function resolveExe(name) {
    if (name.includes('/') || name.includes('\\')) return name; // already a path
    try {
        const cmd = process.platform === 'win32' ? `where ${name}` : `which ${name}`;
        const result = execSync(cmd, { encoding: 'utf-8', timeout: 5000 });
        const lines = result.trim().split('\n');
        const exe = lines.find(l => l.endsWith('.exe')) || lines[0];
        return exe.trim();
    } catch {
        return name;
    }
}

function send(type, payload = {}) {
    const frame = JSON.stringify({ type, seq: 0, ts: Date.now(), payload });
    try {
        fs.writeSync(STDOUT_FD, frame + '\n');
    } catch (e) {
        process.stderr.write('[opencode-adapter] send error: ' + e.message + '\n');
    }
}

// Handshake
send('ready');

let agentId = null;
let agentName = null;
let systemPrompt = '';

// Strip ANSI escape sequences from PTY output
function stripAnsi(str) {
    return str.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '').replace(/\x1b\][0-9;]*[^\x07]*\x07/g, '');
}

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

            const args = ['run', message, '--format', 'json', '--print-logs'];

            const env = { ...process.env };
            if (systemPrompt) {
                env.OPENCODE_SYSTEM_PROMPT = systemPrompt;
            }

            const opencodeBin = process.env.OPENCODE_BIN || 'opencode';

            // Use PTY so the CLI thinks it's writing to a terminal → no libc block-buffering
            let child;
            try {
                child = pty.spawn(resolveExe(opencodeBin), args, {
                    name: 'xterm-256color',
                    cols: 200,
                    rows: 40,
                    cwd: process.cwd(),
                    env
                });
            } catch (err) {
                process.stderr.write('[opencode-adapter] PTY spawn failed: ' + err.message + ' — falling back to pipe\n');
                child = spawn(opencodeBin, args, {
                    env,
                    stdio: ['ignore', 'pipe', 'pipe']
                });
            }

            // Create temp log files
            const ts = Date.now();
            const logFile = path.join(os.tmpdir(), `agenthub-opencode-${ts}.log`);
            const cleanLogFile = path.join(os.tmpdir(), `agenthub-opencode-clean-${ts}.log`);
            fs.writeFileSync(logFile, '');
            fs.writeFileSync(cleanLogFile, '﻿'); // UTF-8 BOM
            process.stderr.write(`[opencode-adapter] Log file: ${logFile}\n`);

            const logStream = fs.createWriteStream(logFile, { flags: 'a' });
            const cleanLogStream = fs.createWriteStream(cleanLogFile, { flags: 'a' });

            function sendText(delta) {
                send('text', { delta });
                cleanLogStream.write(delta);
                forwardedCount++;
            }

            let buffer = '';
            let receivedCount = 0;
            let forwardedCount = 0;

            // PTY emits 'data' events (merged stdout+stderr); pipe spawn uses child.stdout
            const outputStream = child.stdout || child;
            outputStream.on('data', (chunk) => {
                logStream.write(chunk);
                const clean = stripAnsi(chunk.toString());
                buffer += clean;
                const lines = buffer.split('\n');
                buffer = lines.pop();

                for (const l of lines) {
                    if (!l.trim()) continue;

                    let event;
                    try {
                        event = JSON.parse(l);
                    } catch {
                        // Not JSON — could be stderr output mixed in from PTY
                        continue;
                    }

                    receivedCount++;

                    try {
                        switch (event.type) {
                            case 'text':
                                if (event.part?.text) sendText(event.part.text);
                                break;
                            case 'step_start':
                            case 'step_finish':
                                break;
                            case 'stream_event':
                                if (event.delta?.text) sendText(event.delta.text);
                                if (event.delta?.partial_json) sendText(event.delta.partial_json);
                                break;
                            case 'content_block_start':
                                if (event.content_block?.type === 'text' && event.content_block?.text)
                                    sendText(event.content_block.text);
                                break;
                            case 'content_block_stop':
                                break;
                            case 'assistant': {
                                const blocks = event.message?.content;
                                if (blocks && Array.isArray(blocks)) {
                                    for (const block of blocks) {
                                        if (block.type === 'text' && block.text) {
                                            sendText(block.text);
                                        } else if (block.type === 'tool_use') {
                                            send('tool_call', {
                                                toolName: block.name,
                                                toolInput: block.input,
                                                toolId: block.id
                                            });
                                        }
                                    }
                                }
                                if (event.delta?.text) sendText(event.delta.text);
                                break;
                            }
                            case 'content_block_delta':
                                if (event.delta?.text) sendText(event.delta.text);
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
                            case 'user': {
                                const userBlocks = event.message?.content;
                                if (userBlocks && Array.isArray(userBlocks)) {
                                    for (const block of userBlocks) {
                                        if (block.type === 'tool_result') {
                                            send('tool_result', {
                                                toolId: block.tool_use_id,
                                                content: block.content
                                            });
                                        }
                                    }
                                }
                                break;
                            }
                            case 'system':
                            case 'init':
                                break;
                            case 'result':
                                break;
                            default: {
                                const textLike = event.text || event.content || event.delta?.text;
                                if (textLike && typeof textLike === 'string' && textLike.length < 5000) {
                                    sendText(textLike);
                                }
                            }
                        }
                    } catch (processingError) {
                        process.stderr.write('[opencode-adapter] Dispatch error for type=' +
                            (event?.type || 'unknown') + ': ' + processingError.message + '\n');
                    }
                }
            });

            // For non-PTY spawn, also handle stderr
            if (child.stderr && child.stderr !== child.stdout) {
                child.stderr.on('data', (chunk) => {
                    logStream.write(chunk);
                    process.stderr.write('[opencode] ' + chunk);
                });
            }

            let finalEventSent = false;
            function sendFinal(type, payload = {}) {
                if (finalEventSent) return;
                finalEventSent = true;
                send(type, payload);
            }

            function onClose(code) {
                logStream.end();
                cleanLogStream.end();
                process.stderr.write(`[opencode-adapter] received=${receivedCount} forwarded=${forwardedCount}\n`);
                if (buffer.trim() && !finalEventSent) {
                    sendText(buffer);
                }
                sendFinal('done', { exitCode: code != null ? code : 0 });
                rl.close();
                process.exit(0);
            }

            if (child.onExit) {
                child.onExit(({ exitCode }) => onClose(exitCode));
            } else {
                child.on('close', onClose);
            }

            child.on('error', (err) => {
                logStream.end();
                cleanLogStream.end();
                const hint = err.code === 'ENOENT'
                    ? ' — the "opencode" command was not found. Install OpenCode CLI or add it to your PATH.'
                    : '';
                send('error', { message: 'Failed to start opencode: ' + err.message + hint });
                sendFinal('done', { exitCode: 1 });
                rl.close();
                process.exit(1);
            });

            // Open a terminal window to tail the clean log file
            const title = `AgentHub — ${agentName || 'OpenCode'}`;
            if (process.platform === 'win32') {
                spawn('cmd', ['/c', 'start', title, 'powershell', '-NoExit', '-Command',
                    `chcp 65001 > $null; $Host.UI.RawUI.WindowTitle = '${title}'; Write-Host 'AgentHub — ${agentName || 'OpenCode'}'; Get-Content -Path '${cleanLogFile}' -Encoding UTF8 -Wait`],
                    { stdio: 'ignore', detached: true });
            } else if (process.platform === 'darwin') {
                spawn('osascript', ['-e',
                    `tell app "Terminal" to do script "tail -f ${cleanLogFile}; exit"`],
                    { stdio: 'ignore', detached: true });
            } else {
                spawn('x-terminal-emulator', ['-e', `tail -f ${cleanLogFile}`],
                    { stdio: 'ignore', detached: true });
            }
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
