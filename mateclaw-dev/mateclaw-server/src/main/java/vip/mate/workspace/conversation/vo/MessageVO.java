package vip.mate.workspace.conversation.vo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
public class MessageVO {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Long id;

    private String conversationId;

    private String role;

    private String content;

    private String toolName;

    private String status;

    /**
     * Agent 事件元数据：toolCalls, segments 等。
     * 类型为 Object，Jackson 序列化时直接输出 JSON 对象（而非字符串），
     * 前端无需额外 parse。
     */
    private Object metadata;

    /** Prompt tokens 消耗 */
    private Integer promptTokens;

    /** Completion tokens 消耗 */
    private Integer completionTokens;

    /** Model name actually used to produce this message (e.g. "deepseek-chat"). */
    private String runtimeModel;

    /** Provider id of the runtime model (e.g. "deepseek", "zhipu"). */
    private String runtimeProvider;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private List<MessageContentPart> contentParts;

    // === 以下为新增展示字段 ===

    /** 消息类型：text / tool_call / system / artifact */
    private String messageType;

    /** 回复的消息 ID（引用回复） */
    private Long replyToId;

    /** 发送者 Agent ID（群聊场景，标识由哪个 Agent 发送） */
    private Long senderAgentId;

    /** 发送者 Agent 名称 */
    private String senderAgentName;

    /** 关联产物引用列表（JSON 数组字符串） */
    private String artifactRefs;

    /** 重新生成来源消息 ID */
    private Long regeneratedFromId;

    public static MessageVO from(MessageEntity entity, List<MessageContentPart> contentParts, String renderedContent) {
        MessageVO vo = new MessageVO();
        vo.setId(entity.getId());
        vo.setConversationId(entity.getConversationId());
        vo.setRole(entity.getRole());
        vo.setContent(renderedContent);
        vo.setToolName(entity.getToolName());
        vo.setStatus(entity.getStatus());
        // 将 JSON 字符串解析为 Map，Jackson 序列化时直接输出对象而非转义字符串
        vo.setMetadata(parseMetadataToObject(entity.getMetadata()));
        vo.setPromptTokens(entity.getPromptTokens());
        vo.setCompletionTokens(entity.getCompletionTokens());
        vo.setRuntimeModel(entity.getRuntimeModel());
        vo.setRuntimeProvider(entity.getRuntimeProvider());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setContentParts(contentParts);
        // 复制新增字段
        vo.setMessageType(entity.getMessageType());
        vo.setReplyToId(entity.getReplyToId());
        vo.setSenderAgentId(entity.getSenderAgentId());
        vo.setArtifactRefs(entity.getArtifactRefs());
        vo.setRegeneratedFromId(entity.getRegeneratedFromId());
        return vo;
    }

    private static Object parseMetadataToObject(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            // H2 JSON 列可能返回带引号包裹的字符串，需要先 unwrap
            String json = metadataJson.trim();
            if (json.startsWith("\"") && json.endsWith("\"")) {
                // 双重包裹：H2 JSON 类型 getString() 返回了 JSON string literal
                json = MAPPER.readValue(json, String.class);
            }
            if (json.isBlank() || "{}".equals(json)) {
                return Collections.emptyMap();
            }
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // 解析失败时返回空 map 而非原始字符串，避免前端拿到字符串
            return Collections.emptyMap();
        }
    }
}
