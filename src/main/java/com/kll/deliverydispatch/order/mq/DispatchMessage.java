package com.kll.deliverydispatch.order.mq;

import java.time.Instant;

public record DispatchMessage(
        Long outboxId,
        Long orderId,
        String eventType,
        Instant createdAt
) {
}
