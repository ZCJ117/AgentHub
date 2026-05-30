package vip.mate.domain.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.memory.model.DreamReportEntity;

/**
 * Mapper for mate_dream_report table.
 *
 * @author MateClaw Team
 */
@Mapper
public interface DreamReportMapper extends BaseMapper<DreamReportEntity> {
}
