package vip.mate.domain.audit.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.audit.model.AuditEventEntity;

@Mapper
public interface AuditEventMapper extends BaseMapper<AuditEventEntity> {
}
