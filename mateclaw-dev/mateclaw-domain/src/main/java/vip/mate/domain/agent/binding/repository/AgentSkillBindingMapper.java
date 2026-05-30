package vip.mate.domain.agent.binding.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.agent.binding.model.AgentSkillBinding;

@Mapper
public interface AgentSkillBindingMapper extends BaseMapper<AgentSkillBinding> {
}
