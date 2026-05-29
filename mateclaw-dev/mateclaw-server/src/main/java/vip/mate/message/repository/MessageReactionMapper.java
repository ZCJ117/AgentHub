package vip.mate.message.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.message.model.MessageReactionEntity;

@Mapper
public interface MessageReactionMapper extends BaseMapper<MessageReactionEntity> {}
