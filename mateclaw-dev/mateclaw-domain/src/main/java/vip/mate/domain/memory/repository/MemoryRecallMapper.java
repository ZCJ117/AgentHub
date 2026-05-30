package vip.mate.domain.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.memory.model.MemoryRecallEntity;

/**
 * 记忆召回追踪 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface MemoryRecallMapper extends BaseMapper<MemoryRecallEntity> {
}
