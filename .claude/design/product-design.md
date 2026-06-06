
AgentHub

多 Agent 协同工作平台

产品设计文档

版本: v1.0

日期: 2026-05-21


# 1. 产品概述


## 1.1 产品定位

AgentHub 是一个多 Agent 协同工作平台，以 IM 聊天作为核心交互范式，让用户像使用飞书/微信一样，通过新建对话、发送消息的方式与不同的 AI Agent 进行交互。平台统一接入多种主流 Agent 产品（Claude Code、OpenCode 等），提供单聊、群聊协作、产物内联预览能力。


## 1.2 核心价值主张

IM 原生体验: 以熟悉的聊天界面降低 AI Agent 使用门槛

多 Agent 协同: 群聊模式下的智能任务拆解与并行调度

统一接入: 适配器层屏蔽不同 Agent 平台的 API 差异

产物闭环: 从对话生成到预览编辑到部署发布的完整链路

开放生态: 支持用户自建 Agent，自定义 System Prompt 与工具集


## 1.3 产品边界

AgentHub 定位于 Agent 协同的门户平台，不负责底层 LLM 推理、不实现 Agent 内核算法，而是聚焦于:

多 Agent 间的通信、调度与状态管理

IM 聊天体验的产品化封装

产物的存储、预览与版本管理

用户身份认证与工作区多租户隔离


# 2. 用户角色


## 2.1 角色定义


| 角色 | 标识 | 典型场景 | 核心需求 |
| --- | --- | --- | --- |
| 普通用户 | Member | 日常使用 Agent 完成编码、文档编写等任务 | 便捷发起对话、查看历史、管理产物 |
| 高级用户 | Admin | 自建 Agent、配置工具集、管理团队工作区 | Agent 配置、技能绑定、工作区管理 |
| 系统管理员 | Global Admin | 全局配置、模型管理、系统运维 | Provider 配置、系统监控、故障处理 |


## 2.2 用户旅程

典型用户核心流程:

登录平台 → 进入工作区

浏览对话列表（左侧边栏），查看历史会话

新建对话 → 选择 Agent（单聊）或 @ 多个 Agent（群聊）

输入消息 → Agent 回复 → 查看内联产物预览

对产物进行二次编辑

管理对话历史: 置顶、新建


# 3. 信息架构


## 3.1 页面结构

AgentHub 采用标准企业 IM 的三栏布局结构:


| 区域 | 组件 | 功能说明 |
| --- | --- | --- |
| 左侧边栏 | ConversationSidebar | 会话列表: 展示用户的所有对话，支持搜索、新建、置顶、归档。按最近活跃时间排序。对话项显示 Agent 头像、会话标题、最后消息摘要、未读标记。 |
| 中间主区域 | ChatView | 消息流展示: 当前对话的消息列表，支持文本、代码块、图片、文件附件、Diff 视图、网页预览卡片等消息类型。顶部显示对话标题和参与者信息。 |
| 右侧面板 | DetailPanel | 上下文面板(可收起): 展示当前对话的 Agent 成员、产物列表、pinned 消息、对话设置。点击产物卡片时展开为全屏预览/编辑器。 |


## 3.2 导航路由

基于 Vue Router 的 hash 路由设计:


| 路由路径 | 页面 | 说明 |
| --- | --- | --- |
| /login | LoginView | 登录页: 账号密码登录、初始化引导 |
| /chat | ChatView (主页面) | IM 主界面: 左侧边栏 + 中间聊天区 + 右侧面板 |
| /chat/:conversationId | ChatView (指定会话) | 打开指定会话的聊天视图 |
| /agents | AgentManageView | Agent 管理页: 查看、创建、编辑 Agent |
| /agents/:id | AgentDetailView | Agent 详情页: System Prompt、工具集、能力配置 |
| /artifacts | ArtifactListView | 产物库: 所有 Agent 产物的统一浏览入口 |
| /settings | SettingsView | 用户设置: 个人信息、偏好、API Key 管理 |


## 3.3 组件层级

核心组件树结构:

App.vue

PageTransition > RouterView

ChatView

ConversationSidebar (会话列表、搜索、新建)

ChatArea (消息流、Composer)

DetailPanel (Agent信息、产物、设置)

AgentManageView

ArtifactListView / ArtifactDetailView


# 4. 核心功能设计


## 4.1 IM 聊天式交互


### 4.1.1 对话列表

左侧会话列表是用户管理所有对话的核心入口，设计要点:

列表排序: 按最近活跃时间降序排列，刚收到新消息的对话自动置顶

列表项信息: Agent 头像（单聊）/ 组合头像（群聊）、对话标题、最后一条消息摘要、时间戳、未读标记

操作入口: 新建对话按钮（置顶）、搜索框（支持按标题/Agent名称搜索）、右键菜单（置顶/取消置顶、归档、删除）

状态标识: 正在生成中的对话显示呼吸灯动画；有未读消息的对话标题加粗

归档管理: 已归档对话收纳至折叠区域，支持取消归档恢复


### 4.1.2 单聊模式

1v1 与单个 Agent 对话，适合有明确目标的任务。用户在新建对话时从 Agent 列表中选择一个目标 Agent，进入聊天界面后直接发送消息。系统自动创建新的 conversation 并关联该 Agent。

交互流程:

点击 [新建对话] 按钮

弹出 Agent 选择器：展示可用 Agent 列表（头像、名称、能力标签、描述）

选择目标 Agent → 创建新会话 → 自动聚焦输入框

发送消息 → Agent 开始生成回复（SSE 流式展示）

回复完成后，消息气泡显示完整内容 + 操作按钮（复制、重新生成、引用回复）


### 4.1.3 群聊模式

群聊是 AgentHub 的核心差异化功能。在一个对话中 @ 多个 Agent，由 Orchestrator 主 Agent 自动协调分工，多个 Agent 像群聊成员一样依次回复各自的产出。

交互流程:

新建群聊 → 选择多个 Agent 加入（至少 2 个）

Orchestrator 自动作为群聊的协调者加入

用户发送任务消息（可用 @ 指定特定 Agent 执行子任务）

Orchestrator 分析用户意图，拆解任务，在聊天流中发送 「任务拆解计划」消息卡片

各子 Agent 依次（或并行）执行分配的任务，各自回复产出

Orchestrator 在所有子 Agent 完成后发送 「汇总报告」消息卡片

用户可对任意子 Agent 的回复进行单独追问或修改

群聊消息气泡与单聊的区别:

每条 Agent 消息气泡头部显示该 Agent 的小头像 + 名称，便于区分

Orchestrator 的系统消息使用特殊样式（蓝色左边框）以区别于普通 Agent 消息

子 Agent 的产出消息支持内联预览卡片


### 4.1.4 消息类型

支持的富媒体消息类型:


| 消息类型 | 表现形式 | 交互操作 |
| --- | --- | --- |
| 文本消息 | Markdown 渲染的富文本 | 支持代码高亮、表格、列表、链接。可选中文本后在聊天框中描述修改意图进行对话式编辑 |
| 代码块 | 语法高亮的代码区域 | 一键复制、Apply Diff（应用代码变更到产物文件） |
| Diff 卡片 | 代码差异对比视图 (unified/split) | 一键 Apply、展开全屏对比、拒绝变更 |
| 网页预览卡片 | 内联 iframe 预览缩略图 | 点击展开全屏预览、新标签页打开、复制链接 |
| 文件附件 | 文件图标 + 文件名 + 大小 | 下载、预览（支持图片/PDF/文档） |
| 图片消息 | 缩略图展示 | 点击放大预览、复制图片 |
| 部署状态卡片(可选) | 部署进度/结果卡片 | 查看部署日志、访问部署地址、回滚 |


### 4.1.5 上下文管理

自动上下文: 聊天历史自动作为 Agent 的对话上下文传递（基于 mateclaw-dev 的 conversation 历史压缩机制）

Pin 消息: 右键消息可选择 Pin，被 Pin 的消息作为长期上下文持久注入后续对话

上下文窗口提示: 当对话历史接近模型上下文窗口上限时，在输入框上方显示提示

清空上下文: 提供「清空上下文」操作（保留消息历史但重置 Agent 的上下文状态）


## 4.2 主 Agent 协调器 (Orchestrator)


### 4.2.1 设计目标

Orchestrator 是群聊模式下负责多 Agent 协同调度的核心组件。基于 mateclaw-dev 已有的 DelegateAgentTool 和 SubagentRegistry 机制进行扩展，实现智能任务拆解、并行调度、失败降级和结果聚合。


### 4.2.2 架构设计

Orchestrator 复用 mateclaw-dev 的子 Agent 委托体系:

Orchestrator 本身是一个特殊的 Agent（agentType = "orchestrator"），使用增强的 Plan-Execute 模式

任务拆解阶段: Orchestrator 调用 LLM 分析用户意图，生成任务拆解计划（Plan），存储到 mate_plan / mate_sub_plan 表

任务分派阶段: 通过 DelegateAgentTool 的 delegateParallel 方法并行调度子 Agent

执行监控阶段: 通过 SubagentRegistry 实时追踪子 Agent 的状态（运行中/已完成/失败）

结果聚合阶段: 收集所有子 Agent 的产出，生成汇总报告并作为消息返回聊天流


### 4.2.3 调度策略


| 策略 | 适用场景 | 实现方式 |
| --- | --- | --- |
| 串行调度 | 任务有明确的前后依赖关系 | 使用 DelegateAgentTool.delegateToAgent，按 Plan 步骤顺序执行，前一步的输出作为后一步的输入 |
| 并行调度 | 任务可独立并行执行 | 使用 DelegateAgentTool.delegateParallel，最多同时 8 个子任务并发执行 |
| 条件调度 | 需要根据中间结果动态决策后续步骤 | Orchestrator 评估中间结果后调整 Plan，重新分派 |
| 人工确认调度 | 关键步骤需用户审批（如部署操作） | 复用 ToolGuard 审批机制，子 Agent 工具调用暂停等待审批 |


### 4.2.4 失败处理

单 Agent 失败: 自动重试 1 次，仍失败则标记该步骤为失败，不影响其他并行任务

全局失败策略: 用户可配置（fail-fast 立即停止 / fail-tolerant 继续其他任务）

代码冲突: 两个子 Agent 修改同一文件时，Orchestrator 检测冲突并提示用户手动选择或合并

超时控制: 默认 300 秒超时（可配置），超时后优雅中断子 Agent


## 4.3 多 Agent 接入


### 4.3.1 统一适配器层

基于 mateclaw-dev 已有的多 Provider 架构进行扩展，通过统一的适配器接口屏蔽不同 Agent 平台的 API 差异:

Claude Code: 通过 Anthropic Messages API / Claude Code OAuth 接入

OpenCode: 通过 OpenAI 兼容 API 接入

自定义 Agent: 用户通过对话式创建，设定 System Prompt + 工具集

适配器接口: 统一的消息发送、流式接收、上下文管理、工具调用协议


### 4.3.2 Agent 生命周期管理

每个 Agent 在平台中的生命周期:

创建: 从模板创建 / 从 ClawHub 安装 / 对话式自建

配置: 设定名称、头像、System Prompt、工具集、Provider 偏好

验证: 测试连接、试运行

发布: 在工作区内启用，出现在用户可选 Agent 列表中

监控: 运行时状态追踪、Token 用量统计

迭代: 修改配置、更新 System Prompt、增减工具

归档/删除: 停用后归档或永久删除


### 4.3.3 Agent 能力展示

在对话列表和 Agent 选择器中，每个 Agent 以"联系人"身份呈现:

Agent 头像（首字母头像 / 自定义图片）

Agent 名称 + 简短描述

能力标签: 如 [编码] [文档] [网页设计] [数据分析]

在线状态: 可用 / 忙碌中（正在执行任务）/ 不可用


## 4.4 产物预览与编辑


### 4.4.1 产物类型

Agent 对话过程中生成的各类产出物统称为"产物"（Artifact）:


| 产物类型 | 预览方式 | 编辑/操作 |
| --- | --- | --- |
| 网页(HTML) | 内联 iframe 预览卡片 → 全屏预览 | 在线代码编辑器修改 → 实时预览刷新 → 一键部署 |
| 代码文件 | 代码高亮视图 + Diff 视图 | 对话式局部修改 → Apply Diff → 版本对比 |
| 文档(Markdown/PDF) | Markdown 渲染 / PDF 预览 | Markdown 源码编辑 → 实时渲染预览 |
| PPT | 幻灯片缩略图浏览 | 下载 / 对话式修改某一页 / 调整样式 |
| 图片 | 缩略图 → 全屏预览 | 下载 / 对话式修改（如"把背景改成蓝色"） |


### 4.4.2 版本管理

每次对产物的修改自动生成新版本，支持:

版本历史列表: 按时间倒序展示所有版本，标注版本号和变更摘要

版本 Diff: 任意两个版本之间的差异对比（代码/文本）

版本回滚: 一键恢复到任意历史版本

版本标签: 为重要版本打标签（如 v1.0、release 等）


### 4.4.3 部署发布

对网页类产物支持一键部署:

部署触发: 在产物预览页面点击 [部署] 按钮

部署流程: 选择部署目标 → 自动打包 → 上传 → 返回访问地址

部署状态: 部署中（进度展示）→ 已部署（显示 URL + 访问按钮）→ 部署失败（错误日志）

部署管理: 查看部署历史、下线已部署产物、绑定自定义域名（可选）


# 5. 界面设计规范


## 5.1 设计原则

延续 AIagent_frontend 的 Apple 风格设计语言: SF Pro 字体、毛玻璃效果、大圆角、柔和阴影

以内容为中心: 减少装饰元素，突出消息流和产物内容

渐进式披露: 常用操作触手可及，高级功能通过二级入口访问

即时反馈: 所有操作提供即时视觉反馈（加载动画、状态提示、过渡动效）


## 5.2 色彩系统

在 AIagent_frontend 现有变量基础上扩展:


| 色彩角色 | 色值 | 使用场景 |
| --- | --- | --- |
| 主背景 | #F5F5F7 | 页面主背景色 |
| 卡片/气泡背景 | #FFFFFF | 消息气泡、卡片、面板背景 |
| 主文字 | #1D1D1F | 正文、标题 |
| 主色调 | #2E75B6 | 链接、按钮、选中状态、Orchestrator 消息边框 |
| 成功 | #34C759 | 部署成功、任务完成 |
| 警告 | #FF9500 | 超时、审批等待 |
| 错误 | #FF3B30 | 生成失败、部署失败 |


## 5.3 字体与排版

字体: system-ui, -apple-system, "SF Pro Text", "PingFang SC", sans-serif

消息正文: 14px / 1.6 行高

标题: H1 28px / H2 22px / H3 18px

辅助文字: 12px / 颜色 #999999

代码: "SF Mono", "Fira Code", monospace, 13px


## 5.4 布局规范

左侧边栏宽度: 320px（可拖拽调整 260px - 400px）

主聊天区: flex-grow 自适应，最大宽度 900px（消息内容区）

右侧面板: 360px（默认收起，可切换展开）

间距系统: 4px 基础单元（4, 8, 12, 16, 20, 24, 32, 48 px）

圆角: sm=8px, md=14px, lg=20px, full=999px


# 6. 交互流程


## 6.1 单聊完整流程

时序说明:

用户点击 [新建对话] → 前端展示 Agent 选择器弹窗

前端调用 GET /api/v1/agents?enabled=true 获取可选 Agent 列表

用户选择 Agent → 前端创建新路由 /chat/new?agentId=xxx

用户发送第一条消息 → 前端调用 POST /api/v1/chat/stream 建立 SSE 连接

后端根据 agentId 创建 conversationId，启动 Agent 执行

SSE 事件流: text（文本增量）、tool_call（工具调用）、tool_result（工具结果）、done（完成）

前端实时渲染消息气泡，完成后显示操作按钮

后续消息在同一 conversationId 下继续发送，维持上下文连续性


## 6.2 群聊 Orchestrator 调度流程

群聊模式下的完整调度时序:

用户创建群聊，选择多个 Agent → 创建 GroupConversation

用户发送任务消息 → 消息路由到 Orchestrator Agent

Orchestrator LLM 分析意图 → 生成 Plan（任务拆解计划）

前端展示「任务拆解计划」消息卡片（包含任务步骤、分配的 Agent、预估耗时）

Orchestrator 通过 DelegateAgentTool 并行/串行分派任务给子 Agent

前端通过 SSE 接收 delegation_progress 事件，实时更新各子 Agent 执行状态

子 Agent 回复作为独立消息气泡展示在聊天流中（带 Agent 头像和名称）

所有子 Agent 完成后，Orchestrator 生成汇总报告并发送到聊天流


## 6.3 产物编辑流程

Agent 回复中包含产物（如 HTML 代码）→ 自动生成预览卡片

用户点击预览卡片 → 右侧面板展开为全屏预览

用户在聊天框中输入修改请求（如"把标题颜色改成红色"）

消息自动关联当前产物上下文 → 发送给 Agent

Agent 返回 Diff → 前端渲染 Diff 视图卡片

用户点击 [Apply] → 产物更新为新版本

用户可在版本历史中查看和回滚


# 7. 技术架构概述


## 7.1 前后端交互架构

AgentHub 采用前后端分离架构，基于 mateclaw-dev 已有的 350+ API 端点进行扩展。


| 层级 | 技术选型 | 说明 |
| --- | --- | --- |
| 前端 | Vue 3 + Pinia + Vite + Axios | 基于 AIagent_frontend 迭代，新增会话列表、群聊、产物预览等模块 |
| API 网关 | Spring Boot REST API | /api/v1/* 端点，JWT 认证，统一响应格式 |
| 通信协议 | SSE (流式) + REST (同步) | 聊天使用 SSE 流式推送，CRUD 使用 REST API |
| 业务层 | Spring Boot Service Layer | AgentService, ConversationService, OrchestratorService, ArtifactService |
| Agent 运行时 | Spring AI + StateGraph + AgentGraphBuilder | ReAct / Plan-Execute 两种 Agent 模式，多 Provider 故障转移链 |
| 持久层 | MyBatis-Plus + Flyway (MySQL/H2) | ORM 映射 + 数据库迁移管理 |
| 文件存储 | 本地文件系统 / 对象存储 | 产物文件、附件、工作区文件的存储与读取 |


## 7.2 关键扩展点

基于 mateclaw-dev 现有架构，需要扩展的关键模块:

新增 OrchestratorService: 群聊任务拆解、分派、聚合的核心服务

新增 ArtifactService: 产物存储、版本管理、预览生成

扩展 ConversationService: 支持群聊会话类型、@ 提及解析

扩展 DelegateAgentTool: 支持群聊上下文传递、结果回流到聊天流

新增 SSE 事件类型: delegation_progress、orchestrator_plan、artifact_preview

扩展 AgentEntity: 支持 Agent 头像、能力标签、在线状态


## 7.3 前端扩展规划

在 AIagent_frontend 现有代码基础上的迭代路径:

新增 stores: conversationStore（会话管理）、orchestratorStore（协调器状态）、artifactStore（产物管理）

新增 views: AgentManageView、ArtifactListView、ArtifactDetailView、SettingsView

新增 components: ConversationSidebar、DetailPanel、GroupChatSetup、PlanCard、DiffViewCard、ArtifactPreview

重构 ChatView: 从单 Agent 对话升级为支持多 Agent 群聊的消息流

SSE 客户端升级: 从简单 fetch 升级为 EventSource + 断线重连机制

路由扩展: 新增 /agents、/artifacts、/settings 路由


# 8. API 接口规划

以下为需要新增的 API 端点，用于支持多 Agent 协同平台的核心功能。所有端点复用 mateclaw-dev 的 JWT 认证和统一响应格式。


## 8.1 群聊会话管理


| 方法 | URL | 说明 | 认证 |
| --- | --- | --- | --- |
| GET | /conversations/group | 获取群聊会话列表 | JWT |
| POST | /conversations/group | 创建群聊会话 | JWT |
| PUT | /conversations/group/{id}/members | 更新群聊成员 | JWT |
| DELETE | /conversations/group/{id}/members/{agentId} | 移除群聊成员 | JWT |


## 8.2 Orchestrator 任务管理


| 方法 | URL | 说明 | 认证 |
| --- | --- | --- | --- |
| GET | /orchestrator/tasks/{conversationId} | 获取群聊的任务拆解计划 | JWT |
| GET | /orchestrator/tasks/{taskId}/status | 查询子任务执行状态 | JWT |
| POST | /orchestrator/tasks/{taskId}/retry | 重试失败的子任务 | JWT |
| POST | /orchestrator/tasks/{taskId}/cancel | 取消子任务 | JWT |


## 8.3 产物管理


| 方法 | URL | 说明 | 认证 |
| --- | --- | --- | --- |
| GET | /artifacts?conversationId=&type= | 获取产物列表 | JWT |
| GET | /artifacts/{id} | 获取产物详情 | JWT |
| GET | /artifacts/{id}/versions | 获取版本历史 | JWT |
| POST | /artifacts/{id}/versions/{versionId}/restore | 回滚到指定版本 | JWT |
| POST | /artifacts/{id}/deploy | 部署产物 | JWT |
| GET | /artifacts/{id}/deploy/status | 查询部署状态 | JWT |


## 8.4 Agent 市场与自建


| 方法 | URL | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | /agents/custom | 对话式创建自定义 Agent | JWT |
| PUT | /agents/{id}/avatar | 上传 Agent 头像 | JWT |
| GET | /agents/{id}/stats | Agent 使用统计 | JWT |


# 9. 非功能需求


## 9.1 性能要求

SSE 流式推送首字节延迟 < 2 秒

消息列表滚动加载，支持万级消息量的流畅浏览（虚拟滚动）

产物预览加载时间 < 3 秒

并行 Agent 调度最大并发数: 8


## 9.2 安全要求

复用 mateclaw-dev 的 JWT 认证机制（HMAC-SHA256, 24 小时过期）

复用 ToolGuard 工具安全审批机制，危险操作需用户确认

工作区级别的多租户隔离

XSS 防护: 消息内容使用现有的 html.js 转义工具


## 9.3 可用性要求

断线重连: SSE 连接断开后自动重连（指数退避策略）

离线提示: 网络断开时显示连接状态提示

错误恢复: Agent 执行失败时提供一键重试

响应式设计: 适配 1366px+ 桌面端屏幕
