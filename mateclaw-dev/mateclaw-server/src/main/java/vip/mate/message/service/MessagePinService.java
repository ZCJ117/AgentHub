package vip.mate.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.message.model.MessagePinEntity;
import vip.mate.message.repository.MessagePinMapper;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MessagePinService {

    private final MessagePinMapper pinMapper;

    @Transactional
    public MessagePinEntity pinMessage(Long messageId, Long conversationId, Long userId, String note) {
        MessagePinEntity existing = pinMapper.selectOne(
            new LambdaQueryWrapper<MessagePinEntity>()
                .eq(MessagePinEntity::getMessageId, messageId));
        if (existing != null) {
            existing.setNote(note);
            pinMapper.updateById(existing);
            return existing;
        }
        MessagePinEntity pin = new MessagePinEntity();
        pin.setMessageId(messageId);
        pin.setConversationId(conversationId);
        pin.setPinnedBy(userId);
        pin.setNote(note);
        pinMapper.insert(pin);
        return pin;
    }

    @Transactional
    public void unpinMessage(Long messageId) {
        pinMapper.delete(new LambdaQueryWrapper<MessagePinEntity>()
            .eq(MessagePinEntity::getMessageId, messageId));
    }

    public List<MessagePinEntity> listPins(Long conversationId) {
        return pinMapper.selectList(
            new LambdaQueryWrapper<MessagePinEntity>()
                .eq(MessagePinEntity::getConversationId, conversationId)
                .orderByDesc(MessagePinEntity::getCreatedAt));
    }
}
