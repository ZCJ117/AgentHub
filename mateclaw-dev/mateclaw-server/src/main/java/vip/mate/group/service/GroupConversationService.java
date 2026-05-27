package vip.mate.group.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.group.model.*;
import vip.mate.group.repository.*;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.io.File;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupConversationService {

    private final GroupConversationMapper groupConversationMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final ConversationMapper conversationMapper;
    private final AgentMapper agentMapper;

    private static final String CLAUDE_MD_BASE_DIR =
            System.getProperty("java.io.tmpdir") + "/agenthub-claude-md";

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * 创建群聊会话 — API 文档 6. Create Group Conversation
     */
    @Transactional
    public Map<String, Object> createGroup(String username, Long workspaceId, String title,
                                            List<Long> agentIds,
                                            String schedulingMode, String failurePolicy, Integer maxParallelTasks) {
        if (agentIds == null || agentIds.size() < 2) {
            throw new MateClawException("err.group.min_agents", "群聊至少需要 2 个 Agent");
        }

        // Orchestrator is now handled by arther-agent Agent01 (agents.yml "000001").
        // No local_cli Orchestrator needed — the first user message in the group
        // will be routed to Agent01 via ArtherAgentClient.

        // Create conversation
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId(UUID.randomUUID().toString());
        conv.setTitle(title);
        conv.setAgentId(null);  // group chat uses arther-agent orchestrator, no single agent
        conv.setUsername(username);
        conv.setMessageCount(0);
        conv.setConversationType("group");
        conv.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
        conv.setArchived(0);
        conv.setUnreadCount(0);
        conv.setLastActiveAt(LocalDateTime.now());
        conversationMapper.insert(conv);

        // Create group config
        GroupConversationEntity gc = new GroupConversationEntity();
        gc.setConversationId(conv.getId());
        gc.setOrchestratorAgentId(null);  // Orchestrator is external (arther-agent Agent01)
        gc.setSchedulingMode(schedulingMode != null ? schedulingMode : "auto");
        gc.setFailurePolicy(failurePolicy != null ? failurePolicy : "fail_tolerant");
        gc.setMaxParallelTasks(maxParallelTasks != null ? maxParallelTasks : 8);
        gc.setCreatedAt(LocalDateTime.now());
        gc.setUpdatedAt(LocalDateTime.now());
        groupConversationMapper.insert(gc);

        // Agent01 from arther-agent acts as orchestrator (not a DB member)
        // Add other agents
        for (Long agentId : agentIds) {
            addMemberInternal(conv.getId(), agentId, "member");
        }

        return buildGroupResponse(conv, gc);
    }

    private void addMemberInternal(Long conversationId, Long agentId, String role) {
        GroupMemberEntity member = new GroupMemberEntity();
        member.setConversationId(conversationId);
        member.setAgentId(agentId);
        member.setMemberRole(role);
        member.setJoinedAt(LocalDateTime.now());
        groupMemberMapper.insert(member);
    }

    /**
     * 列出群聊会话 — API 文档 6.
     */
    public IPage<Map<String, Object>> listGroups(String username, int page, int size) {
        Page<ConversationEntity> pageReq = new Page<>(page, size);
        IPage<ConversationEntity> result = conversationMapper.selectPage(pageReq,
            new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getUsername, username)
                .eq(ConversationEntity::getConversationType, "group")
                .eq(ConversationEntity::getDeleted, 0)
                .orderByDesc(ConversationEntity::getLastActiveAt));
        return result.convert(conv -> {
            GroupConversationEntity gc = groupConversationMapper.selectOne(
                new LambdaQueryWrapper<GroupConversationEntity>()
                    .eq(GroupConversationEntity::getConversationId, conv.getId()));
            return buildGroupResponse(conv, gc);
        });
    }

    /**
     * 获取群聊详情
     */
    public Map<String, Object> getGroupDetail(Long conversationId) {
        ConversationEntity conv = conversationMapper.selectById(conversationId);
        if (conv == null || !"group".equals(conv.getConversationType())) {
            throw new MateClawException("err.group.not_found", "群聊会话不存在");
        }
        GroupConversationEntity gc = groupConversationMapper.selectOne(
            new LambdaQueryWrapper<GroupConversationEntity>()
                .eq(GroupConversationEntity::getConversationId, conversationId));
        return buildGroupResponse(conv, gc);
    }

    /**
     * 更新群聊配置
     */
    @Transactional
    public void updateGroupConfig(Long conversationId, Map<String, Object> body) {
        GroupConversationEntity gc = groupConversationMapper.selectOne(
            new LambdaQueryWrapper<GroupConversationEntity>()
                .eq(GroupConversationEntity::getConversationId, conversationId));
        if (gc == null) throw new MateClawException("err.group.not_found", "群聊不存在");
        if (body.containsKey("schedulingMode")) gc.setSchedulingMode((String) body.get("schedulingMode"));
        if (body.containsKey("failurePolicy")) gc.setFailurePolicy((String) body.get("failurePolicy"));
        if (body.containsKey("maxParallelTasks")) gc.setMaxParallelTasks(Integer.valueOf(body.get("maxParallelTasks").toString()));
        groupConversationMapper.updateById(gc);
    }

    /**
     * 添加成员
     */
    @Transactional
    public void addMember(Long conversationId, Long agentId, String role) {
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null) throw new MateClawException("err.group.agent_not_found", "Agent 不存在");
        addMemberInternal(conversationId, agentId, role != null ? role : "member");
    }

    /**
     * 移除成员 — 不允许移除 orchestrator
     */
    @Transactional
    public void removeMember(Long conversationId, Long agentId) {
        GroupMemberEntity member = groupMemberMapper.selectOne(
            new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getConversationId, conversationId)
                .eq(GroupMemberEntity::getAgentId, agentId));
        if (member == null) throw new MateClawException("err.group.member_not_found", "成员不存在");
        if ("orchestrator".equals(member.getMemberRole())) {
            throw new MateClawException("err.group.cannot_remove_orchestrator", "不能移除 Orchestrator");
        }
        groupMemberMapper.deleteById(member.getId());
    }

    /**
     * 批量替换成员
     */
    @Transactional
    public void batchReplaceMembers(Long conversationId, List<Long> agentIds) {
        // Remove all non-orchestrator members
        List<GroupMemberEntity> existing = groupMemberMapper.selectList(
            new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getConversationId, conversationId));
        for (GroupMemberEntity m : existing) {
            if (!"orchestrator".equals(m.getMemberRole())) {
                groupMemberMapper.deleteById(m.getId());
            }
        }
        // Add new members
        for (Long agentId : agentIds) {
            addMemberInternal(conversationId, agentId, "member");
        }
    }

    private Map<String, Object> buildGroupResponse(ConversationEntity conv, GroupConversationEntity gc) {
        List<GroupMemberEntity> members = groupMemberMapper.selectList(
            new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getConversationId, conv.getId()));
        List<Map<String, Object>> memberList = members.stream().map(m -> {
            AgentEntity agent = agentMapper.selectById(m.getAgentId());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("agentId", m.getAgentId());
            map.put("agentName", agent != null ? agent.getName() : "Unknown");
            map.put("memberRole", m.getMemberRole());
            map.put("joinedAt", m.getJoinedAt() != null ? m.getJoinedAt().toString() : null);
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conv.getId());
        result.put("title", conv.getTitle());
        result.put("conversationType", conv.getConversationType());
        result.put("groupConfig", Map.of(
            "orchestratorAgentId", gc.getOrchestratorAgentId(),
            "schedulingMode", gc.getSchedulingMode(),
            "failurePolicy", gc.getFailurePolicy(),
            "maxParallelTasks", gc.getMaxParallelTasks()
        ));
        result.put("members", memberList);
        result.put("createdAt", conv.getCreateTime() != null ? conv.getCreateTime().toString() : null);
        return result;
    }

    /**
     * Build a [群聊上下文] prompt block listing all group members.
     * Returns empty string if the conversation is not a group chat.
     */
    public String buildGroupMemberContextPrompt(String conversationId) {
        ConversationEntity conv = conversationMapper.selectOne(
            new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        if (conv == null || !"group".equals(conv.getConversationType())) return "";

        List<GroupMemberEntity> members = groupMemberMapper.selectList(
            new LambdaQueryWrapper<GroupMemberEntity>()
                .eq(GroupMemberEntity::getConversationId, conv.getId()));
        if (members.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("[群聊上下文]\n");
        sb.append("你是此群聊的协调者(Orchestrator)。群聊成员如下：\n\n");
        for (GroupMemberEntity m : members) {
            AgentEntity ag = agentMapper.selectById(m.getAgentId());
            if (ag == null) continue;
            sb.append("- ").append(ag.getName());
            sb.append(" (").append(m.getMemberRole()).append(")");
            if ("orchestrator".equals(m.getMemberRole())) {
                sb.append(" [你自己]");
            }
            if (ag.getDescription() != null && !ag.getDescription().isBlank()) {
                sb.append(" — ").append(ag.getDescription());
            }
            sb.append("\n");
        }
        sb.append("\n使用 delegateToAgent(agentName, task) 将子任务分派给特定成员Agent。");
        sb.append("如果用户没有明确指定由谁执行，请分析每个Agent的能力描述后自行决定。");
        sb.append("\n使用 delegateParallel 可以同时向多个Agent分派独立任务。\n");
        sb.append("[/群聊上下文]");
        return sb.toString();
    }

    /**
     * Get the CLAUDE.md file path for an agent in a group conversation.
     */
    public String getClaudeMdPath(Long conversationDbId, String agentName) {
        File file = new File(CLAUDE_MD_BASE_DIR + "/" + conversationDbId + "/" + sanitizeFilename(agentName) + ".md");
        return file.exists() ? file.getAbsolutePath() : null;
    }

    /**
     * Build a name->agent lookup map for @AgentName dispatch.
     */
    public Map<String, AgentEntity> buildAgentNameMap(Long conversationDbId) {
        List<GroupMemberEntity> members = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMemberEntity>()
                        .eq(GroupMemberEntity::getConversationId, conversationDbId));
        Map<String, AgentEntity> map = new LinkedHashMap<>();
        for (GroupMemberEntity m : members) {
            AgentEntity ag = agentMapper.selectById(m.getAgentId());
            if (ag != null) {
                map.put(ag.getName(), ag);
            }
        }
        return map;
    }

    /**
     * Get the group configuration for a conversation by its database primary key.
     * Returns a map with orchestratorAgentId, schedulingMode, failurePolicy,
     * and maxParallelTasks. Returns null if the conversation is not a group chat.
     *
     * <p>根据会话数据库主键获取群聊配置。返回包含 orchestratorAgentId、
     * schedulingMode、failurePolicy、maxParallelTasks 的 Map。
     * 若不是群聊则返回 null。
     */
    public Map<String, Object> getGroupConfig(Long conversationDbId) {
        GroupConversationEntity gc = groupConversationMapper.selectOne(
                new LambdaQueryWrapper<GroupConversationEntity>()
                        .eq(GroupConversationEntity::getConversationId, conversationDbId));
        if (gc == null) return null;
        return Map.of(
                "orchestratorAgentId", gc.getOrchestratorAgentId(),
                "schedulingMode", gc.getSchedulingMode(),
                "failurePolicy", gc.getFailurePolicy(),
                "maxParallelTasks", gc.getMaxParallelTasks()
        );
    }
}
