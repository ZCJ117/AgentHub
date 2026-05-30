package vip.mate.domain.approval.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.approval.model.ToolApprovalEntity;

@Mapper
public interface ToolApprovalMapper extends BaseMapper<ToolApprovalEntity> {
}
