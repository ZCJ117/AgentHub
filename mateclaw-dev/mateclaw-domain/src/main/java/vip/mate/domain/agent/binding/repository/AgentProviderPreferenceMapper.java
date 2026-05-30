package vip.mate.domain.agent.binding.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.agent.binding.model.AgentProviderPreference;

@Mapper
public interface AgentProviderPreferenceMapper extends BaseMapper<AgentProviderPreference> {
}
