# AgentHub (Loom)

多智能体协作平台 — 让多个 AI Agent 协同工作，编排任务、管理产物、流式对话。

## 核心功能

### 对话模式

- **单聊模式** — 从 Agent 列表中选择一个 Agent 进行一对一对话，每个 Agent 在聊天列表中显示为独立的"联系人"，有头像、名称、能力标签
- **群聊模式** — 一个对话中包含多个 Agent。用户发送消息后，内置编排 Agent 自动根据子 Agent 的能力分配任务，通过 `@Agent名` 指定子 Agent 完成，用户也可手动 `@` 指定。支持并行调度与失败降级，子 Agent 完成后 Orchestrator 聚合产出并在聊天流中汇报结果。支持手动 Pin 关键信息为长期上下文
- **多会话并行** — 同时开启多个对话窗口，分别与不同 Agent 交流不同任务

### Agent 体系

- **自建 Agent** — 用户可自定义 Agent，设定 System Prompt + Skills，灵活配置能力边界
- **外部 Agent 接入** — 通过 ACP 协议接入本地 Claude Code 和 OpenCode，将外部编码 Agent 纳入协作
- **Skills 技能** — 自动扫描全局 Skill，附带快捷命令，开箱即用
- **文件上传解析** — 支持各类文件上传解析，内置编排 Agent 和子 Agent 均可读取文件内容

### 交互体验

- **SSE 流式对话** — 实时推送文本增量、工具调用、任务计划等事件，思考链 / 工具调用 / 回答分阶段呈现
- **产物内联** — Agent 的回复不仅是文字，还可内联展示代码产物，用户可直接在聊天流中预览和操作
- **工作区** — 可选择本地文件目录作为工作区，Agent 在工作区中读写文件


## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 (Composition API) · Pinia · Vue Router · Naive UI · Vite |
| 后端 | Java 21 · Spring Boot 3.5 · Spring AI 1.1 · MyBatis Plus · Flyway |
| 数据库 | H2 (开发) · MySQL 8.0 (生产) |
| 通信 | SSE 流式推送 · WebSocket |
| 认证 | Spring Security + JWT |
| 构建 | Maven 多模块 (后端) · npm/Vite (前端) |
| 部署 |  npm/Vite (前端) |

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
└── docs/superpowers/          AI 协作记录，superpowers产出的文档
```

## 快速开始

### 环境要求

- Node.js 18+ / pnpm
- Java 21+
- Maven 3.9+
- MySQL 8.0 (生产环境需要)
- Claude Code (外部 Agent 接入)
- OpenCode (外部 Agent 接入)

注意：确保 claude code / opencode 在系统的 PATH 中

### 任务分配Agent API Key

编辑 `mateclaw-dev/mateclaw-server/src/main/resources/agent/agents.yml`：

```yaml
ai:
  agent:
    config:
      tables:
        Agent01:
          module:
            ai-api:
              base-url: https://api.deepseek.com
              api-key:  sk-your-deepseek-api-key      # ← 替换为你的 DeepSeek API Key
              completions-path: v1/chat/completions
              embeddings-path: v1/embeddings
            chat-model:
              model: deepseek-v4-flash                 
```

### 启动后端服务

开发模式（H2 内嵌数据库）
默认使用 H2 文件数据库，**无需安装 MySQL**，开箱即用：

```bash
cd mateclaw-dev
mvn spring-boot:run -pl mateclaw-server
```

启动后访问：`http://localhost:18088`
- Swagger UI：`http://localhost:18088/swagger-ui.html`
- H2 控制台（默认关闭）：`http://localhost:18088/h2-console`

生产模式（MySQL）

**先创建数据库**（可选，连接串带了 `createDatabaseIfNotExist=true` 会自动创建）：

```sql
CREATE DATABASE mateclaw CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 启动前端服务

```bash
cd AIagent_frontend
npm install
npm run dev
```

访问 `http://localhost:3000`，Vite 会将 `/api` 代理到后端的 `http://127.0.0.1:18088`。


## 页面路由

| 路径 | 页面 | 说明 |
|---|---|---|
| `/login` | 登录 | JWT 认证，已登录自动跳转聊天页 |
| `/chat/:conversationId?` | 对话 | 核心页面。左侧 Agent 联系人列表（头像、名称、能力标签），右侧 SSE 流式聊天区。支持单聊、群聊（多个 Agent `@` 协作，Orchestrator 自动分配任务，并行调度 + 失败降级）、多会话并行切换。聊天流内联展示代码产物，支持 Pin 关键信息为长期上下文 |
| `/agents` | 智能体管理 | Agent 列表，创建自定义 Agent（System Prompt + Skills + 工具集），启停、删除。支持 ACP 协议接入本地 Claude Code / OpenCode 外部 Agent |
| `/agents/:id` | 智能体详情 | 编辑 System Prompt、绑定模型供应商与偏好、技能/工具/MCP 服务器挂载、ACP 端点配置 |
| `/skills` | 技能管理 | 全局 Skill 浏览、按类型筛选、启停、手动扫描同步，Skill 附带快捷命令 |
| `/settings` | 设置 | 用户信息、工作区设置、模型供应商配置、渠道接入管理 |

## API 概览

完整 API 文档见 [.claude/specs/API.md](.claude/specs/API.md)。

主要接口模块：

- **认证与用户** — `POST /api/v1/auth/login` 登录 · `GET/POST /api/v1/auth/users` 用户管理 · `GET/PUT /api/v1/auth/me` 当前用户 · `GET/POST/DELETE /api/v1/auth/tokens` PAT 个人令牌
- **工作空间** — `GET/POST /api/v1/workspaces` 工作空间与成员管理，RBAC 四级角色（viewer → member → admin → owner）
- **Agent** — `GET/POST/PUT/DELETE /api/v1/agents` Agent CRUD · 头像上传 · 技能/工具/MCP 绑定 · ACP 端点 · 模型供应商偏好
- **对话** — `GET/POST /api/v1/conversations` 对话管理 · `POST /api/v1/conversations/group` 创建群组对话（选成员 Agent）· 群组成员增删
- **聊天** — `POST /api/v1/chat/stream` SSE 流式聊天 · `POST /api/v1/chat/upload` 文件上传解析 · `POST /api/v1/chat/{convId}/stop` 停止生成 · `/interrupt` 中断
- **编排** — `GET /api/v1/orchestrator/tasks` 任务分解跟踪 · 委派记录 · 任务重试/取消 · 并行调度状态
- **技能** — `GET/POST/PUT/DELETE /api/v1/skills` 技能 CRUD · 启停切换 · 重新扫描 · 全局扫描 `POST /api/v1/skills/global/scan`

所有响应均为统一 JSON 格式：`{ "code": 200, "message": "success", "data": {} }`。聊天接口使用 SSE（`text/event-stream`），支持 `Last-Event-ID` 断线重连。

## 开发约定

详见 [.claude/CLAUDE.md](.claude/CLAUDE.md)，核心原则：

- **简单优先** — 最少的代码解决问题，不添加推测性功能
- **手术式修改** — 只修改必须改的，不重构无关代码
- **目标驱动** — 为每个任务定义可验证的成功标准
- 前端路由使用 hash 模式，改动前端时确保 SSE 流不会被意外断开
- 数据库变更通过 Flyway 迁移脚本管理
