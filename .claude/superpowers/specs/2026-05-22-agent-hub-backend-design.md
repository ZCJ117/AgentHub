# AgentHub Backend — Multi-Agent Collaboration Platform Design

**Date**: 2026-05-22
**Version**: 1.0
**Status**: Approved

## 1. Overview

Implement the complete backend for the AgentHub multi-agent collaboration platform by extending the existing `mateclaw-dev` Spring Boot 3 codebase. The implementation follows a **Extend-First, Then-Build-New** strategy (Approach C), with full implementation of all features from the three design documents.

### 1.1 Source Documents

- 数据库设计文档.docx (Database Design)
- 产品设计文档.docx (Product Design)
- API.docx (API Reference, 350+ endpoints)
- mateclaw-dev/ existing codebase (~400 Java files, 35+ tables, V1–V119 migrations)

### 1.2 Implementation Strategy

**Phase 1 — Extend existing foundation (V120–V122):**
Extend `mate_agent`, `mate_conversation`, `mate_message` tables and their entities, services, and controllers.

**Phase 2 — New domain modules (V123–V131):**
Group conversations → Orchestrator scheduling → Artifacts + versions + deployment → Pins + reactions.

### 1.3 Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Orchestrator LLM integration | Reuse existing AgentGraphBuilder (agentType="orchestrator") | Design doc specifies Orchestrator as a special Agent type |
| Artifact storage | Local filesystem with `ArtifactStorageProvider` interface | Works with existing workspace file patterns; pluggable later |
| Deployment | Stubbed (`NoopDeployProvider`); endpoints wired | Avoids external service dependency; swap-in later |
| Testing | Key integration tests only (H2 in-memory) | Covers critical paths without exhaustive unit tests |

## 2. Architecture

### 2.1 Package Structure

```
vip/mate/
├── agent/                          # [MODIFIED]
│   ├── model/AgentEntity.java      # +5 fields
│   ├── service/AgentService.java   # +avatar upload, stats, status
│   └── controller/AgentController.java
│
├── workspace/conversation/         # [MODIFIED]
│   ├── model/
│   │   ├── ConversationEntity.java # +6 fields
│   │   └── MessageEntity.java      # +5 fields
│   ├── service/ConversationService.java
│   ├── controller/ConversationController.java
│   └── vo/ConversationVO.java
│
├── group/                          # [NEW]
│   ├── model/GroupConversationEntity.java, GroupMemberEntity.java
│   ├── repository/GroupConversationMapper.java, GroupMemberMapper.java
│   ├── service/GroupConversationService.java
│   └── controller/GroupConversationController.java
│
├── orchestrator/                   # [NEW]
│   ├── model/OrchestratorTaskEntity.java, OrchestratorAssignmentEntity.java
│   ├── repository/OrchestratorTaskMapper.java, OrchestratorAssignmentMapper.java
│   ├── service/OrchestratorService.java
│   ├── controller/OrchestratorController.java
│   └── event/OrchestratorEventTypes.java
│
├── artifact/                       # [NEW]
│   ├── model/ArtifactEntity.java, ArtifactVersionEntity.java, DeployRecordEntity.java
│   ├── repository/ArtifactMapper.java, ArtifactVersionMapper.java, DeployRecordMapper.java
│   ├── service/ArtifactService.java, ArtifactVersionService.java, ArtifactDeployService.java
│   ├── storage/ArtifactStorageProvider.java, LocalFileSystemProvider.java
│   └── controller/ArtifactController.java
│
├── message/                        # [NEW]
│   ├── model/MessagePinEntity.java, MessageReactionEntity.java
│   ├── repository/MessagePinMapper.java, MessageReactionMapper.java
│   └── service/MessagePinService.java, MessageReactionService.java
│
├── chat/                           # [MODIFIED]
│   └── controller/ChatController.java  # +new SSE event types
│
└── tool/builtin/
    └── DelegateAgentTool.java      # [MODIFIED] group-context-aware delegation
```

### 2.2 What Does NOT Change

- pom.xml (no new dependencies needed)
- Security config, JWT auth, X-Workspace-Id isolation
- LLM provider infrastructure
- SubagentRegistry, ToolGuard, approval flows
- mateclaw-plugin-api, mateclaw-plugin-sample
- AIagent_frontend (zero changes)

## 3. Data Layer

### 3.1 Flyway Migrations (V120–V131)

All migrations in both `h2/` and `mysql/` directories. H2 uses `ADD COLUMN IF NOT EXISTS`/`BOOLEAN`; MySQL uses plain `ADD COLUMN`/`TINYINT(1)`.

| Version | Description | Type |
|---|---|---|
| V120 | Extend mate_agent (+5 columns, extend agent_type values) | ALTER |
| V121 | Extend mate_conversation (+6 columns) | ALTER |
| V122 | Extend mate_message (+5 columns) | ALTER |
| V123 | Create mate_group_conversation | CREATE |
| V124 | Create mate_group_member | CREATE |
| V125 | Create mate_orchestrator_task | CREATE |
| V126 | Create mate_orchestrator_assignment | CREATE |
| V127 | Create mate_artifact | CREATE |
| V128 | Create mate_artifact_version | CREATE |
| V129 | Create mate_message_pin | CREATE |
| V130 | Create mate_message_reaction | CREATE |
| V131 | Create mate_deploy_record | CREATE |

### 3.2 Entity Conventions

All entities follow existing patterns:
- `@Data` + `@TableName("mate_xxx")` + `@TableId(type = IdType.ASSIGN_ID)`
- `deleted` (Integer, 0/1) for soft-delete on persistent entities
- `createTime`/`updateTime` with `@TableField(fill = FieldFill.INSERT/INSERT_UPDATE)`
- `workspaceId` on workspace-scoped entities
- `@TableField(updateStrategy = FieldStrategy.ALWAYS)` for nullable fields that must support clearing to null

### 3.3 Mapper Pattern

```java
@Mapper
public interface XxxMapper extends BaseMapper<XxxEntity> {}
```

All queries via `LambdaQueryWrapper`/`LambdaUpdateWrapper` in services. No XML mappers.

## 4. API Layer

### 4.1 Modified Existing Endpoints

**AgentController** — `/api/v1/agents`:
- `PUT /{id}/avatar` (member) — Upload avatar image
- `GET /{id}/stats` (viewer) — Usage statistics
- `GET /{id}/capabilities` (viewer) — Capability tags + config

**ConversationController** — `/api/v1/conversations`:
- `GET /` — Updated with `conversationType` filter, returns new fields
- `PUT /{id}/pin` — Toggle pin
- `PUT /{id}/archive` — Toggle archive
- `POST /batch-delete` — Batch delete
- `GET /{id}/pins` — List pinned messages

**MessageController** — `/api/v1/messages`:
- `GET /{id}` — Message detail with metadata
- `POST /{id}/regenerate` — Regenerate agent response
- `GET /{id}/reply-chain` — Threaded reply chain
- `GET /{id}/reactions` — Get reactions grouped by type
- `POST /{id}/reactions` — Add reaction
- `DELETE /{id}/reactions/{reactionType}` — Remove reaction

**ChatController** — New SSE event types:
- `orchestrator_plan` — Task decomposition plan
- `delegation_progress` — Sub-agent execution progress
- `artifact_preview` — Artifact generated

### 4.2 New Endpoints

**GroupConversationController** — `/api/v1/conversations/group`:
- `POST /` — Create group
- `GET /` — List groups
- `GET /{id}` — Group detail
- `PUT /{id}` — Update config
- `PUT /{id}/members` — Batch replace members
- `POST /{id}/members` — Add member
- `DELETE /{id}/members/{agentId}` — Remove member

**OrchestratorController** — `/api/v1/orchestrator`:
- `GET /tasks` — List tasks
- `GET /tasks/{taskId}` — Task detail
- `GET /tasks/{taskId}/assignments` — List assignments
- `GET /assignments/{id}` — Assignment detail
- `POST /tasks/{taskId}/retry` — Retry failed
- `POST /tasks/{taskId}/cancel` — Cancel

**ArtifactController** — `/api/v1/artifacts`:
- `GET /` — List artifacts
- `GET /{id}` — Detail
- `GET /{id}/versions` — Version history
- `GET /{id}/versions/{versionId}` — Version content
- `GET /{id}/versions/diff?from=&to=` — Version diff
- `POST /{id}/versions/{versionId}/restore` — Rollback
- `PUT /{id}/tags` — Update tags
- `POST /{id}/deploy` — Deploy (stubbed)
- `GET /{id}/deploy/status` — Deploy status
- `GET /{id}/deploy/history` — Deploy history

### 4.3 Response Format

All endpoints return `R<T>`: `{"code": 200, "message": "success", "data": {...}}`.

Paginated: `{"records": [...], "total": N, "current": P, "size": S, "pages": T}`.
Cursor-based (messages): `{"records": [...], "hasMore": bool, "nextBeforeId": N}`.

## 5. Business Logic

### 5.1 Agent Extensions

- **Status**: Managed via existing stream lifecycle hooks (BUSY on start, AVAILABLE on completion)
- **Avatar**: Upload to `{workspace-files}/avatars/{agentId}/`, max 2MB, image types only
- **Stats**: Aggregated from mate_message + mate_conversation; no new tables
- **agentType**: VARCHAR already exists; validate "orchestrator" in service layer

### 5.2 Conversation/Message Extensions

- `conversationType`: "direct" (default) / "group"; existing data stays "direct"
- `lastMessagePreview`: Updated atomically on message save (first 200 chars)
- `messageType`: "text"/"code"/"diff"/"image"/"file"/"preview_card"/"plan_card"/"system"

### 5.3 Group Conversations

- Create: validate agents exist/belong to workspace, orchestrator has correct type
- Member mgmt: forbid removing orchestrator; batch replace in transaction
- Listing: joins GroupConversation + GroupMember; sorts by lastActiveAt DESC

### 5.4 Orchestrator Flow

```
User message → Orchestrator LLM (Plan-Execute AgentGraph, agentType="orchestrator")
  → Plan JSON saved to mate_orchestrator_task
  → SSE: orchestrator_plan
  → For each step: create assignment, map to agent
  → Delegate via DelegateAgentTool (delegateParallel/delegateToAgent)
  → SSE: delegation_progress per agent
  → Collect results, create aggregation message
  → SSE: done
```

**Failure handling:**
- Single failure → retry once, then mark failed
- fail_fast → cancel remaining on first failure
- fail_tolerant → continue, mark task completed with partial failures
- Timeout: 300s per assignment (reuses existing config)

### 5.5 Artifact Management

- **Storage**: `ArtifactStorageProvider` interface → `LocalFileSystemProvider` impl
  - Path: `{workspace.base-dir}/artifacts/{artifactId}/{versionNumber}/{filename}`
- **Versioning**: SHA-256 content hash, unified diff from previous (text types only)
- **Rollback**: Copies target version content to new version number
- **Tags**: JSON array stored in VARCHAR(500)

### 5.6 Deployment (Stubbed)

- `NoopDeployProvider` logs request; sets status to "deployed" with local preview URL
- `DeployProvider` interface for future Vercel/static hosting implementations
- All endpoints wired and return valid responses

### 5.7 Message Pins & Reactions

- **Pins**: one per message (UK on message_id); list returns previews for context injection
- **Reactions**: append-only; UK on (message_id, user_id, reaction_type); "regenerate" triggers existing regenerate flow

## 6. Testing

### 6.1 Integration Tests (H2 in-memory)

Key critical path tests:
1. GroupConversationServiceTest — create, list, member add/remove, config update
2. OrchestratorServiceTest — task creation, assignment lifecycle, retry, cancel
3. ArtifactServiceTest — create, version, diff, rollback
4. ConversationServiceTest — extend with new fields (pin, archive, type filter)
5. MessagePinServiceTest + MessageReactionServiceTest

### 6.2 ArchUnit

Update existing ArchUnit rules to cover new packages (`group/`, `orchestrator/`, `artifact/`, `message/`). Follow existing layered architecture rules.

## 7. Error Handling

All errors follow the existing pattern:
- Service layer throws `BusinessException` (from `vip.mate.exception`)
- Controller layer or global exception handler maps to `R.fail(code, message)`
- Error codes from API document Section 1.8
- Resource-not-found → 404; permission-denied → 403; validation-error → 400

## 8. Estimated Artifacts

| Category | Files | LoC (approx) |
|---|---|---|
| DB Migrations | 24 (12 h2 + 12 mysql) | ~600 |
| Entities | 12 (3 modified + 9 new) | ~800 |
| Mappers | 9 new interfaces | ~90 |
| Services | 8 new + 3 modified | ~2500 |
| Controllers | 5 new + 3 modified | ~1200 |
| DTOs/VOs | ~15 request/response classes | ~600 |
| Storage/Diff | 3 utility classes | ~400 |
| SSE Events | 1 event types class | ~80 |
| Tests | 5 integration test classes | ~1000 |
| **Total** | ~85 files | ~7,300 |
