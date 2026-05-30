package vip.mate.domain.group.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.group.model.GroupMemberEntity;

@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMemberEntity> {}
