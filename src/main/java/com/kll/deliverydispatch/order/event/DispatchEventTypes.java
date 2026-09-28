package com.kll.deliverydispatch.order.event;

/**
 * 派单事件类型编码，保存在 Outbox 和 RocketMQ 消息中。
 */
public final class DispatchEventTypes {
    public static final String DISPATCH_REQUEST = "DISPATCH_REQUEST";

    private DispatchEventTypes() {
    }
}
