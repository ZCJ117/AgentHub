package vip.mate.domain.artifact.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.artifact.model.ArtifactEntity;

@Mapper
public interface ArtifactMapper extends BaseMapper<ArtifactEntity> {}
