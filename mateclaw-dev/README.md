# AgentHub

多智能体协作平台 — 编排多个 AI Agent 协同工作，流式对话，可扩展技能与工具体系。

## 概述

AgentHub 是一个多智能体协作平台，让多个 AI Agent 在同一工作空间中协同解决复杂任务。它提供流式聊天界面、Agent 群组编排、产物管理和可扩展的能力体系。

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
| 后端 | Spring Boot 3.5 · Spring AI 1.1 |
| 持久化 | MyBatis Plus · Flyway |
| 数据库 | H2（开发）· MySQL 8.0（生产）|
| 认证 | Spring Security + JWT + PAT 个人令牌 |
| 通信 | SSE 流式推送 · WebSocket |
| 构建 | Maven 多模块（后端）· npm/Vite（前端）|

## 项目结构

```
Loom/
├── AIagent_frontend/         Vue 3 前端 SPA
├── mateclaw-dev/             MateClaw 后端引擎（Spring Boot）
│   ├── mateclaw-server/      主模块，API 入口
│   ├── mateclaw-common/      公共层：异常、统一响应、工具类
│   ├── mateclaw-domain/      领域层：Entity、Mapper、Service
│   ├── mateclaw-plugin-api/  插件 SDK
│   ├── mateclaw-plugin-sample/ 参考插件
│   ├── adapters/             ACP 适配器（Claude Code、OpenCode）
│   └── docker/               SearXNG 配置
├── docker-compose.yml
└── .env.example
```

## 快速开始

### 环境要求

- Java 21+ / Maven 3.9+
- Node.js 20+ / npm 10+
- MySQL 8.0
- Claude Code (外部 Agent 接入)
- OpenCode (外部 Agent 接入)

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

## API 参考

Base URL: `http://localhost:18088/api/v1/`

统一响应格式：

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
| 编排 | `/api/v1/orchestrator` | 任务分解、委派跟踪 |
| 技能 | `/api/v1/skills` | 技能定义与绑定 |
| 工具 | `/api/v1/tools` | 工具注册与启停 |


## SSE 流式事件

聊天接口通过 Server-Sent Events 推送，事件类型：

| 事件 | 说明 |
|---|---|
| `text` | 增量文本输出 |
| `tool_call` | Agent 调用工具 |
| `tool_result` | 工具执行结果 |
| `orchestrator_plan` | 群组编排任务计划 |
| `artifact_preview` | 产物预览 |
| `error` | 生成过程出错 |

支持通过 `Last-Event-ID` 请求头实现断线重连恢复。

## License

[Apache License 2.0](LICENSE)
