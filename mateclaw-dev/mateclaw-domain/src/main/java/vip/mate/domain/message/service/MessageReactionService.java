package vip.mate.domain.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.domain.message.model.MessageReactionEntity;
import vip.mate.domain.message.repository.MessageReactionMapper;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageReactionService {

    private final MessageReactionMapper reactionMapper;

    public void addReaction(Long messageId, Long userId, String reactionType) {
        MessageReactionEntity existing = reactionMapper.selectOne(
            new LambdaQueryWrapper<MessageReactionEntity>()
                .eq(MessageReactionEntity::getMessageId, messageId)
                .eq(MessageReactionEntity::getUserId, userId)
                .eq(MessageReactionEntity::getReactionType, reactionType));
        if (existing == null) {
            MessageReactionEntity r = new MessageReactionEntity();
            r.setMessageId(messageId);
            r.setUserId(userId);
            r.setReactionType(reactionType);
            reactionMapper.insert(r);
        }
    }

    public void removeReaction(Long messageId, Long userId, String reactionType) {
        reactionMapper.delete(new LambdaQueryWrapper<MessageReactionEntity>()
            .eq(MessageReactionEntity::getMessageId, messageId)
            .eq(MessageReactionEntity::getUserId, userId)
            .eq(MessageReactionEntity::getReactionType, reactionType));
    }

    public Map<String, List<MessageReactionEntity>> getReactionsGrouped(Long messageId) {
        List<MessageReactionEntity> reactions = reactionMapper.selectList(
            new LambdaQueryWrapper<MessageReactionEntity>()
                .eq(MessageReactionEntity::getMessageId, messageId));
        return reactions.stream().collect(Collectors.groupingBy(MessageReactionEntity::getReactionType));
    }
}
