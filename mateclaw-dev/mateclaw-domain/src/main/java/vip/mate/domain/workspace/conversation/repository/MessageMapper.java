package vip.mate.domain.workspace.conversation.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.workspace.conversation.model.MessageEntity;

/**
 * 消息数据访问层
 *
 * @author MateClaw Team
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
}
