package vip.mate.domain.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.agent.model.AgentEntity;

/**
 * Agent 数据访问层
 *
 * @author MateClaw Team
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
}
