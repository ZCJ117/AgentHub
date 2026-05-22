# AgentHub Frontend

多 Agent 协同工作平台前端 — 基于 Vue 3 + Pinia + Naive UI + Vite。

## 快速启动

```bash
# 安装依赖
npm install

# 启动开发服务器 (端口 3000)
npm run dev

# 生产构建
npm run build

# 预览生产构建
npm run preview
```

## 前置条件

- Node.js 18+
- 后端服务运行在 `http://localhost:18088`
- 开发时前端通过 Vite proxy 将 `/api` 请求转发到后端

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.4 | Composition API (`<script setup>`) |
| Pinia | 2 | 状态管理 |
| Vue Router | 4 | Hash 路由 |
| Vite | 5 | 构建工具 |
| Naive UI | 2 | 组件库（Apple 风格主题） |
| Axios | 1 | HTTP 客户端 |
| marked | 18 | Markdown 渲染 |
| DOMPurify | 3 | HTML 安全净化 |

## 目录结构

```
src/
├── api/                    # Axios 封装 + API 函数
│   ├── client.js           # JWT 拦截器、响应解包、401 处理
│   ├── auth.js             # POST /auth/login, GET /auth/me
│   ├── agents.js           # Agent CRUD
│   ├── chat.js             # SSE 流式聊天 (POST /chat/stream)
│   ├── conversations.js    # 会话 CRUD、置顶、归档、群聊
│   ├── messages.js         # 消息详情、重新生成、反馈
│   ├── orchestrator.js     # (通过 client 直接调用)
│   ├── artifacts.js        # (通过 client 直接调用)
│   └── workspaces.js       # GET /workspaces
│
├── stores/                 # Pinia Composition API
│   ├── auth.js             # JWT 登录/登出、token 生命周期
│   ├── workspace.js        # 工作区自动选择
│   ├── agent.js            # Agent 列表/详情
│   ├── conversation.js     # 会话列表、激活、置顶、归档
│   ├── chat.js             # SSE 流式消息、发送/停止/重新生成
│   ├── orchestrator.js     # 任务拆解、委派进度追踪
│   └── artifact.js         # 产物 CRUD、版本、部署
│
├── router/
│   └── index.js            # 路由: /login, /chat/:id?, /agents, /artifacts, /settings
│
├── views/
│   ├── LoginView.vue       # 登录页
│   ├── ChatView.vue        # IM 主界面 (三栏布局)
│   ├── AgentManageView.vue # Agent 列表管理
│   ├── AgentDetailView.vue # Agent 详情/新建/编辑
│   ├── ArtifactListView.vue # 产物库
│   ├── ArtifactDetailView.vue # 产物详情 (版本/预览/部署)
│   └── SettingsView.vue    # 用户设置
│
├── components/
│   ├── chat/               # 聊天组件
│   │   ├── ConversationSidebar.vue  # 会话列表侧栏
│   │   ├── ChatArea.vue             # 消息流 + Composer
│   │   ├── MessageBubble.vue        # 多类型消息气泡
│   │   ├── Composer.vue             # 输入框 + 发送/停止
│   │   ├── PlanCard.vue             # Orchestrator 任务计划卡片
│   │   ├── DiffViewCard.vue         # 代码 Diff 视图
│   │   ├── ArtifactPreviewCard.vue  # 产物内联预览卡片
│   │   ├── StatusBar.vue            # SSE 连接状态
│   │   └── ChatEmpty.vue            # 空对话引导
│   ├── layout/             # 布局组件
│   │   ├── TopBar.vue              # 顶部导航栏
│   │   ├── UserPill.vue            # 用户头像+角色
│   │   └── DetailPanel.vue         # 右侧上下文面板
│   ├── agent/
│   │   └── AgentSelector.vue       # Agent 选择器 (单聊/群聊)
│   ├── common/             # 通用 UI 组件 (保留原有)
│   └── login/              # 登录页组件 (保留原有)
│
├── composables/            # 可复用逻辑
│   ├── useSSE.js           # fetch + ReadableStream SSE 客户端
│   ├── useMarkdown.js      # marked + DOMPurify 渲染
│   ├── useTextareaAutosize.js
│   └── ... (保留原有)
│
├── utils/
│   ├── token.js            # JWT localStorage 存取
│   ├── html.js             # XSS 转义
│   ├── time.js             # 时间格式化
│   └── cookie.js           # Cookie 工具
│
└── assets/styles/
    ├── variables.css        # CSS 变量 (Apple 风格色彩)
    ├── naive-theme.js       # Naive UI 主题覆盖
    ├── base.css
    ├── animations.css
    └── transitions.css
```

## 设计语言

Apple 风格，SF Pro 字体，毛玻璃效果，大圆角 (14px)，柔和阴影。

| 色彩角色 | 色值 |
|----------|------|
| 主背景 | #F5F5F7 |
| 卡片/气泡 | #FFFFFF |
| 主文字 | #1D1D1F |
| 主色调 | #2E75B6 |
| 成功 | #34C759 |
| 警告 | #FF9500 |
| 错误 | #FF3B30 |

## 路由

| 路径 | 页面 | 认证 |
|------|------|------|
| `/login` | 登录页 | 游客 |
| `/chat` | IM 主界面（新对话） | JWT |
| `/chat/:id` | 指定会话 | JWT |
| `/agents` | Agent 管理 | JWT |
| `/agents/:id` | Agent 详情 | JWT |
| `/artifacts` | 产物库 | JWT |
| `/artifacts/:id` | 产物详情 | JWT |
| `/settings` | 用户设置 | JWT |

## API 代理

开发模式下 Vite 将 `/api` 请求代理到后端：

```
http://localhost:3000/api/v1/*  →  http://127.0.0.1:18088/api/v1/*
```

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `VITE_API_BASE` | 无 (使用 proxy) | API 基础 URL |

参见 `.env.development` 和 `.env.production`。
