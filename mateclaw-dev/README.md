# MateClaw

AI Agent harness built on Spring Boot — multi-agent orchestration, extensible skills, streaming communication.

[![Java](https://img.shields.io/badge/Java-21+-blue.svg?logo=openjdk)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-Apache--2.0-red.svg)](LICENSE)

[[中文](README_zh.md)]

## Overview

MateClaw is a Spring Boot backend that powers multi-agent collaboration. It manages AI agents, conversations, artifacts, and orchestration — exposing a REST API consumed by frontend SPAs, IM channel adapters, and external automation.

- **Agent runtime** — ReAct + Plan-and-Execute patterns, group orchestration with task decomposition and parallel delegation
- **Streaming** — SSE-based real-time chat with reconnect support, staged output (thinking / tool / answer)
- **Capability extension** — Skills, Tools, MCP servers, and ACP endpoints — each independently bindable per agent
- **Multi-tenant** — Workspaces with RBAC (viewer → member → admin → owner), JWT auth, PAT support
- **Artifact management** — Versioned outputs with diff viewing, rollback, and deployment
- **Memory** — Post-conversation fact extraction, scheduled consolidation, dreaming workflows
- **Provider pool** — Multi-vendor model routing with health tracking and automatic failover
- **Channels** — DingTalk, Feishu, Slack, Discord, Telegram, and more
- **Enterprise** — Flyway migrations, Cron distributed lock, HMAC webhook signing, audit trail

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.5 · Spring AI 1.1 |
| Persistence | MyBatis Plus · Flyway |
| Database | H2 (dev) · MySQL 8.0 (prod) |
| Auth | Spring Security + JWT |
| Communication | SSE streaming · WebSocket |
| Build | Maven multi-module |

## Project Structure

```
mateclaw-dev/
├── mateclaw-server/         Spring Boot 3.5 main module
├── mateclaw-plugin-api/     Java SDK for capability plugins
├── mateclaw-plugin-sample/  Reference plugin implementation
├── arther-agent/            Agent engine sub-module
├── adapters/                ACP adapters (Claude Code, OpenCode)
├── docker/                  Docker supporting files (SearXNG config)
├── assets/                  Architecture diagrams and images
├── docker-compose.yml
└── .env.example
```

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- MySQL 8.0 (or use Docker Compose)

### Run with H2 (dev)

```bash
cd mateclaw-server
mvn spring-boot:run           # http://localhost:18088
```

Default profile uses H2 in-memory database — no external DB needed. API base path is `/api/v1/`.

### Run with Docker Compose

```bash
cp .env.example .env          # edit passwords in .env
docker compose up -d          # http://localhost:18080
```

Starts three containers: MySQL 8.0 · SearXNG · MateClaw Server. Flyway runs migrations automatically on startup.

### Frontend

The web frontend is in the sibling `AIagent_frontend/` directory (Vue 3 + Naive UI + Vite):

```bash
cd ../AIagent_frontend
npm install && npm run dev    # http://localhost:3000
```

## API Reference

Base URL: `http://localhost:18088/api/v1/`

All endpoints use a unified JSON response format:

```json
{ "code": 200, "message": "success", "data": {} }
```

### Core Endpoints

| Module | Base Path | Description |
|---|---|---|
| Auth | `/api/v1/auth` | Login, user CRUD, PAT management |
| Workspaces | `/api/v1/workspaces` | Multi-tenant workspace and member management |
| Agents | `/api/v1/agents` | Agent CRUD, skill/tool binding, templates |
| Conversations | `/api/v1/conversations` | Direct and group conversations, messages |
| Chat | `/api/v1/chat` | SSE streaming, file upload, stop/interrupt |
| Artifacts | `/api/v1/artifacts` | Versioned outputs, diff, deploy, tags |
| Orchestrator | `/api/v1/orchestrator` | Task decomposition, assignment tracking |
| Skills | `/api/v1/skills` | Skill definitions and binding |
| Tools | `/api/v1/tools` | Tool registry and enabling |
| MCP | `/api/v1/mcp/servers` | MCP server management |
| ACP | `/api/v1/acp/endpoints` | External coding agent endpoints |
| Models | `/api/v1/models` | Provider and model configuration |
| Security | `/api/v1/security` | ToolGuard rules, approval, audit |
| Memory | `/api/v1/memory/{agentId}` | Memory emergence, dreaming, facts |
| Dashboard | `/api/v1/dashboard` | Activity overview, token usage |
| System | `/api/v1/system` | Health check, settings |

Full API specification is in `.claude/specs/API.md`.

## SSE Streaming

Chat endpoints support Server-Sent Events with the following event types:

| Event | Description |
|---|---|
| `text` | Incremental text delta |
| `tool_call` | Agent invokes a tool |
| `tool_result` | Tool execution result |
| `orchestrator_plan` | Group orchestration task plan |
| `delegation_progress` | Sub-agent execution progress |
| `artifact_preview` | Generated artifact preview |
| `done` | Turn completed (finish reason, token usage) |
| `error` | Error during generation |

Reconnection is supported via `lastEventId`.

## License

[Apache License 2.0](LICENSE)
