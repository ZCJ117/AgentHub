package vip.mate.domain.tool.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.tool.model.ToolEntity;

/**
 * 工具 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface ToolMapper extends BaseMapper<ToolEntity> {
}
