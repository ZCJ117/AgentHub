package vip.mate.domain.orchestrator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.orchestrator.model.OrchestratorTaskEntity;

@Mapper
public interface OrchestratorTaskMapper extends BaseMapper<OrchestratorTaskEntity> {}
