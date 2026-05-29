package domain.agent.service.armory.node.workflow;

import domain.agent.model.entity.ArmoryCommandEntity;
import domain.agent.model.valobj.AiAgentConfigTableVO;
import domain.agent.model.valobj.AiAgentRegisterVO;
import domain.agent.service.armory.AbstractArmorySupport;
import domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 并行工作流节点 — 记录并行配置元数据
 *
 * 实际并行执行由 GraphExecutor 在运行时处理
 */
@Slf4j
@Service("parallelAgentNode")
public class ParallelAgentNode extends AbstractArmorySupport {

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ParallelAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();
        log.info("记录并行工作流: {} subAgents={}",
                currentAgentWorkflow.getName(),
                currentAgentWorkflow.getSubAgents());

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }
}
