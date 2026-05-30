package vip.mate.domain.workspace.conversation.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.workspace.conversation.model.ConversationEntity;

/**
 * 会话 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}
