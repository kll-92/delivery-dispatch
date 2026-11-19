package com.kll.deliverydispatch.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待投递到 RocketMQ 的派单事件记录。
 */
@Data
@TableName("dispatch_outbox")
public class DispatchOutboxMessage {
    public static final int PENDING = 0;
    public static final int PUBLISHED = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String eventType;
    /** 下单时产生的 traceId，用于把异步派单日志串回原请求。 */
    private String traceId;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
}
