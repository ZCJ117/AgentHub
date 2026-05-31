# AgentHub (Loom)

多智能体协作平台 — 让多个 AI Agent 协同工作，编排任务、管理产物、流式对话。

## 核心功能

- **多智能体群聊** — 创建群组对话，Orchestrator Agent 自动分解任务、指派子 Agent、汇总结果。支持 `auto` / `manual` 调度模式和 `fail_fast` / `fail_tolerant` 故障策略
- **SSE 流式对话** — Server-Sent Events 实时推送：文本增量、工具调用、编排计划、产物预览、子智能体进度，支持断线重连
- **Artifact 管理** — Agent 生成的网页、代码、文档等产物支持版本历史、Diff 对比、版本回滚、一键部署
- **技能与工具绑定** — 每个 Agent 可独立绑定 Skill、Tool、MCP Server，能力不交叉泄露
- **多供应商故障转移** — 模型提供商池 + 健康追踪，主模型不可用时自动切换到下一个
- **工作空间多租户** — RBAC 角色体系（viewer → member → admin → owner），JWT 认证，Personal Access Token 支持
- **记忆系统** — Agent 对话后自动提取事实，定时整理，支持 Dreaming 工作流

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 (Composition API) · Pinia · Vue Router · Naive UI · Vite |
| 后端 | Java 21 · Spring Boot 3.5 · Spring AI 1.1 · MyBatis Plus · Flyway |
| 数据库 | H2 (开发) · MySQL 8.0 (生产) |
| 通信 | SSE 流式推送 · WebSocket |
| 认证 | Spring Security + JWT |
| 构建 | Maven 多模块 (后端) · npm/Vite (前端) |
| 部署 | Dockerfile (后端) · npm/Vite (前端) |

## 项目结构

```
Loom/
├── AIagent_frontend/          Vue 3 前端 SPA
│   └── src/
│       ├── views/             页面：Chat, AgentManage, AgentDetail, ArtifactList, ArtifactDetail, Settings, Login
│       ├── stores/            状态：auth, agent, chat, conversation, workspace, artifact, orchestrator
│       ├── api/               API 请求封装
│       ├── components/        通用组件：agent, chat, common, layout, login
│       ├── composables/       组合式函数：useSSE, useMarkdown, 等
│       └── router/            路由 (hash 模式)
├── mateclaw-dev/              Spring Boot 后端 (Maven 多模块)
│   ├── mateclaw-server/       Web 层 — 控制器、WebSocket、SSE，启动类 MateClawApplication
│   ├── mateclaw-domain/       领域层 — 实体、服务、Mapper、业务逻辑
│   ├── mateclaw-common/       公共层 — 统一返回、异常、安全工具
│   ├── adapters/              外部 Agent 适配器 (Claude、OpenCode)
│   └── pom.xml                父 POM
├── .claude/                   项目配置与规格文档
│   ├── CLAUDE.md              编码指南
│   └── specs/                 API 文档 · 产品设计 · 数据库设计
└── docs/                      AI 协作记录
```

## 快速开始

### 环境要求

- Node.js 18+ / pnpm
- Java 21+
- Maven 3.9+
- MySQL 8.0 (生产环境需要)

### 前端开发

```bash
cd AIagent_frontend
npm install
npm run dev                    # http://localhost:3000
```

Vite dev server 自动将 `/api` 代理到 `http://127.0.0.1:18088`。

### 后端开发

```bash
cd mateclaw-dev
mvn spring-boot:run -pl mateclaw-server  # http://localhost:18088
```

API 基路径为 `/api/v1/`。默认使用 H2 内存数据库，无需额外配置。

### Docker 部署

```bash
cd mateclaw-dev/mateclaw-server
docker build -t mateclaw-server .
docker run -p 18088:18088 mateclaw-server
```

详见 `mateclaw-dev/mateclaw-server/Dockerfile`。

## 页面路由

| 路径 | 页面 | 说明 |
|---|---|---|
| `/login` | 登录 | JWT 认证 |
| `/chat/:conversationId?` | 对话 | SSE 流式聊天，支持直接对话和群组对话 |
| `/agents` | 智能体管理 | 创建、配置、启停 Agent |
| `/agents/:id` | 智能体详情 | 系统提示词、模型、技能/工具绑定 |
| `/artifacts` | 产物列表 | 按类型、部署状态筛选 |
| `/artifacts/:id` | 产物详情 | 版本历史、Diff、部署、回滚 |
| `/settings` | 设置 | 用户信息、偏好 |

## API 概览

完整 API 文档见 [.claude/specs/API.md](.claude/specs/API.md)。

主要接口模块：

- `POST /api/v1/auth/login` — 用户认证
- `GET/POST /api/v1/workspaces` — 工作空间管理
- `GET/POST /api/v1/agents` — Agent CRUD 与技能/工具绑定
- `GET/POST /api/v1/conversations` — 对话管理
- `POST /api/v1/conversations/group` — 创建群组对话
- `POST /api/v1/chat/stream` — SSE 流式聊天
- `GET /api/v1/artifacts` — 产物与版本管理
- `GET /api/v1/orchestrator/tasks` — 编排任务监控
- `GET/POST /api/v1/skills` · `/api/v1/tools` · `/api/v1/mcp/servers` — 能力扩展
- `GET /api/v1/models/providers` — 模型供应商管理

所有响应均为统一 JSON 格式：`{ "code": 200, "message": "success", "data": {} }`。

## 开发约定

详见 [.claude/CLAUDE.md](.claude/CLAUDE.md)，核心原则：

- **简单优先** — 最少的代码解决问题，不添加推测性功能
- **手术式修改** — 只修改必须改的，不重构无关代码
- **目标驱动** — 为每个任务定义可验证的成功标准
- 前端路由使用 hash 模式，改动前端时确保 SSE 流不会被意外断开
- 数据库变更通过 Flyway 迁移脚本管理
