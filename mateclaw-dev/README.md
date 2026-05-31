# MateClaw（太一）

基于 Spring Boot 的 AI Agent 运行引擎 — 多智能体编排、可扩展技能体系、流式通信。

[![Java](https://img.shields.io/badge/Java-21+-blue.svg?logo=openjdk)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-Apache--2.0-red.svg)](LICENSE)

[[English](README.md)]

## 概述

MateClaw 是一个 Spring Boot 后端引擎，为多智能体协作平台提供核心能力。它管理 AI Agent、对话、产物和任务编排，通过 REST API 为前端 SPA、IM 渠道适配器和外部自动化提供服务。

- **Agent 运行时** — ReAct + Plan-and-Execute 模式，群组编排，任务分解与并行委派
- **流式通信** — 基于 SSE 的实时聊天，支持断线重连，分阶段输出（思考 / 工具 / 回答）
- **能力扩展** — 技能（Skills）、工具（Tools）、MCP 服务器、ACP 端点，每个 Agent 独立绑定
- **多租户** — 工作空间 + RBAC（viewer → member → admin → owner），JWT 认证，PAT 个人令牌
- **产物管理** — 版本化输出，支持 Diff 对比、版本回滚和一键部署
- **记忆系统** — 对话后事实提取、定时整理、Dreaming 工作流
- **供应商池** — 多厂商模型路由，健康追踪，自动故障转移
- **多渠道** — 钉钉、飞书、Slack、Discord、Telegram 等
- **企业级** — Flyway 迁移、Cron 分布式锁、HMAC Webhook 签名、审计日志

## 技术栈

| 层 | 技术 |
|---|---|
| 框架 | Spring Boot 3.5 · Spring AI 1.1 |
| 持久化 | MyBatis Plus · Flyway |
| 数据库 | H2（开发）· MySQL 8.0（生产）|
| 认证 | Spring Security + JWT |
| 通信 | SSE 流式推送 · WebSocket |
| 构建 | Maven 多模块 |

## 项目结构

```
mateclaw-dev/
├── mateclaw-server/         Spring Boot 3.5 主模块
├── mateclaw-plugin-api/     插件 SDK（Java API）
├── mateclaw-plugin-sample/  参考插件实现
├── arther-agent/            Agent 引擎子模块
├── adapters/                ACP 适配器（Claude Code, OpenCode）
├── docker/                  Docker 辅助文件（SearXNG 配置）
├── assets/                  架构图等资源
├── docker-compose.yml
└── .env.example
```

## 快速开始

### 环境要求

- Java 21+
- Maven 3.9+
- MySQL 8.0（使用 Docker Compose 则无需手动安装）

### 开发模式（H2）

```bash
cd mateclaw-server
mvn spring-boot:run           # http://localhost:18088
```

默认 profile 使用 H2 内存数据库，无需外部依赖。API 基路径为 `/api/v1/`。

### Docker Compose 部署

```bash
cp .env.example .env          # 编辑 .env 设置密码
docker compose up -d          # http://localhost:18080
```

启动三个容器：MySQL 8.0 · SearXNG · MateClaw Server。Flyway 自动执行数据库迁移。

### 前端

Web 前端位于同级目录 `AIagent_frontend/`（Vue 3 + Naive UI + Vite）：

```bash
cd ../AIagent_frontend
npm install && npm run dev    # http://localhost:3000
```

## API 参考

Base URL: `http://localhost:18088/api/v1/`

所有接口均使用统一 JSON 响应格式：

```json
{ "code": 200, "message": "success", "data": {} }
```

### 核心接口

| 模块 | 路径 | 说明 |
|---|---|---|
| 认证 | `/api/v1/auth` | 登录、用户管理、PAT 令牌 |
| 工作空间 | `/api/v1/workspaces` | 多租户工作空间与成员管理 |
| Agent | `/api/v1/agents` | Agent 增删改查、技能/工具绑定 |
| 对话 | `/api/v1/conversations` | 单聊、群聊、消息管理 |
| 聊天 | `/api/v1/chat` | SSE 流式、文件上传、停止/中断 |
| 产物 | `/api/v1/artifacts` | 版本化产物、Diff、部署、标签 |
| 编排 | `/api/v1/orchestrator` | 任务分解、委派跟踪 |
| 技能 | `/api/v1/skills` | 技能定义与绑定 |
| 工具 | `/api/v1/tools` | 工具注册与启停 |
| MCP | `/api/v1/mcp/servers` | MCP 服务器管理 |
| ACP | `/api/v1/acp/endpoints` | 外部编码 Agent 接入 |
| 模型 | `/api/v1/models` | 供应商与模型配置 |
| 安全 | `/api/v1/security` | ToolGuard 规则、审批、审计 |
| 记忆 | `/api/v1/memory/{agentId}` | 记忆涌现、Dreaming、事实管理 |
| 面板 | `/api/v1/dashboard` | 活动概览、Token 用量 |
| 系统 | `/api/v1/system` | 健康检查、设置 |

完整 API 文档见 `.claude/specs/API.md`。

## SSE 流式事件

聊天接口支持 Server-Sent Events，事件类型如下：

| 事件 | 说明 |
|---|---|
| `text` | 增量文本输出 |
| `tool_call` | Agent 调用工具 |
| `tool_result` | 工具执行结果 |
| `orchestrator_plan` | 群组编排任务计划 |
| `delegation_progress` | 子 Agent 执行进度 |
| `artifact_preview` | 产物预览 |
| `done` | 本轮完成（含 finishReason、token 用量）|
| `error` | 生成过程出错 |

支持通过 `lastEventId` 重连恢复。

## License

[Apache License 2.0](LICENSE)
