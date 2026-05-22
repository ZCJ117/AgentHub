# AgentHub — 多 Agent 协同工作平台

## 项目概述

AgentHub 是一个多 Agent 协同工作平台，以 IM 聊天为核心交互范式。用户像使用飞书/微信一样，通过新建对话、发送消息的方式与不同 AI Agent 交互，支持单聊、群聊协作、产物内联预览与一键部署。

### 开发策略

- **前端**：在 `AIagent_frontend` 现有 Vue 3 初始产品基础上迭代为最终的多 Agent 协同平台前端，不依赖 `mateclaw-dev` 项目的前端部分（mateclaw-ui / mateclaw-webchat）。
- **后端**：在 `mateclaw-dev` 的 Spring Boot 3 后端代码基础上增删修改，复用已有的 Agent 管理、会话管理、SSE 流式推送、子 Agent 委托（DelegateAgentTool）等核心能力，按需扩展群聊、Orchestrator 调度、产物管理等模块。

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 前端 | Vue 3 + Composition API (`<script setup>`) + Pinia + Vue Router (hash) + Vite + Axios | Vue 3.4 / Vite 5 |
| 后端 | Spring Boot 3 + MyBatis-Plus + Spring AI + Flyway | Java 21 / Spring Boot 3.5 |
| 数据库 | MySQL 8.0（生产） / H2（开发） | — |
| 认证 | JWT (HMAC-SHA256) + BCrypt | — |

## 目录结构

```
D:\code\Loom\
├── AIagent_frontend/         # 前端 (Vue 3 + Pinia + Vite)
│   ├── src/
│   │   ├── api/              # Axios 封装 (client.js, agent.js, chat.js, session.js)
│   │   ├── assets/styles/    # CSS 变量、基础样式、动画
│   │   ├── components/       # 组件
│   │   │   ├── chat/         # AgentSelector, ChatArea, Composer, MessageBubble ...
│   │   │   ├── layout/       # TopBar, UserPill
│   │   │   ├── login/        # LoginForm, HeroSection
│   │   │   └── common/       # AppButton, AppModal, BrandMark, 动画组件
│   │   ├── composables/      # useCookie, useTextareaAutosize, useScrollReveal ...
│   │   ├── router/           # 路由: /login, /chat, 默认→/chat
│   │   ├── stores/           # Pinia: auth.js, agent.js, chat.js
│   │   ├── utils/            # cookie.js, time.js, html.js (XSS)
│   │   └── views/            # ChatView.vue, LoginView.vue
│   └── vite.config.js        # dev 端口 3000, /api→127.0.0.1:8091
│
├── mateclaw-dev/             # 后端 (Spring Boot 3 Maven 多模块)
│   ├── pom.xml               # 父 POM, revision=1.4.0-SNAPSHOT
│   ├── mateclaw-server/      # 主服务模块 (~400+ Java 文件)
│   │   └── src/main/
│   │       ├── java/vip/mate/ # 代码 (agent/, workspace/, skill/, tool/, llm/, ...)
│   │       └── resources/db/migration/  # Flyway: h2/ + mysql/ (V1~V119)
│   ├── mateclaw-plugin-api/  # 插件 SDK (5 个文件)
│   └── mateclaw-plugin-sample/
│
├── 主要功能文档.txt           # 完整功能需求规格
├── api.md                    # mateclaw-dev API 文档 (350+ 端点, /api/v1/)
├── 产品设计文档.docx          # 产品设计 (9 章, 含界面/交互/API规划)
└── 数据库设计文档.docx        # 数据库设计 (8 章, 3表扩展 + 9张新表)
```

## 开发命令

### 前端 (AIagent_frontend)
```bash
cd AIagent_frontend
npm install           # 安装依赖
npm run dev           # 启动开发服务器 (端口 3000, API 代理到 127.0.0.1:8091)
npm run build         # 生产构建 → dist/
npm run preview       # 预览生产构建
```

### 后端 (mateclaw-dev)
```bash
cd mateclaw-dev
mvnw clean compile    # 编译
mvnw spring-boot:run  # 启动 (默认端口 18088, Swagger: http://localhost:18088/swagger-ui.html)
mvnw test             # 运行测试
```

## 当前状态与设计决策

**项目处于设计阶段。** AIagent_frontend 为初始产品（仅单聊），mateclaw-dev 为原始代码库。

### 核心架构决策

1. **Orchestrator 基于已有 DelegateAgentTool 扩展**
   mateclaw-dev 已有 `DelegateAgentTool`（支持 `delegateToAgent` 串行 / `delegateParallel` 并行 / `delegateAsync` 异步）和 `SubagentRegistry`。在此基础上新增群聊会话模式、LLM 驱动的自动任务拆解、聊天流内联结果聚合，而非独立新建调度引擎。

2. **Agent 适配器层复用已有 Provider 架构**
   已在 `AgentGraphBuilder` 中支持多协议（DashScope Native / OpenAI Compatible / Anthropic Messages / Claude Code OAuth / Gemini Native），Claude Code 和 OpenCode 均通过已有 Provider 机制接入。

3. **前端在 AIagent_frontend 上迭代**
   从当前 3 个 Pinia store → 扩展为 conversationStore、orchestratorStore、artifactStore。从 2 个路由 → 扩展为 /chat/:id、/agents、/artifacts、/settings。ChatView 从单 Agent → 多 Agent 群聊。

### 关键扩展点（尚未实现）

| 模块 | 现有基础 | 需要新增/扩展 |
|------|----------|--------------|
| Agent | `mate_agent` 表, AgentGraphBuilder | +avatar_url, +capability_tags, agent_type="orchestrator" |
| 会话 | `mate_conversation` 表 (单聊), 已有 parent_conversation_id 委托链 | +conversation_type, group 模式, archived, pinned_at |
| 消息 | `mate_message` 表, SSE 流式推送 | +message_type 枚举, reply_to_id, sender_agent_id |
| 群聊 | — | mate_group_conversation, mate_group_member (两张新表) |
| 调度 | DelegateAgentTool, SubagentRegistry, mate_plan | mate_orchestrator_task, mate_orchestrator_assignment (两张新表) |
| 产物 | WorkspaceFileService (仅 memory docs) | mate_artifact, mate_artifact_version, mate_deploy_record (三张新表) |
| Pin | — | mate_message_pin (新表) |
| 反馈 | — | mate_message_reaction (新表) |

## 架构约定

### 前端

- **设计语言**：Apple 风格（SF Pro 字体、毛玻璃效果、大圆角 14~20px、柔阴影）
- **色彩变量**：定义在 `src/assets/styles/variables.css`，背景 #f5f5f7 / 文字 #1d1d1f / 主色 #000000
- **状态管理**：Pinia Composition API 风格 (`defineStore` 第二个参数函数返回 state/computed/actions)
- **API 层**：Axios 实例，响应拦截器自动解包 `response.data.data`，`code !== '0000'` 视为错误
- **认证**：Cookie-based (`ai_agent_login`)，login 操作在 Pinia auth store 中硬编码校验
- **XSS 防护**：消息内容通过 `utils/html.js` 的 `escapeHtml()` 转义
- **路由守卫**：`requiresAuth` / `requiresGuest`，检查 auth store 的 `isLoggedIn`

### 后端

- **API 前缀**：`/api/v1/`，统一响应格式 `{ code, message, data }`
- **认证**：JWT Bearer Token（`Authorization: Bearer <token>`），24 小时过期
- **工作区隔离**：工作区级别的端点需要 `X-Workspace-Id` header
- **数据库迁移**：Flyway，版本号递增，H2 和 MySQL 双套脚本同版本号
- **Agent 运行时**：`BaseAgent` 抽象类，两种模式 ReAct / Plan-Execute，通过 `AgentGraphBuilder` 构建
- **子 Agent 委托**：最大深度 3 层，支持 spawn-pause 运营控制，ThreadLocal DelegationContext
- **ToolGuard**：模式匹配规则 + 审批流程，Guardians: CredentialExposure, FilePath, FileWrite, ShellCommand

## 文档索引

| 文档 | 路径 | 用途 |
|------|------|------|
| 功能需求 | `主要功能文档.txt` | 完整功能清单、AI 协作规范要求 |
| API 文档 | `api.md` | 350+ 端点参考、统一响应格式、角色权限 |
| 产品设计 | `产品设计文档.docx` | 界面设计、交互流程、API 规划、非功能需求 |
| 数据库设计 | `数据库设计文档.docx` | 表结构、索引、迁移策略、DDL 参考 |
| 后端代码 | `mateclaw-dev/` | Spring Boot 3 后端原始代码 |
| 前端代码 | `AIagent_frontend/` | Vue 3 前端初始产品 |

## 禁止事项

- **当前阶段不要修改任何后端代码**（mateclaw-dev）——仅阅读分析
- **不要修改数据库 schema** —— 设计文档已生成，迁移脚本待实施阶段再写
- 前端代码可基于 `AIagent_frontend` 正常迭代开发
