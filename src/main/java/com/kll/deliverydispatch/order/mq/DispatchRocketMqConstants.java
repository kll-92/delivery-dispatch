package com.kll.deliverydispatch.order.mq;

public final class DispatchRocketMqConstants {
    public static final String TOPIC = "delivery-dispatch-events";
    public static final String TAG = "dispatch-request";
    public static final String CONSUMER_GROUP = "delivery-dispatch-workers";
    public static final String DESTINATION = TOPIC + ":" + TAG;

    private DispatchRocketMqConstants() {
    }
}
