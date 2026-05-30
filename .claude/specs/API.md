


AgentHub

Multi-Agent Collaboration Platform


REST API Reference

v1.4.0


Base URL: http://localhost:18088/api/v1/

2026-05-22


# Table of Contents


# 1. Global Conventions


## 1.1 Base URL

All API endpoints are prefixed with /api/v1/. In development, the full base URL is:

http://localhost:18088/api/v1/


## 1.2 Unified Response Format

All responses follow a unified JSON structure:

{

"code": 200,

"message": "success",

"data": {}

}

Fields:

code     (number)  HTTP status code. 200 = success; 400+ = error

message  (string)  Human-readable description

data     (any)     Response payload; null on error


## 1.3 Pagination

Paginated endpoints accept Query params page (default 1) and size (default 20, max 100). Response wraps the list:

{

"code": 200,

"data": {

"records": [],

"total": 150,

"current": 1,

"size": 20,

"pages": 8

}

}


## 1.4 Authentication

All endpoints require authentication unless marked "Public". Use JWT Bearer Token:

Authorization: Bearer <jwt_token>

Tokens are obtained via POST /auth/login, expire after 24 hours. A new token is returned in the X-New-Token response header when remaining validity < 2 hours.


## 1.5 Workspace Isolation

Most endpoints require the X-Workspace-Id header:

X-Workspace-Id: 1


## 1.6 Role-Based Access

Role hierarchy (ascending): viewer -> member -> admin -> owner -> global_admin.
viewer:  Read-only (chat, view agents/content)
member:  Read + write (manage agents, conversations)
admin:   Full workspace admin (members, channels, models, security)
owner:   Same as admin + deletion privileges
global_admin: System-wide (user management, runtime control)


## 1.7 SSE Streaming Protocol

Chat endpoints support Server-Sent Events. The client receives a stream of JSON events:

event: text

data: {"delta":"Hello","turnId":"t_123"}


event: tool_call

data: {"toolName":"read_file","args":{"path":"/src/main.js"},"callId":"c_1"}


event: tool_result

data: {"callId":"c_1","output":"file content..."}


event: orchestrator_plan

data: {"taskId":101,"title":"Build landing page","steps":[...]}


event: delegation_progress

data: {"agentName":"Claude Code","status":"running","summary":"Working..."}


event: artifact_preview

data: {"artifactId":5,"type":"html","previewUrl":"/artifacts/5/preview"}


event: done

data: {"turnId":"t_123","finishReason":"stop","tokenUsage":{...}}


event: error

data: {"code":500,"message":"Internal error"}


## 1.8 Error Codes

Error responses include HTTP status code and message:

{

"code": 403,

"message": "Insufficient workspace role required",

"data": null

}


# 2. Authentication & Users

User authentication, account management, and Personal Access Token (PAT) lifecycle. Base: /api/v1/auth.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| POST | /auth/login | Public | Body: LoginRequest | Authenticate user, return JWT token (24h expiry) |
| GET | /auth/me | JWT | — | Get current user profile |
| PUT | /auth/me | JWT | Body: UpdateProfileRequest | Update current user nickname, avatar, email |
| GET | /auth/users | GlobalAdmin | Query: page, size | Paginated user list (admin only) |
| POST | /auth/users | GlobalAdmin | Body: CreateUserRequest | Create a new user account |
| PUT | /auth/users/{id}/password | JWT | Body: {oldPassword, newPassword} | Change own password |
| GET | /auth/tokens | JWT | Query: page, size | List personal access tokens (metadata only) |
| POST | /auth/tokens | JWT | Body: {name, scopes?, expiresAt?} | Create PAT; plaintext returned once |
| DELETE | /auth/tokens/{id} | JWT | — | Revoke a PAT |


### Login Request / Response

POST /api/v1/auth/login

{

"username": "admin",

"password": "your_password"

}

Response (200):

{

"code": 200,

"message": "success",

"data": {

"userId": 1,

"token": "eyJhbGciOiJIUzI1NiJ9...",

"username": "admin",

"nickname": "Admin",

"role": "admin"

}

}

Response (401):

{

"code": 401,

"message": "Invalid username or password",

"data": null

}


### Create PAT

POST /api/v1/auth/tokens

{

"name": "CLI Access",

"scopes": [

"chat",

"agents:read"

],

"expiresAt": "2026-12-31T23:59:59Z"

}

Response (201):

{

"code": 200,

"message": "success",

"data": {

"id": 5,

"name": "CLI Access",

"token": "mc_aB3xK9vL2mN7pQ",

"scopes": [

"chat",

"agents:read"

],

"createdAt": "2026-05-22T10:00:00Z"

}

}


# 3. Workspace Management

Multi-tenant workspace CRUD and member management. Base: /api/v1/workspaces.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /workspaces | JWT | — | List workspaces for current user (with memberRole) |
| GET | /workspaces/{id} | JWT | — | Get workspace detail |
| GET | /workspaces/{id}/access | JWT | — | Get user access capabilities for routing |
| POST | /workspaces | JWT | Body: CreateWorkspaceRequest | Create workspace; creator becomes owner |
| PUT | /workspaces/{id} | JWT (admin) | Body: UpdateWorkspaceRequest | Update workspace name, description, settings |
| DELETE | /workspaces/{id} | JWT (owner) | — | Delete workspace and all associated resources |
| GET | /workspaces/{id}/members | JWT | Query: page, size | List workspace members with roles |
| POST | /workspaces/{id}/members | JWT (admin) | Body: AddMemberRequest | Add a member by userId or username |
| PUT | /workspaces/{id}/members/{memberId} | JWT (admin) | Body: {role} | Update member role (viewer/member/admin) |
| DELETE | /workspaces/{id}/members/{memberId} | JWT (admin) | — | Remove member from workspace |


### Create Workspace

POST /api/v1/workspaces

{

"name": "My Team Workspace",

"description": "Workspace for team collaboration",

"slug": "my-team"

}

Response (201):

{

"code": 200,

"message": "success",

"data": {

"id": 2,

"name": "My Team Workspace",

"slug": "my-team",

"ownerId": 1,

"createdAt": "2026-05-22T10:00:00Z"

}

}


### List Members

GET /api/v1/workspaces/2/members

Response (200):

{

"code": 200,

"message": "success",

"data": {

"records": [

{

"id": 1,

"workspaceId": 2,

"userId": 1,

"username": "admin",

"nickname": "Admin",

"role": "owner"

},

{

"id": 2,

"workspaceId": 2,

"userId": 3,

"username": "dev1",

"nickname": "Developer 1",

"role": "member"

}

],

"total": 2,

"current": 1,

"size": 20,

"pages": 1

}

}


# 4. Agent Management

Complete lifecycle management for AI Agents: creation, configuration, capability binding, and runtime control. Base: /api/v1/agents.


### Agent CRUD


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /agents | viewer | Query: enabled, keyword, agentType, page, size. Header: X-Workspace-Id | List agents with filtering by status, keyword, type |
| GET | /agents/{id} | viewer | — | Get agent detail: avatar, capability tags, status, config |
| GET | /agents/{id}/capabilities | viewer | — | Get agent modality capabilities and sidecar config |
| POST | /agents | member | Body: CreateAgentRequest | Create agent with system prompt, model, type, tags |
| PUT | /agents/{id} | member | Body: UpdateAgentRequest | Update agent configuration |
| DELETE | /agents/{id} | member | — | Delete agent (admin, workspace admin, or creator only) |
| PUT | /agents/{id}/avatar | member | Multipart: file | Upload agent avatar image |
| GET | /agents/{id}/stats | viewer | — | Get agent usage stats (conversation count, token usage, avg response time) |
| GET | /agents/{id}/conversations | viewer | Query: page, size | List conversations involving this agent |


### Create Agent

POST /api/v1/agents

{

"name": "Claude Code",

"description": "Expert coding agent powered by Claude",

"agentType": "react",

"systemPrompt": "You are an expert software engineer...",

"modelName": "claude-sonnet-4-6",

"enabled": true,

"avatarUrl": "https://example.com/avatar.png",

"capabilityTags": [

"coding",

"debugging",

"code-review"

],

"isPublic": true

}

Response (201):

{

"code": 200,

"message": "success",

"data": {

"id": 5,

"name": "Claude Code",

"agentType": "react",

"agentStatus": "AVAILABLE",

"systemPrompt": "You are an expert software engineer...",

"modelName": "claude-sonnet-4-6",

"enabled": true,

"avatarUrl": "https://example.com/avatar.png",

"capabilityTags": [

"coding",

"debugging",

"code-review"

],

"isPublic": true,

"workspaceId": 1,

"creatorUserId": 1,

"createTime": "2026-05-22T10:00:00Z"

}

}


### Agent Stats

GET /api/v1/agents/5/stats

Response (200):

{

"code": 200,

"message": "success",

"data": {

"agentId": 5,

"totalConversations": 128,

"totalMessages": 3456,

"totalTokens": 12500000,

"avgResponseTimeMs": 3200,

"lastActiveAt": "2026-05-22T09:55:00Z"

}

}


### Agent Skill Binding


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /agents/{agentId}/skills | viewer | — | List skills bound to this agent |
| PUT | /agents/{agentId}/skills | member | Body: [skillId, ...] | Batch-set skill bindings |
| POST | /agents/{agentId}/skills/{skillId} | member | — | Bind a single skill to agent |
| DELETE | /agents/{agentId}/skills/{skillId} | member | — | Unbind a skill from agent |


### Agent Tool Binding


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /agents/{agentId}/tools | viewer | — | List tools bound to this agent |
| PUT | /agents/{agentId}/tools | member | Body: ["toolName", ...] | Batch-set tool bindings |


### Agent Provider Preferences


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /agents/{agentId}/provider-preferences | viewer | — | Get provider preference ordering |
| PUT | /agents/{agentId}/provider-preferences | member | Body: [{providerId, priority}] | Set provider preferences |


### Agent Chat / Execution


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /agents/{id}/chat/stream | viewer | Query: message, conversationId. Accept: text/event-stream | SSE streaming chat; auto-creates conversation if needed |
| POST | /agents/{id}/chat | viewer | Body: {message, conversationId, contentParts?} | Synchronous chat (non-streaming) |
| POST | /agents/{id}/execute | viewer | Body: {message, conversationId} | Execute agent in Plan-Execute mode |
| GET | /agents/{id}/state | viewer | — | Get agent runtime state: IDLE or RUNNING |


### Agent Templates


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /templates | Public | Header: Accept-Language | List built-in agent templates |
| POST | /templates/{id}/apply | member | Header: X-Workspace-Id | Create agent from a template |


# 5. Conversation Management

Conversation lifecycle: creation, listing, message retrieval, archiving, pinning. Base: /api/v1/conversations.


### Conversation CRUD


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /conversations | JWT | Header: X-Workspace-Id. Query: conversationType, archived, keyword | List conversations sorted by lastActiveAt DESC |
| GET | /conversations/page | JWT | Header: X-Workspace-Id. Query: page, size, keyword | Paginated list with lastMessagePreview, unreadCount |
| GET | /conversations/{id} | JWT | — | Get conversation detail incl. group config if applicable |
| GET | /conversations/{id}/messages | JWT | Query: beforeId, limit (default 50) | Cursor-based message history pagination |
| DELETE | /conversations/{id} | JWT | — | Delete conversation and all messages |
| PUT | /conversations/{id}/title | JWT | Body: {title} | Rename conversation |
| PUT | /conversations/{id}/pin | JWT | Body: {pinned: bool} | Toggle pin status (sets pinnedAt) |
| PUT | /conversations/{id}/archive | JWT | Body: {archived: bool} | Archive or unarchive conversation |
| PUT | /conversations/{id}/model | JWT | Body: {modelProvider, modelName} | Pin conversation to a specific model |
| POST | /conversations/batch-delete | JWT | Body: {conversationIds: [...]} | Batch delete multiple conversations |
| DELETE | /conversations/{id}/messages | JWT | — | Clear all messages in a conversation |
| GET | /conversations/{id}/status | JWT | — | Get stream status: {streamStatus: idle|running} |


### Conversation List Response

{

"code": 200,

"message": "success",

"data": {

"records": [

{

"id": 42,

"conversationId": "a1b2c3d4-...",

"title": "Build landing page",

"agentId": 5,

"agentName": "Claude Code",

"agentAvatarUrl": "https://...png",

"conversationType": "direct",

"archived": false,

"pinnedAt": null,

"lastActiveAt": "2026-05-22T09:55:00Z",

"lastMessagePreview": "Here is the HTML for the landing page...",

"unreadCount": 0,

"messageCount": 24

}

],

"total": 15,

"current": 1,

"size": 20,

"pages": 1

}

}


### Message History Response

{

"code": 200,

"message": "success",

"data": {

"records": [

{

"id": 500,

"conversationId": 42,

"role": "assistant",

"content": "Here is the code...",

"messageType": "code",

"senderAgentId": 5,

"replyToId": null,

"artifactRefs": [

1,

2

],

"status": "completed",

"tokenUsage": {

"promptTokens": 1200,

"completionTokens": 800

},

"createTime": "2026-05-22T09:54:00Z"

},

{

"id": 499,

"conversationId": 42,

"role": "user",

"content": "Create a landing page",

"messageType": "text",

"senderAgentId": null,

"replyToId": null,

"artifactRefs": null,

"status": "completed",

"createTime": "2026-05-22T09:53:00Z"

}

],

"hasMore": true,

"nextBeforeId": 498

}

}


### Pinned Messages


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /conversations/{id}/pins | JWT | — | List pinned messages for this conversation |
| POST | /conversations/{id}/pins | JWT | Body: {messageId, note?} | Pin a message with optional note |
| DELETE | /conversations/{id}/pins/{messageId} | JWT | — | Unpin a message |


### Pinned Messages Response

{

"code": 200,

"message": "success",

"data": {

"records": [

{

"id": 10,

"messageId": 500,

"conversationId": 42,

"pinnedBy": 1,

"note": "Keep this as context",

"messagePreview": "Here is the code...",

"pinnedAt": "2026-05-22T09:56:00Z"

}

]

}

}


# 6. Group Conversations

Multi-Agent group chat management: create groups, manage members, configure orchestration settings. Base: /api/v1/conversations/group.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| POST | /conversations/group | JWT | Body: CreateGroupRequest | Create group conversation; Orchestrator Agent auto-joined |
| GET | /conversations/group | JWT | Query: page, size. Header: X-Workspace-Id | List group conversations for current user |
| GET | /conversations/group/{id} | JWT | — | Get group detail with members and config |
| PUT | /conversations/group/{id} | JWT | Body: UpdateGroupRequest | Update scheduling mode, failure policy, max parallel tasks |
| PUT | /conversations/group/{id}/members | JWT | Body: {agentIds: [...]} | Batch replace group members |
| POST | /conversations/group/{id}/members | JWT | Body: {agentId, memberRole?} | Add a member to group |
| DELETE | /conversations/group/{id}/members/{agentId} | JWT | — | Remove a member from group |


### Create Group Conversation

POST /api/v1/conversations/group

{

"title": "Website Redesign Team",

"orchestratorAgentId": 3,

"agentIds": [

5,

7,

8

],

"schedulingMode": "auto",

"failurePolicy": "fail_tolerant",

"maxParallelTasks": 4

}

Response (201):

{

"code": 200,

"message": "success",

"data": {

"conversationId": 43,

"title": "Website Redesign Team",

"conversationType": "group",

"groupConfig": {

"orchestratorAgentId": 3,

"schedulingMode": "auto",

"failurePolicy": "fail_tolerant",

"maxParallelTasks": 4

},

"members": [

{

"agentId": 3,

"agentName": "Orchestrator",

"memberRole": "orchestrator",

"joinedAt": "2026-05-22T10:00:00Z"

},

{

"agentId": 5,

"agentName": "Claude Code",

"memberRole": "member",

"joinedAt": "2026-05-22T10:00:00Z"

},

{

"agentId": 7,

"agentName": "Designer Agent",

"memberRole": "member",

"joinedAt": "2026-05-22T10:00:00Z"

},

{

"agentId": 8,

"agentName": "Reviewer Agent",

"memberRole": "member",

"joinedAt": "2026-05-22T10:00:00Z"

}

],

"createdAt": "2026-05-22T10:00:00Z"

}

}


# 7. Chat & Messages

Core messaging APIs: SSE streaming, synchronous chat, message lifecycle. Base: /api/v1/chat and /api/v1/messages.


### Chat Endpoints


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| POST | /chat/stream | Public | Body: ChatStreamRequest. Accept: text/event-stream | SSE streaming chat; supports reconnection via lastEventId |
| POST | /chat | JWT | Body: ChatRequest | Synchronous chat (non-streaming); returns complete response |
| POST | /chat/{conversationId}/stop | Public | Body: {message?} | Stop an active stream generation |
| POST | /chat/{conversationId}/interrupt | JWT | Body: InterruptRequest | Queue follow-up message; delivered after current turn |
| POST | /chat/upload | JWT | Multipart: file + conversationId | Upload file attachment to conversation |
| GET | /chat/files/{conversationId}/{storedName:.*} | JWT | — | Download an uploaded file attachment |


### SSE Stream Request

POST /api/v1/chat/stream

{

"agentId": 5,

"message": "Create a React component for a user profile card",

"conversationId": null,

"contentParts": null,

"thinkingLevel": "medium",

"modelProvider": null,

"modelName": null,

"reconnect": false,

"lastEventId": null

}

SSE Event Sequence:

1. event: text  —  Incremental text deltas as agent generates

2. event: tool_call  —  Agent invokes a tool (read_file, write_file, etc.)

3. event: tool_result  —  Tool execution result

4. event: orchestrator_plan  —  (Group only) Orchestrator task decomposition plan

5. event: delegation_progress  —  (Group only) Sub-agent execution progress

6. event: artifact_preview  —  Agent produces an artifact (web page, code, etc.)

7. event: done  —  Turn completed with finishReason and tokenUsage

8. event: error  —  Error occurred during generation

Sample SSE Events:

event: text

data: {"delta":"Sure! Let me create ","turnId":"t_5001"}


event: orchestrator_plan

data: {"taskId":101,"title":"Build landing page","steps":[{"order":1,"agentName":"Designer Agent","goal":"Design HTML/CSS layout","mode":"sequential"}],"totalAssignments":1}


event: done

data: {"turnId":"t_5001","finishReason":"stop","tokenUsage":{"promptTokens":3500,"completionTokens":1200,"totalTokens":4700},"runtimeModel":"claude-sonnet-4-6","runtimeProvider":"anthropic"}


### Message Endpoints


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /messages/{id} | JWT | — | Get message detail (metadata, toolCalls, segments) |
| POST | /messages/{id}/regenerate | JWT | Body: {message?} | Regenerate agent response; linked via regeneratedFromId |
| GET | /messages/{id}/reply-chain | JWT | — | Get reply chain for threaded view |


### Message Detail Response

{

"code": 200,

"message": "success",

"data": {

"id": 500,

"conversationId": 42,

"role": "assistant",

"content": "Here is the React component you requested...",

"messageType": "code",

"senderAgentId": 5,

"replyToId": 499,

"artifactRefs": [

1,

2

],

"regeneratedFromId": null,

"status": "completed",

"tokenUsage": {

"promptTokens": 1200,

"completionTokens": 800

},

"metadata": {

"toolCalls": [

{

"toolName": "write_file",

"args": {

"path": "/src/ProfileCard.jsx"

}

}

],

"segments": [

{

"type": "text"

},

{

"type": "code",

"language": "jsx"

}

],

"finishReason": "stop"

},

"createTime": "2026-05-22T09:54:00Z"

}

}


# 8. Message Reactions

User feedback on agent messages: like, dislike, regenerate, apply_diff. Base: /api/v1/messages.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /messages/{id}/reactions | JWT | — | Get all reactions for a message, grouped by type |
| POST | /messages/{id}/reactions | JWT | Body: {reactionType} | Add a reaction (regenerate, like, dislike, apply_diff) |
| DELETE | /messages/{id}/reactions/{reactionType} | JWT | — | Remove own reaction of specified type |


### Reactions Example

POST /api/v1/messages/500/reactions

{

"reactionType": "like"

}

Response (200):

{

"code": 200,

"message": "success",

"data": {

"messageId": 500,

"reactions": {

"like": [

{

"userId": 1,

"username": "admin",

"createdAt": "2026-05-22T09:55:00Z"

}

],

"apply_diff": [

{

"userId": 1,

"username": "admin",

"createdAt": "2026-05-22T09:56:00Z"

}

]

}

}

}


# 9. Orchestrator Scheduling

Multi-Agent task orchestration: view decomposition plans, monitor assignments, retry/cancel. Base: /api/v1/orchestrator.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /orchestrator/tasks | JWT | Query: conversationId, status, page, size | List orchestrator tasks with status filtering |
| GET | /orchestrator/tasks/{taskId} | JWT | — | Get task detail with full plan JSON structure |
| GET | /orchestrator/tasks/{taskId}/assignments | JWT | — | List all sub-task assignments for a task |
| GET | /orchestrator/assignments/{id} | JWT | — | Get single assignment detail (status, result, error) |
| POST | /orchestrator/tasks/{taskId}/retry | JWT | Body: {assignmentIds?: [...]} | Retry failed assignments; omit ids to retry all |
| POST | /orchestrator/tasks/{taskId}/cancel | JWT | — | Cancel active orchestration and all running assignments |


### Task Detail Response

{

"code": 200,

"message": "success",

"data": {

"id": 101,

"conversationId": 43,

"messageId": 500,

"title": "Build landing page and deploy",

"planJson": {

"steps": [

{

"order": 1,

"agentName": "Designer Agent",

"goal": "Design HTML/CSS layout",

"mode": "sequential"

},

{

"order": 2,

"agentName": "Claude Code",

"goal": "Add JavaScript interactivity",

"mode": "sequential",

"dependsOn": 1

}

]

},

"status": "running",

"totalAssignments": 3,

"completedAssignments": 1,

"failedAssignments": 0,

"startedAt": "2026-05-22T09:53:00Z",

"completedAt": null,

"aggregationMessageId": null,

"createdAt": "2026-05-22T09:52:00Z"

}

}


### Assignments List Response

{

"code": 200,

"message": "success",

"data": {

"records": [

{

"id": 201,

"taskId": 101,

"agentId": 7,

"agentName": "Designer Agent",

"stepOrder": 1,

"executionMode": "sequential",

"goal": "Design HTML/CSS layout",

"dependencyOn": null,

"status": "completed",

"childConversationId": 44,

"resultSummary": "Created responsive HTML/CSS layout",

"errorMessage": null,

"retryCount": 0,

"startedAt": "2026-05-22T09:53:00Z",

"completedAt": "2026-05-22T09:54:30Z"

},

{

"id": 202,

"taskId": 101,

"agentId": 5,

"agentName": "Claude Code",

"stepOrder": 2,

"executionMode": "sequential",

"goal": "Add JavaScript interactivity",

"dependencyOn": 201,

"status": "running",

"childConversationId": 45,

"resultSummary": null,

"errorMessage": null,

"retryCount": 0,

"startedAt": "2026-05-22T09:54:30Z",

"completedAt": null

}

]

}

}


# 10. Artifact Management

Agent-generated artifacts: browse, inspect versions, compare diffs, rollback. Base: /api/v1/artifacts.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /artifacts | JWT | Query: conversationId, type, deployStatus, workspaceId, page, size | List artifacts with multi-dimensional filtering |
| GET | /artifacts/{id} | JWT | — | Get artifact detail with current version info |
| GET | /artifacts/{id}/versions | JWT | Query: page, size | Version history (newest first) |
| GET | /artifacts/{id}/versions/{versionId} | JWT | — | Get specific version detail including file content |
| GET | /artifacts/{id}/versions/diff | JWT | Query: from, to (version numbers) | Unified diff between two versions |
| POST | /artifacts/{id}/versions/{versionId}/restore | JWT | — | Rollback artifact to historical version |
| PUT | /artifacts/{id}/tags | JWT | Body: {tags: [...]} | Update artifact tags (JSON array) |


### Artifact List Response

{

"code": 200,

"message": "success",

"data": {

"records": [

{

"id": 1,

"conversationId": 42,

"messageId": 500,

"creatorAgentId": 5,

"artifactName": "landing-page",

"artifactType": "html",

"currentVersion": 3,

"deployStatus": "deployed",

"deployUrl": "https://landing-page.vercel.app",

"tags": [

"v1.0",

"production"

],

"createdAt": "2026-05-22T09:00:00Z",

"updatedAt": "2026-05-22T09:50:00Z"

}

],

"total": 5,

"current": 1,

"size": 20,

"pages": 1

}

}


### Version Diff Response

{

"code": 200,

"message": "success",

"data": {

"artifactId": 1,

"fromVersion": 1,

"toVersion": 3,

"diff": "@@ -1,10 +1,15 @@\n <html>\n   <head>\n-    <title>Old Title</title>\n+    <title>New Landing Page</title>\n   </head>"

}

}


# 11. Artifact Deployment

One-click deployment for web artifacts. Base: /api/v1/artifacts.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| POST | /artifacts/{id}/deploy | JWT | Body: {deployTarget, versionId?} | Initiate deployment; defaults to current version |
| GET | /artifacts/{id}/deploy/status | JWT | — | Get current deployment status |
| GET | /artifacts/{id}/deploy/history | JWT | Query: page, size | List deployment history records |


### Deploy Status Response

{

"code": 200,

"message": "success",

"data": {

"artifactId": 1,

"versionId": 10,

"status": "deploying",

"deployTarget": "vercel",

"deployUrl": null,

"startedAt": "2026-05-22T10:05:00Z",

"estimatedSeconds": 30

}

}


### Deploy History Response

{

"code": 200,

"message": "success",

"data": {

"records": [

{

"id": 50,

"artifactId": 1,

"versionId": 8,

"deployTarget": "vercel",

"deployUrl": "https://landing-page.vercel.app",

"status": "deployed",

"errorLog": null,

"deployedBy": 1,

"createdAt": "2026-05-22T09:30:00Z",

"completedAt": "2026-05-22T09:31:00Z"

}

],

"total": 3,

"current": 1,

"size": 20,

"pages": 1

}

}


# 12. Skills Management

Agent skill definitions: create, configure, toggle, manage lifecycle. Base: /api/v1/skills.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /skills | member | Query: keyword, skillType, enabled, page, size | List skills with filtering |
| GET | /skills/{id} | member | — | Get skill detail with files and config |
| POST | /skills | admin | Body: CreateSkillRequest | Create a new skill definition |
| PUT | /skills/{id} | admin | Body: UpdateSkillRequest | Update skill metadata and files |
| DELETE | /skills/{id} | admin | — | Delete skill (soft delete) |
| PUT | /skills/{id}/toggle | admin | Query: enabled | Enable or disable a skill |
| GET | /skills/types | member | — | List available skill type categories |
| GET | /skills/summary | member | — | Get workspace skill summary (counts by type/status) |
| GET | /skills/installed | member | Query: page, size | List installed skills from ClawHub |
| PUT | /skills/{id}/pin | member | Body: {pinned} | Pin/unpin skill |
| PUT | /skills/{id}/archive | member | Body: {archived} | Archive/restore skill |
| GET | /skills/{id}/prompt-preview | member | — | Preview rendered system prompt for this skill |


# 13. Tools Management

Agent tools registry: built-in, MCP, and channel tools. Base: /api/v1/tools.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /tools | member | Query: enabled, toolType, keyword | List tools with filtering |
| GET | /tools/{id} | member | — | Get tool detail with parameter schema |
| POST | /tools | admin | Body: CreateToolRequest | Register a new tool |
| PUT | /tools/{id} | admin | Body: UpdateToolRequest | Update tool configuration |
| DELETE | /tools/{id} | admin | — | Delete tool |
| PUT | /tools/{id}/toggle | admin | Query: enabled | Enable or disable a tool |
| GET | /tools/enabled | member | — | List all currently enabled tools |
| GET | /tools/available | member | — | List tools available for agent binding |


# 14. MCP Server Management

Model Context Protocol server registry. Base: /api/v1/mcp/servers.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /mcp/servers | admin | Query: enabled, page, size | List MCP server configurations |
| GET | /mcp/servers/{id} | admin | — | Get MCP server detail |
| POST | /mcp/servers | admin | Body: CreateMcpServerRequest | Register a new MCP server connection |
| PUT | /mcp/servers/{id} | admin | Body: UpdateMcpServerRequest | Update MCP server configuration |
| DELETE | /mcp/servers/{id} | admin | — | Delete MCP server |
| PUT | /mcp/servers/{id}/toggle | admin | Query: enabled | Enable or disable MCP server |
| POST | /mcp/servers/{id}/test | admin | — | Test connection to MCP server |
| POST | /mcp/servers/{id}/discover | admin | — | Discover tools exposed by this MCP server |
| POST | /mcp/servers/refresh | admin | — | Refresh tools from all enabled MCP servers |


# 15. Model Configuration

LLM Provider and Model management. Base: /api/v1/models.


### Provider Endpoints


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /models/providers | admin | — | List all model providers |
| GET | /models/providers/{id} | admin | — | Get provider detail with API configuration |
| POST | /models/providers | admin | Body: CreateProviderRequest | Register a new provider |
| PUT | /models/providers/{id} | admin | Body: UpdateProviderRequest | Update provider config (API key, base URL) |
| DELETE | /models/providers/{id} | admin | — | Delete provider |
| PUT | /models/providers/{id}/toggle | admin | Query: enabled | Enable/disable provider |
| GET | /models/providers/pool/status | admin | — | Get provider pool health status |
| POST | /models/providers/{id}/test | admin | — | Test provider connectivity |


### Model Endpoints


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /models | member | Query: providerId, enabled | List configured models |
| GET | /models/{id} | member | — | Get model detail |
| POST | /models | admin | Body: CreateModelRequest | Register a new model under a provider |
| PUT | /models/{id} | admin | Body: UpdateModelRequest | Update model configuration |
| DELETE | /models/{id} | admin | — | Delete model |
| PUT | /models/{id}/default | admin | — | Set model as workspace default |
| GET | /models/default | member | — | Get current default model |


# 16. Security & ToolGuard

Tool-level security guard: rules, approval workflows, audit logs. Base: /api/v1/security.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /security/guard/config | admin | — | Get ToolGuard global configuration |
| PUT | /security/guard/config | admin | Body: GuardConfigRequest | Update ToolGuard global config |
| GET | /security/guard/rules | admin | Query: enabled, page, size | List guard rules |
| GET | /security/guard/rules/{id} | admin | — | Get rule detail |
| POST | /security/guard/rules | admin | Body: CreateRuleRequest | Create a guard rule |
| PUT | /security/guard/rules/{id} | admin | Body: UpdateRuleRequest | Update a guard rule |
| DELETE | /security/guard/rules/{id} | admin | — | Delete a guard rule |
| PUT | /security/guard/rules/{id}/toggle | admin | Query: enabled | Enable/disable a guard rule |
| GET | /security/guard/audit-logs | admin | Query: conversationId, ruleId, page, size | Query guard audit logs |
| GET | /security/guard/stats | admin | Query: startDate, endDate | Get guard trigger statistics |
| GET | /chat/{conversationId}/pending-approvals | JWT | — | Get pending tool approvals for conversation |


# 17. Memory System

Agent memory: facts, dreaming, emergence. Base: /api/v1/memory/{agentId}.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| POST | /memory/{agentId}/emergence | member | — | Trigger memory emergence analysis |
| POST | /memory/{agentId}/dream | member | Query: focused (bool) | Trigger dreaming for memory consolidation |
| GET | /memory/{agentId}/dreaming/status | member | — | Get current dreaming process status |
| GET | /memory/{agentId}/dreaming/candidates | member | — | List memory candidates for dreaming |
| GET | /memory/{agentId}/dreams | member | Query: page, size | List dream report history |
| GET | /memory/{agentId}/dreams/{id} | member | — | Get dream report detail |
| GET | /memory/{agentId}/facts | member | Query: page, size, keyword | List stored facts |
| POST | /memory/{agentId}/facts/{factId}/forget | member | — | Mark a fact as forgotten |
| POST | /memory/{agentId}/facts/{factId}/feedback | member | Body: {feedback} | Provide feedback on a fact |
| GET | /memory/{agentId}/facts/contradictions | member | — | List conflicting facts |
| POST | /memory/{agentId}/facts/contradictions/{id}/resolve | member | Body: {resolution} | Resolve fact contradiction |


# 18. Workspace Files

Per-agent file storage and prompt file management. Base: /api/v1/agents/{agentId}/workspace.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /agents/{agentId}/workspace/files | viewer | Query: path (subdirectory) | List files (without content) |
| GET | /agents/{agentId}/workspace/files/** | viewer | — | Read a file (supports subdirectory paths) |
| PUT | /agents/{agentId}/workspace/files/** | member | Body: {content} | Create or update a file |
| DELETE | /agents/{agentId}/workspace/files/** | member | — | Delete a file |
| GET | /agents/{agentId}/workspace/prompt-files | viewer | — | Get enabled system prompt files |
| PUT | /agents/{agentId}/workspace/prompt-files | member | Body: {files: [{filename, enabled}]} | Set enabled prompt files |
| GET | /agents/{agentId}/workspace/memory/export | viewer | — | Export memory as ZIP archive |
| POST | /agents/{agentId}/workspace/memory/import/preview | member | Multipart: file | Preview memory import (dry-run) |
| POST | /agents/{agentId}/workspace/memory/import | member | Multipart: file | Commit memory import from ZIP |


# 19. Dashboard & Token Usage

Workspace analytics: activity overview, trends, token usage, cost tracking.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /dashboard/overview | member | Header: X-Workspace-Id | Get workspace overview (agent count, conversations, messages today) |
| GET | /dashboard/trend | member | Query: days. Header: X-Workspace-Id | Get daily activity trend data |
| GET | /token-usage | member | Query: startDate, endDate, modelName, providerId, page, size | Get paginated token usage records |
| GET | /token-usage/summary | member | Query: startDate, endDate. Header: X-Workspace-Id | Get token usage summary (total tokens, cost estimate) |


### Token Usage Summary Response

{

"code": 200,

"message": "success",

"data": {

"totalPromptTokens": 2500000,

"totalCompletionTokens": 1800000,

"totalTokens": 4300000,

"estimatedCostUSD": 12.85,

"topModels": [

{

"modelName": "claude-sonnet-4-6",

"tokens": 2000000,

"costUSD": 6

}

],

"periodStart": "2026-05-01",

"periodEnd": "2026-05-22"

}

}


# 20. Plans

Agent Plan-Execute mode plans: view task decomposition and execution status.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /plans | member | Query: agentId, page, size | List plans, optionally filtered by agent |
| GET | /plans/{id} | member | — | Get plan detail with sub-plans and execution status |


# 21. Sub-Agents Runtime

Runtime management of delegated sub-agents. Base: /api/v1/subagents and /api/v1/admin/agent-runtime.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /subagents/active | GlobalAdmin | Query: parentConversationId | List currently active sub-agents |
| POST | /subagents/spawn-pause | GlobalAdmin | Body: {parentConversationId, paused} | Toggle spawn-pause for parent conversation |
| POST | /subagents/{subagentId}/interrupt | GlobalAdmin | — | Interrupt a running sub-agent |
| GET | /admin/agent-runtime/snapshot | GlobalAdmin | — | Full snapshot of all in-flight agent turns |
| POST | /admin/agent-runtime/runs/{conversationId}/stop | GlobalAdmin | — | Friendly stop of an agent run |
| POST | /admin/agent-runtime/runs/{conversationId}/recycle | GlobalAdmin | — | Force-recycle (dispose flux + drop RunState) |
| POST | /admin/agent-runtime/subagents/{subagentId}/interrupt | GlobalAdmin | — | Admin override: interrupt sub-agent |
| POST | /admin/agent-runtime/sweep | GlobalAdmin | — | Bulk recycle all stuck runs |


# 22. ACP Endpoints

Agent Communication Protocol endpoint registry for external coding agents (Claude Code, OpenCode, Codex). Base: /api/v1/acp/endpoints.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /acp/endpoints | admin | Header: X-Workspace-Id | List registered ACP endpoints |
| GET | /acp/endpoints/{id} | admin | — | Get endpoint detail |
| POST | /acp/endpoints | admin | Body: CreateAcpEndpointRequest | Register new ACP endpoint (command + args + env) |
| PUT | /acp/endpoints/{id} | admin | Body: UpdateAcpEndpointRequest | Update endpoint configuration |
| DELETE | /acp/endpoints/{id} | admin | — | Delete endpoint |
| PUT | /acp/endpoints/{id}/toggle | admin | Query: enabled | Enable/disable endpoint |
| POST | /acp/endpoints/{id}/test | admin | — | Test connection (initialize handshake) |


# 23. System

System health check, global settings.


### Endpoint List


| Method | Path | Auth | Parameters | Description |
| --- | --- | --- | --- | --- |
| GET | /system/health | Public | — | System health (DB, disk, memory) |
| GET | /settings | admin | — | Get all system settings |
| PUT | /settings | admin | Body: {key: value, ...} | Update system settings |
| GET | /settings/language | Public | — | Get current system language |
| PUT | /settings/language | admin | Body: {language} | Update system language |


### Health Check Response

{

"code": 200,

"message": "success",

"data": {

"status": "UP",

"components": {

"db": {

"status": "UP",

"latency": "5ms"

},

"disk": {

"status": "UP",

"freeBytes": 50000000000

},

"memory": {

"status": "UP",

"usedPercent": 45

}

},

"uptime": "3d 12h 30m",

"version": "1.4.0"

}

}


# 24. Error Code Reference


| Code | Name | Description |
| --- | --- | --- |
| 400 | Bad Request | Invalid or missing request parameters |
| 401 | Unauthorized | Missing or invalid JWT token |
| 403 | Forbidden | Insufficient role or workspace permissions |
| 404 | Not Found | Requested resource does not exist |
| 409 | Conflict | Resource already exists or state conflict |
| 413 | Payload Too Large | Uploaded file exceeds size limit |
| 422 | Unprocessable Entity | Valid JSON but semantically invalid |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Unexpected server-side error |
| 502 | Bad Gateway | Upstream model provider error |
| 503 | Service Unavailable | Server temporarily overloaded or in maintenance |
| 504 | Gateway Timeout | Upstream model provider timeout |


# 25. Appendix


## 25.1 SSE Event Types

text                 Incremental text delta during streaming generation

tool_call            Agent invoked a tool (includes toolName, args, callId)

tool_result          Tool execution result (includes callId, output)

orchestrator_plan    (Group only) Orchestrator task decomposition plan

delegation_progress  (Group only) Sub-agent execution progress update

artifact_preview     Agent produced an artifact (web page, code file, etc.)

done                 Turn completed (includes finishReason, tokenUsage)

error                Error occurred during generation


## 25.2 Message Types

text          Plain text or Markdown-rendered content (default)

code          Code block with syntax highlighting

diff          Unified/split diff view for code changes

image         Inline image display

file          File attachment (download link + metadata)

preview_card  Inline web preview card (iframe thumbnail)

plan_card     Orchestrator task decomposition plan card

system        System notification message


## 25.3 Agent Types

react         Standard ReAct-pattern agent

plan_execute  Plan-Execute pattern agent

orchestrator  Multi-Agent coordination agent


## 25.4 Agent Status

AVAILABLE  Agent is ready

BUSY       Agent is processing a task

OFFLINE    Agent is disabled or unreachable


## 25.5 Artifact Types

html       Web page (HTML/CSS/JS) — previewable + deployable

code       Source code files — syntax highlighted + diffable

markdown   Markdown documents — rendered preview

pdf        PDF documents — embedded viewer

ppt        PowerPoint presentations — slide browsing

image      Images — thumbnail + full preview

other      Miscellaneous file types — download only


## 25.6 Deploy Status

none       Never deployed

deploying  In progress

deployed   Successfully deployed

failed     Deployment failed (see errorLog)


## 25.7 Orchestrator Scheduling Mode

auto    Automatically assign tasks based on capability matching

manual  User manually @mentions agents for each sub-task


## 25.8 Failure Policy

fail_fast      Stop all assignments if any one fails

fail_tolerant  Continue other assignments; report failures at end


## 25.9 Reaction Types

regenerate  Request agent to regenerate this message

like        Positive feedback

dislike     Negative feedback

apply_diff  Apply the diff/code change to the artifact
