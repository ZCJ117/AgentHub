package vip.mate.orchestrator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.orchestrator.model.OrchestratorTaskEntity;

@Mapper
public interface OrchestratorTaskMapper extends BaseMapper<OrchestratorTaskEntity> {}
