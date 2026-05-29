package vip.mate.artifact.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.artifact.model.DeployRecordEntity;

@Mapper
public interface DeployRecordMapper extends BaseMapper<DeployRecordEntity> {}
