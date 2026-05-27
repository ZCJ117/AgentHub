import { spawn } from 'child_process';
import { createInterface } from 'readline';
import fs from 'fs';
import path from 'path';
import os from 'os';

const rl = createInterface({ input: process.stdin });

function send(type, payload = {}) {
    const frame = JSON.stringify({ type, seq: 0, ts: Date.now(), payload });
    process.stdout.write(frame + '\n');
}

// Handshake
send('ready');

let agentId = null;
let agentName = null;
let systemPrompt = '';
let currentSessionId = null;  // persisted across chat_request invocations for --resume

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
            send('ready');
            break;
        }

        case 'chat_request': {
            const message = frame.payload?.message || '';
            const conversationId = frame.payload?.conversationId || 'default';
            const resumeId = frame.payload?.sessionId || currentSessionId;

            const args = ['-p', message, '--output-format', 'stream-json', '--verbose'];
            if (resumeId) {
                args.push('--resume', resumeId);
            }

            const env = { ...process.env };
            if (systemPrompt) {
                env.CLAUDE_CODE_SYSTEM_PROMPT = systemPrompt;
            }
            if (process.env.CLAUDE_MD_PATH) {
                env.CLAUDE_MD_PATH = process.env.CLAUDE_MD_PATH;
            }

            const child = spawn('claude', args, {
                env,
                stdio: ['ignore', 'pipe', 'pipe']
            });

            // Create temp log files — raw JSON for debugging, clean text for the terminal window
            const logFile = path.join(os.tmpdir(), `agenthub-claude-${Date.now()}.log`);
            const cleanLogFile = path.join(os.tmpdir(), `agenthub-claude-clean-${Date.now()}.log`);
            const logStream = fs.createWriteStream(logFile);
            const cleanLogStream = fs.createWriteStream(cleanLogFile);
            cleanLogStream.write('﻿'); // UTF-8 BOM for Windows PowerShell compatibility
            process.stderr.write(`[claude-adapter] Log file: ${logFile}\n`);

            function sendText(delta) {
                process.stderr.write(`[claude-adapter] sendText len=${delta.length}: ${delta.slice(0, 60)}\n`);
                send('text', { delta });
                cleanLogStream.write(delta);
                forwardedCount++;
            }

            let buffer = '';
            let receivedCount = 0;
            let forwardedCount = 0;
            child.stdout.on('data', (chunk) => {
                logStream.write(chunk);  // Tee to log file for terminal window
                buffer += chunk.toString();
                const lines = buffer.split('\n');
                buffer = lines.pop();

                for (const l of lines) {
                    if (!l.trim()) continue;

                    let event;
                    try {
                        event = JSON.parse(l);
                    } catch {
                        process.stderr.write('[claude-adapter] JSON parse error: ' + l.slice(0, 200) + '\n');
                        continue;
                    }

                    receivedCount++;

                    try {
                        switch (event.type) {
                            case 'stream_event':
                                if (event.delta?.text) {
                                    sendText(event.delta.text);
                                }
                                if (event.delta?.partial_json) {
                                    sendText(event.delta.partial_json);
                                }
                                break;
                            case 'content_block_start':
                                // Anthropic API: content_block.text for text blocks
                                if (event.content_block?.type === 'text' && event.content_block?.text) {
                                    sendText(event.content_block.text);
                                }
                                break;
                            case 'content_block_stop':
                                // End of a content block — no payload to forward
                                break;
                            case 'assistant': {
                                // Claude Code 2.x format: content blocks inside event.message.content[]
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
                                break;
                            }
                            case 'content_block_delta':
                                // Claude Code 1.x / Anthropic API format
                                if (event.delta?.text) {
                                    sendText(event.delta.text);
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
                            case 'user': {
                                // tool_result may be embedded in a user message with content blocks
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
                                // Capture session_id for --resume on subsequent requests
                                if (event.session_id && !currentSessionId) {
                                    currentSessionId = event.session_id;
                                }
                                break;
                            case 'result':
                                // Final result event — text already forwarded via assistant events
                                break;
                            default: {
                                // Only forward text-like fields from unknown events,
                                // and gate on reasonable size to avoid flooding the SSE channel
                                const textLike = event.text || event.content || event.delta?.text;
                                if (textLike && typeof textLike === 'string' && textLike.length < 5000) {
                                    sendText(textLike);
                                } else if (!textLike) {
                                    process.stderr.write('[claude-adapter] Unhandled event type: ' + event.type + '\n');
                                }
                            }
                        }
                    } catch (processingError) {
                        process.stderr.write('[claude-adapter] Dispatch error for type=' +
                            (event?.type || 'unknown') + ': ' + processingError.message + '\n');
                    }
                }
            });

            child.stderr.on('data', (chunk) => {
                logStream.write(chunk);  // Tee stderr to log file too
                process.stderr.write('[claude] ' + chunk);
            });

            let finalEventSent = false;
            function sendFinal(type, payload = {}) {
                if (finalEventSent) return;
                finalEventSent = true;
                send(type, payload);
            }

            child.on('close', (code) => {
                logStream.end();
                cleanLogStream.end();
                process.stderr.write(`[claude-adapter] received=${receivedCount} forwarded=${forwardedCount} sessionId=${currentSessionId || 'none'}\n`);
                if (currentSessionId) {
                    send('session_info', { sessionId: currentSessionId, conversationId });
                }
                if (buffer.trim() && !finalEventSent) {
                    sendText(buffer);
                }
                sendFinal('done', { exitCode: code });
            });

            child.on('error', (err) => {
                logStream.end();
                cleanLogStream.end();
                const hint = err.code === 'ENOENT'
                    ? ' — the "claude" command was not found. Install Claude Code CLI or add it to your PATH.'
                    : '';
                send('error', { message: 'Failed to start claude: ' + err.message + hint });
                sendFinal('done', { exitCode: 1 });
            });

            // Open a terminal window to tail the clean log file
            const title = `AgentHub — ${agentName || 'Claude Code'}`;
            if (process.platform === 'win32') {
                spawn('cmd', ['/c', 'start', title, 'powershell', '-NoExit', '-Command',
                    `chcp 65001 > $null; $Host.UI.RawUI.WindowTitle = '${title}'; Write-Host 'AgentHub — ${agentName || 'Claude Code'}'; Get-Content -Path '${cleanLogFile}' -Encoding UTF8 -Wait`],
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
            process.stderr.write('[claude-adapter] Unknown frame type: ' + frame.type + '\n');
    }
});

rl.on('close', () => {
    process.exit(0);
});
