import { spawn } from 'child_process';
import { createInterface } from 'readline';

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

            const args = ['-p', message, '--output-format', 'stream-json', '--verbose'];

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
                process.stderr.write('[claude] ' + chunk);
            });

            child.on('close', (code) => {
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
