package vip.mate.domain.message.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_message_reaction")
public class MessageReactionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long messageId;
    private Long userId;
    private String reactionType;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
