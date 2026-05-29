package domain.agent.service;

import domain.agent.model.entity.ChatCommandEntity;
import domain.agent.model.valobj.AiAgentConfigTableVO;
import domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 对话接口
 */
public interface IChatService {

    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    Flowable<RuntimeEvent> handleMessageStream(String agentId, String userId, String sessionId, String message);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

}
