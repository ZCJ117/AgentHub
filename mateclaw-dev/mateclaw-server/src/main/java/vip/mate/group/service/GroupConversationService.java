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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupConversationService {

    private final GroupConversationMapper groupConversationMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final ConversationMapper conversationMapper;
    private final AgentMapper agentMapper;

    /**
     * 创建群聊会话 — API 文档 6. Create Group Conversation
     */
    @Transactional
    public Map<String, Object> createGroup(String username, Long workspaceId, String title,
                                            Long orchestratorAgentId, List<Long> agentIds,
                                            String schedulingMode, String failurePolicy, Integer maxParallelTasks) {
        if (agentIds == null || agentIds.size() < 2) {
            throw new MateClawException("err.group.min_agents", "群聊至少需要 2 个 Agent");
        }
        AgentEntity orchestrator = agentMapper.selectById(orchestratorAgentId);
        if (orchestrator == null) {
            throw new MateClawException("err.group.invalid_orchestrator", "指定的 Orchestrator Agent 不存在");
        }
        boolean validOrch = "orchestrator".equals(orchestrator.getAgentType())
                || ("local_cli".equals(orchestrator.getAgentType())
                    && "claude_code".equals(orchestrator.getCliType()));
        if (!validOrch) {
            throw new MateClawException("err.group.invalid_orchestrator",
                    "Orchestrator 必须是 Claude Code 类型");
        }
        // Create conversation
        ConversationEntity conv = new ConversationEntity();
        conv.setConversationId(UUID.randomUUID().toString());
        conv.setTitle(title);
        conv.setAgentId(orchestratorAgentId);
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
        gc.setOrchestratorAgentId(orchestratorAgentId);
        gc.setSchedulingMode(schedulingMode != null ? schedulingMode : "auto");
        gc.setFailurePolicy(failurePolicy != null ? failurePolicy : "fail_tolerant");
        gc.setMaxParallelTasks(maxParallelTasks != null ? maxParallelTasks : 8);
        groupConversationMapper.insert(gc);

        // Add orchestrator as member
        addMemberInternal(conv.getId(), orchestratorAgentId, "orchestrator");
        // Add other agents
        for (Long agentId : agentIds) {
            if (!agentId.equals(orchestratorAgentId)) {
                addMemberInternal(conv.getId(), agentId, "member");
            }
        }

        return buildGroupResponse(conv, gc);
    }

    private void addMemberInternal(Long conversationId, Long agentId, String role) {
        GroupMemberEntity member = new GroupMemberEntity();
        member.setConversationId(conversationId);
        member.setAgentId(agentId);
        member.setMemberRole(role);
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
}
