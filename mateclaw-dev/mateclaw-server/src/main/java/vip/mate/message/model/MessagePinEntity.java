package vip.mate.message.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_message_pin")
public class MessagePinEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long messageId;
    private Long conversationId;
    private Long pinnedBy;
    private String note;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
