package vip.mate.tool.guard.model;

import java.util.Map;

/**
 * 工具调用上下文
 * <p>
 * 标准化的工具调用信息，供所有 Guardian 使用。
 * 先标准化上下文，再做风险评估。
 */
public record ToolInvocationContext(
        String toolName,
        Map<String, Object> parameters,
        String rawArguments,
        String conversationId,
        String agentId,
        String channelType,
        String userId
) {

    /**
     * 常用工厂方法 — 从工具名和原始参数创建
     */
    public static ToolInvocationContext of(String toolName, String rawArguments,
                                           String conversationId, String agentId) {
        return new ToolInvocationContext(
                toolName, Map.of(), rawArguments, conversationId, agentId, null, null
        );
    }

    /**
     * 完整工厂方法
     */
    public static ToolInvocationContext of(String toolName, Map<String, Object> parameters,
                                           String rawArguments, String conversationId,
                                           String agentId, String channelType, String userId) {
        return new ToolInvocationContext(
                toolName, parameters != null ? parameters : Map.of(),
                rawArguments, conversationId, agentId, channelType, userId
        );
    }
}
