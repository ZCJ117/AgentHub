
AgentHub

多 Agent 协同工作平台

数据库设计文档

版本: v1.0

日期: 2026-05-21

数据库: MySQL 8.0+


# 1. 设计概述


## 1.1 设计目标

本文档定义 AgentHub 多 Agent 协同工作平台的数据库设计方案。该方案在 mateclaw-dev 项目已有的 35+ 表结构基础上，针对 IM 聊天式交互、群聊协作、Orchestrator 调度和产物管理等新功能进行扩展设计。

设计原则:

最小侵入: 尽可能复用现有表结构，通过新增字段和关联表扩展功能

向后兼容: 所有新增表和字段不影响现有功能的正常运行

数据完整性: 使用外键约束、唯一索引、CHECK 约束确保数据一致性

性能优先: 针对高频查询场景（会话列表、消息历史）设计合理的索引策略


## 1.2 现有数据库概览

mateclaw-dev 现有关键表（V1 ~ V119 迁移）总计约 35 张表，主要分为以下模块:


| 模块 | 核心表 | 说明 |
| --- | --- | --- |
| 用户与认证 | mate_user, mate_personal_access_token | 用户账户、JWT 认证、PAT 管理 |
| 工作区 | mate_workspace, mate_workspace_member | 多租户隔离、成员角色管理 |
| Agent 管理 | mate_agent, mate_agent_skill, mate_agent_tool | Agent 配置、技能绑定、工具绑定 |
| 聊天与会话 | mate_conversation, mate_message | 会话管理、消息存储、上下文历史 |
| 技能与工具 | mate_skill, mate_tool, mate_mcp_server | 技能定义、工具注册、MCP 服务器 |
| 模型配置 | mate_model_config, mate_model_provider | LLM Provider 和模型配置 |
| 任务规划 | mate_plan, mate_sub_plan | Plan-Execute 模式的任务拆解 |
| 安全审批 | mate_tool_approval, mate_tool_guard_rule, mate_tool_guard_config | ToolGuard 工具安全规则与审批 |
| 工作流 | mate_workflow, mate_workflow_run, mate_trigger | 工作流定义与运行 |


# 2. 实体关系总览


## 2.1 核心实体关系

以下为 AgentHub 核心业务实体及其关系（新增实体标有 [新]）:


mate_user (1) ──< (N) mate_workspace_member (N) >── (1) mate_workspace

mate_workspace (1) ──< (N) mate_agent

mate_workspace (1) ──< (N) mate_conversation

mate_conversation (1) ──< (N) mate_message

mate_agent (1) ──< (N) mate_conversation (普通会话)

mate_agent (1) ──< (N) mate_agent_skill (N) >── (1) mate_skill

mate_agent (1) ──< (N) mate_agent_tool (N) >── (1) mate_tool


[新] mate_conversation (1) ──< (N) mate_group_member ── (N) > mate_agent

[新] mate_group_conversation (1) ── (1) mate_conversation (扩展)

[新] mate_orchestrator_task (N) ── (1) mate_group_conversation

[新] mate_orchestrator_assignment (N) ── (1) mate_orchestrator_task

[新] mate_orchestrator_assignment (N) ── (1) mate_agent

[新] mate_artifact (N) ── (1) mate_conversation

[新] mate_artifact (1) ──< (N) mate_artifact_version

[新] mate_artifact (N) ── (1) mate_agent (创建者)

[新] mate_message_pin (N) ── (1) mate_message

[新] mate_message_pin (N) ── (1) mate_conversation


## 2.2 ER 图说明

核心关系概述:

一个工作区 (workspace) 包含多个 Agent、多个会话 (conversation)

一个会话要么是单聊（关联一个 Agent），要么是群聊（通过 mate_group_conversation 扩展）

群聊会话通过 mate_group_member 关联多个 Agent 成员

Orchestrator 为群聊生成任务拆解计划 (mate_orchestrator_task)，每个任务拆分为多个分派 (mate_orchestrator_assignment)

会话中产生的文件产出物存储为产物 (mate_artifact)，每个产物有多个版本 (mate_artifact_version)

消息可以被 Pin (mate_message_pin) 作为长期上下文


# 3. 现有表扩展设计


## 3.1 mate_agent 扩展

为支持 Agent 在 IM 界面中的"联系人"展示和多 Agent 群聊，对 mate_agent 表增加以下字段:


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| avatar_url | VARCHAR(500) | 否 | NULL | Agent 头像 URL |
| capability_tags | VARCHAR(500) | 否 | NULL | 能力标签 JSON 数组, 如 ["编码","文档"] |
| agent_status | VARCHAR(20) | 是 | 'AVAILABLE' | AVAILABLE / BUSY / OFFLINE |
| is_public | TINYINT(1) | 是 | 1 | 是否对工作区全员可见 |
| agent_type | VARCHAR(30) | 是 | 'react' | 扩展值: orchestrator (新增) |


agent_type 枚举值扩展:

react: ReAct 模式 Agent（已有）

plan_execute: Plan-Execute 模式 Agent（已有）

orchestrator [新]: Orchestrator 协调器 Agent，用于群聊任务拆解和调度


## 3.2 mate_conversation 扩展

为支持群聊模式和会话增强功能，对 mate_conversation 表增加以下字段:


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| conversation_type | VARCHAR(20) | 是 | 'direct' | direct (单聊) / group (群聊)。已有数据默认 direct |
| archived | TINYINT(1) | 是 | 0 | 是否已归档 |
| pinned_at | DATETIME | 否 | NULL | 置顶时间，NULL 表示未置顶 |
| last_active_at | DATETIME | 是 | CURRENT_TIMESTAMP | 最近活跃时间，用于排序 |
| last_message_preview | VARCHAR(200) | 否 | NULL | 最后一条消息摘要，用于列表展示 |
| unread_count | INT | 是 | 0 | 当前用户未读消息计数 |


## 3.3 mate_message 扩展

为支持富媒体消息类型和消息交互操作:


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| message_type | VARCHAR(30) | 是 | 'text' | text / code / diff / image / file / preview_card / plan_card / system |
| reply_to_id | BIGINT | 否 | NULL | 引用的消息 ID |
| sender_agent_id | BIGINT | 否 | NULL | 发送消息的 Agent ID（Agent 消息非空，用户消息为空） |
| artifact_refs | VARCHAR(2000) | 否 | NULL | 关联的产物 ID JSON 数组 |
| regenerated_from_id | BIGINT | 否 | NULL | 重新生成时指向原始消息 |


# 4. 新增表设计


## 4.1 群聊会话扩展表 - mate_group_conversation

扩展 mate_conversation 表的群聊属性，采用 1:1 关联方式，仅在 conversation_type = 'group' 时有对应记录。


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 是 | AUTO_INCREMENT | 主键 |
| conversation_id | BIGINT | 是 | FK | 关联 mate_conversation.id, UNIQUE |
| orchestrator_agent_id | BIGINT | 是 | FK | 关联 mate_agent.id, 群聊的 Orchestrator Agent |
| scheduling_mode | VARCHAR(20) | 是 | 'auto' | auto (自动分派) / manual (用户 @ 指定) |
| failure_policy | VARCHAR(30) | 是 | 'fail_tolerant' | fail_fast / fail_tolerant |
| max_parallel_tasks | INT | 是 | 8 | 最大并行任务数 |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 是 | CURRENT_TIMESTAMP ON UPDATE | 更新时间 |


索引设计:

UNIQUE KEY uk_conversation (conversation_id)

KEY idx_orchestrator (orchestrator_agent_id)


## 4.2 群聊成员表 - mate_group_member

记录群聊会话中的 Agent 成员关系。


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 是 | AUTO_INCREMENT | 主键 |
| conversation_id | BIGINT | 是 | FK | 关联 mate_conversation.id |
| agent_id | BIGINT | 是 | FK | 关联 mate_agent.id |
| member_role | VARCHAR(20) | 是 | 'member' | orchestrator / member |
| joined_at | DATETIME | 是 | CURRENT_TIMESTAMP | 加入时间 |


索引设计:

UNIQUE KEY uk_conversation_agent (conversation_id, agent_id)

KEY idx_agent (agent_id)


## 4.3 Orchestrator 任务表 - mate_orchestrator_task

记录 Orchestrator 在群聊中生成的任务拆解计划。复用并扩展 mate_plan 的设计理念。


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 是 | AUTO_INCREMENT | 主键 |
| conversation_id | BIGINT | 是 | FK | 关联 mate_conversation.id |
| message_id | BIGINT | 是 | FK | 触发此次拆解的用户消息 ID |
| title | VARCHAR(500) | 是 | -- | 任务计划标题 |
| plan_json | MEDIUMTEXT | 是 | -- | 任务拆解计划的完整 JSON 结构 |
| status | VARCHAR(30) | 是 | 'pending' | pending / running / completed / failed / cancelled |
| total_assignments | INT | 是 | 0 | 总子任务数 |
| completed_assignments | INT | 是 | 0 | 已完成子任务数 |
| failed_assignments | INT | 是 | 0 | 失败子任务数 |
| started_at | DATETIME | 否 | NULL | 开始执行时间 |
| completed_at | DATETIME | 否 | NULL | 完成时间 |
| aggregation_message_id | BIGINT | 否 | NULL | 聚合汇总报告的消息 ID |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 是 | CURRENT_TIMESTAMP ON UPDATE | 更新时间 |


索引设计:

KEY idx_conversation (conversation_id)

KEY idx_status (status)

KEY idx_created (created_at)


## 4.4 Orchestrator 任务分派表 - mate_orchestrator_assignment

记录每个子任务的具体分派信息（任务→Agent 的映射）。


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 是 | AUTO_INCREMENT | 主键 |
| task_id | BIGINT | 是 | FK | 关联 mate_orchestrator_task.id |
| agent_id | BIGINT | 是 | FK | 被分派的 Agent ID |
| step_order | INT | 是 | -- | 执行顺序（串行调度时使用） |
| execution_mode | VARCHAR(20) | 是 | 'sequential' | sequential / parallel |
| goal | TEXT | 是 | -- | 分配给 Agent 的任务描述 |
| dependency_on | BIGINT | 否 | NULL | 依赖的前置分派 ID（串行时使用） |
| status | VARCHAR(30) | 是 | 'pending' | pending / running / completed / failed / cancelled |
| child_conversation_id | BIGINT | 否 | NULL | 子 Agent 执行对话 ID（复用 parent_conversation_id） |
| result_summary | TEXT | 否 | NULL | 子 Agent 执行结果摘要 |
| error_message | TEXT | 否 | NULL | 失败时的错误信息 |
| retry_count | INT | 是 | 0 | 重试次数 |
| started_at | DATETIME | 否 | NULL | 开始时间 |
| completed_at | DATETIME | 否 | NULL | 完成时间 |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |


索引设计:

KEY idx_task (task_id)

KEY idx_agent (agent_id)

KEY idx_status (status)

KEY idx_child_conv (child_conversation_id)


## 4.5 产物表 - mate_artifact

存储 Agent 对话中生成的所有产出物（代码文件、网页、文档、图片等）。


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 是 | AUTO_INCREMENT | 主键 |
| conversation_id | BIGINT | 是 | FK | 关联 mate_conversation.id |
| message_id | BIGINT | 否 | FK | 首次产出该产物的消息 ID |
| creator_agent_id | BIGINT | 是 | FK | 创建该产物的 Agent ID |
| workspace_id | BIGINT | 是 | FK | 关联 mate_workspace.id |
| artifact_name | VARCHAR(255) | 是 | -- | 产物名称 |
| artifact_type | VARCHAR(30) | 是 | -- | html / code / markdown / pdf / ppt / image / other |
| file_path | VARCHAR(1000) | 否 | NULL | 产物文件存储路径 |
| current_version | INT | 是 | 1 | 当前版本号 |
| deploy_status | VARCHAR(20) | 是 | 'none' | none / deploying / deployed / failed |
| deploy_url | VARCHAR(1000) | 否 | NULL | 部署后的访问 URL |
| tags | VARCHAR(500) | 否 | NULL | 版本标签 JSON 数组 |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 是 | CURRENT_TIMESTAMP ON UPDATE | 更新时间 |


索引设计:

KEY idx_conversation (conversation_id)

KEY idx_creator_agent (creator_agent_id)

KEY idx_workspace (workspace_id)

KEY idx_type (artifact_type)

KEY idx_deploy_status (deploy_status)

KEY idx_created (created_at)


## 4.6 产物版本表 - mate_artifact_version

记录产物的每个历史版本，支持版本回滚和 Diff 对比。


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 是 | AUTO_INCREMENT | 主键 |
| artifact_id | BIGINT | 是 | FK | 关联 mate_artifact.id |
| version_number | INT | 是 | -- | 版本号，自增 |
| message_id | BIGINT | 否 | FK | 触发此版本生成的聊天消息 ID |
| change_summary | VARCHAR(500) | 否 | NULL | 变更摘要 |
| file_path | VARCHAR(1000) | 是 | -- | 该版本文件存储路径 |
| content_hash | VARCHAR(64) | 否 | NULL | 文件内容 SHA-256 哈希，用于去重 |
| diff_from_prev | MEDIUMTEXT | 否 | NULL | 与上一版本的 Unified Diff |
| tag | VARCHAR(50) | 否 | NULL | 版本标签（如 v1.0, release） |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |


索引设计:

UNIQUE KEY uk_artifact_version (artifact_id, version_number)

KEY idx_message (message_id)

KEY idx_tag (tag)


## 4.7 消息 Pin 表 - mate_message_pin

记录用户在对话中 Pin 的消息，作为长期上下文注入后续对话。


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 是 | AUTO_INCREMENT | 主键 |
| message_id | BIGINT | 是 | FK | 关联 mate_message.id |
| conversation_id | BIGINT | 是 | FK | 关联 mate_conversation.id |
| pinned_by | BIGINT | 是 | FK | Pin 操作的用户 ID |
| note | VARCHAR(500) | 否 | NULL | 用户添加的备注 |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | Pin 时间 |


索引设计:

UNIQUE KEY uk_message (message_id) -- 每条消息最多被 Pin 一次

KEY idx_conversation (conversation_id)


## 4.8 消息反馈表 - mate_message_reaction

支持用户对 Agent 消息进行反馈（重新生成、点赞等操作追踪）。


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 是 | AUTO_INCREMENT | 主键 |
| message_id | BIGINT | 是 | FK | 关联 mate_message.id |
| user_id | BIGINT | 是 | FK | 操作用户 ID |
| reaction_type | VARCHAR(30) | 是 | -- | regenerate / like / dislike / apply_diff |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 操作时间 |


索引设计:

KEY idx_message (message_id)

UNIQUE KEY uk_msg_user_type (message_id, user_id, reaction_type)


## 4.9 部署记录表 - mate_deploy_record

记录产物部署的操作历史。


| 字段名 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 是 | AUTO_INCREMENT | 主键 |
| artifact_id | BIGINT | 是 | FK | 关联 mate_artifact.id |
| version_id | BIGINT | 是 | FK | 部署的版本 ID |
| deploy_target | VARCHAR(50) | 是 | -- | 部署目标类型（如 vercel, static_host 等） |
| deploy_url | VARCHAR(1000) | 否 | NULL | 部署后的访问 URL |
| status | VARCHAR(20) | 是 | 'pending' | pending / deploying / deployed / failed |
| error_log | TEXT | 否 | NULL | 部署失败时的错误日志 |
| deployed_by | BIGINT | 是 | FK | 操作用户 ID |
| created_at | DATETIME | 是 | CURRENT_TIMESTAMP | 创建时间 |
| completed_at | DATETIME | 否 | NULL | 完成时间 |


索引设计:

KEY idx_artifact (artifact_id)

KEY idx_status (status)

KEY idx_deployed_by (deployed_by)


# 5. 数据库迁移策略


## 5.1 Flyway 迁移版本规划

所有变更通过 Flyway 迁移脚本管理，遵循 mateclaw-dev 已有的版本号递增规范。建议迁移顺序:

V120: 扩展 mate_agent 表（avatar_url, capability_tags, agent_status, is_public）

V121: 扩展 mate_conversation 表（conversation_type, archived, pinned_at, last_active_at, last_message_preview, unread_count）

V122: 扩展 mate_message 表（message_type, reply_to_id, sender_agent_id, artifact_refs, regenerated_from_id）

V123: 创建 mate_group_conversation 表

V124: 创建 mate_group_member 表

V125: 创建 mate_orchestrator_task 表

V126: 创建 mate_orchestrator_assignment 表

V127: 创建 mate_artifact 表

V128: 创建 mate_artifact_version 表

V129: 创建 mate_message_pin 表

V130: 创建 mate_message_reaction 表

V131: 创建 mate_deploy_record 表


## 5.2 向后兼容性保证

确保现有功能不受影响的措施:

所有新增字段均设置合理的默认值（如 conversation_type 默认 'direct'）

新增字段全部为可空或有默认值，不设置 NOT NULL 无默认值

新增表不依赖现有表结构变更，独立创建

现有 API 的数据查询基于原有字段，新增字段仅用于新功能

迁移脚本同时提供 h2/ 和 mysql/ 两套版本


## 5.3 数据回填策略

mate_conversation.conversation_type: 所有现有数据回填为 'direct'

mate_message.message_type: 所有现有数据根据 content 内容回填（有代码块标记则为 'code'，否则为 'text'）

mate_conversation.last_active_at: 回填为 updated_at 或 created_at


# 6. 关键 DDL 参考


## 6.1 mate_group_conversation

CREATE TABLE mate_group_conversation (

id BIGINT AUTO_INCREMENT PRIMARY KEY,

conversation_id BIGINT NOT NULL,

orchestrator_agent_id BIGINT NOT NULL,

scheduling_mode VARCHAR(20) NOT NULL DEFAULT 'auto',

failure_policy VARCHAR(30) NOT NULL DEFAULT 'fail_tolerant',

max_parallel_tasks INT NOT NULL DEFAULT 8,

created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

UNIQUE KEY uk_conversation (conversation_id),

KEY idx_orchestrator (orchestrator_agent_id),

CONSTRAINT fk_gc_conversation FOREIGN KEY (conversation_id)

REFERENCES mate_conversation(id) ON DELETE CASCADE,

CONSTRAINT fk_gc_orchestrator FOREIGN KEY (orchestrator_agent_id)

REFERENCES mate_agent(id) ON DELETE RESTRICT

);


## 6.2 mate_artifact

CREATE TABLE mate_artifact (

id BIGINT AUTO_INCREMENT PRIMARY KEY,

conversation_id BIGINT NOT NULL,

message_id BIGINT,

creator_agent_id BIGINT NOT NULL,

workspace_id BIGINT NOT NULL,

artifact_name VARCHAR(255) NOT NULL,

artifact_type VARCHAR(30) NOT NULL,

file_path VARCHAR(1000),

current_version INT NOT NULL DEFAULT 1,

deploy_status VARCHAR(20) NOT NULL DEFAULT 'none',

deploy_url VARCHAR(1000),

tags VARCHAR(500),

created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

KEY idx_conversation (conversation_id),

KEY idx_creator_agent (creator_agent_id),

KEY idx_workspace (workspace_id),

KEY idx_type (artifact_type),

CONSTRAINT fk_art_conversation FOREIGN KEY (conversation_id)

REFERENCES mate_conversation(id) ON DELETE CASCADE,

CONSTRAINT fk_art_message FOREIGN KEY (message_id)

REFERENCES mate_message(id) ON DELETE SET NULL,

CONSTRAINT fk_art_agent FOREIGN KEY (creator_agent_id)

REFERENCES mate_agent(id) ON DELETE RESTRICT,

CONSTRAINT fk_art_workspace FOREIGN KEY (workspace_id)

REFERENCES mate_workspace(id) ON DELETE CASCADE

);


## 6.3 mate_orchestrator_task

CREATE TABLE mate_orchestrator_task (

id BIGINT AUTO_INCREMENT PRIMARY KEY,

conversation_id BIGINT NOT NULL,

message_id BIGINT NOT NULL,

title VARCHAR(500) NOT NULL,

plan_json MEDIUMTEXT NOT NULL,

status VARCHAR(30) NOT NULL DEFAULT 'pending',

total_assignments INT NOT NULL DEFAULT 0,

completed_assignments INT NOT NULL DEFAULT 0,

failed_assignments INT NOT NULL DEFAULT 0,

started_at DATETIME,

completed_at DATETIME,

aggregation_message_id BIGINT,

created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

KEY idx_conversation (conversation_id),

KEY idx_status (status),

KEY idx_created (created_at),

CONSTRAINT fk_ot_conversation FOREIGN KEY (conversation_id)

REFERENCES mate_conversation(id) ON DELETE CASCADE,

CONSTRAINT fk_ot_message FOREIGN KEY (message_id)

REFERENCES mate_message(id) ON DELETE CASCADE

);


# 7. 性能与扩展性考量


## 7.1 高频查询索引策略


| 查询场景 | 涉及表 | 索引策略 |
| --- | --- | --- |
| 获取用户的会话列表（按最近活跃排序） | mate_conversation | idx_username_active (username, last_active_at DESC) |
| 分页获取会话消息历史 | mate_message | idx_conversation_created (conversation_id, created_at DESC) |
| 获取对话的 Pin 消息列表 | mate_message_pin | idx_conversation (conversation_id) |
| 获取会话的所有产物 | mate_artifact | idx_conversation (conversation_id) |
| 查看 Orchestrator 任务执行状态 | mate_orchestrator_task + assignment | idx_conversation + idx_task + idx_status |


## 7.2 数据量预估与分区策略

mate_message: 预估每会话平均 50 条消息，万级会话规模下约百万级记录，需按 conversation_id 分片或按月分区

mate_artifact_version: 每个产物预估 5-10 个版本，建议定期归档超过 90 天未活跃产物的旧版本

mate_message 的 content 和 mate_orchestrator_task 的 plan_json 为大字段（MEDIUMTEXT），建议在列表查询时使用 SELECT 排除这些字段


## 7.3 缓存建议

Agent 列表缓存: Agent 配置变更频率低，适合 Redis 缓存（TTL 5 分钟）

会话列表缓存: 按用户缓存会话列表摘要数据（TTL 30 秒）

Orchestrator 任务状态: 通过 SSE 实时推送状态变更，减少轮询查询


# 8. 附录


## 8.1 扩展表汇总


| 表名 | 类型 | 说明 |
| --- | --- | --- |
| mate_group_conversation | 新增 | 群聊会话扩展（1:1 关联 conversation） |
| mate_group_member | 新增 | 群聊成员关系（N:M conversation-agent） |
| mate_orchestrator_task | 新增 | Orchestrator 任务拆解计划 |
| mate_orchestrator_assignment | 新增 | Orchestrator 子任务分派记录 |
| mate_artifact | 新增 | Agent 产出物 |
| mate_artifact_version | 新增 | 产物版本历史 |
| mate_message_pin | 新增 | 消息 Pin 记录 |
| mate_message_reaction | 新增 | 消息反馈/操作记录 |
| mate_deploy_record | 新增 | 产物部署记录 |


## 8.2 扩展现有表汇总


| 表名 | 新增字段数 | 新增字段 |
| --- | --- | --- |
| mate_agent | 5 | avatar_url, capability_tags, agent_status, is_public, agent_type 扩展值 |
| mate_conversation | 6 | conversation_type, archived, pinned_at, last_active_at, last_message_preview, unread_count |
| mate_message | 5 | message_type, reply_to_id, sender_agent_id, artifact_refs, regenerated_from_id |


## 8.3 参考资料

mateclaw-dev 数据库基线: mateclaw-server/src/main/resources/db/migration/h2/V1__baseline_schema.sql

mateclaw-dev API 文档: api.md (350+ 端点)

AgentHub 主要功能文档: 主要功能文档.txt

产品设计文档: 产品设计文档.docx
