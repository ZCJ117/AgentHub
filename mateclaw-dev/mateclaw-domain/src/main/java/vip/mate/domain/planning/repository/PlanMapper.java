package vip.mate.domain.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.planning.model.PlanEntity;

/**
 * 计划 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface PlanMapper extends BaseMapper<PlanEntity> {
}
