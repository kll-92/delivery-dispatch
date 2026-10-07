package com.kll.deliverydispatch.order.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.order.event.DispatchEventTypes;
import com.kll.deliverydispatch.order.service.DeliveryOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 正常返回表示消费成功；抛出异常时 RocketMQ 按消费组配置重试，超过上限后进入死信队列。
 */
@Component
@ConditionalOnProperty(prefix = "dispatch.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
@RocketMQMessageListener(
        topic = DispatchRocketMqConstants.TOPIC,
        selectorExpression = DispatchRocketMqConstants.TAG,
        consumerGroup = DispatchRocketMqConstants.CONSUMER_GROUP,
        maxReconsumeTimes = 5
)
public class DispatchRocketMqConsumer implements RocketMQListener<String> {
    private final ObjectMapper objectMapper;
    private final DeliveryOrderService deliveryOrderService;

    @Override
    public void onMessage(String payload) {
        DispatchMessage message = readMessage(payload);
        if (!DispatchEventTypes.DISPATCH_REQUEST.equals(message.eventType())) {
            log.warn("忽略未知 RocketMQ 派单事件，outboxId={}, eventType={}",
                    message.outboxId(), message.eventType());
            return;
        }

        try {
            deliveryOrderService.dispatchFromMessage(message.orderId());
            log.info("RocketMQ 派单事件处理成功，outboxId={}, orderId={}",
                    message.outboxId(), message.orderId());
        } catch (RuntimeException e) {
            log.warn("RocketMQ 派单事件处理失败，将由 Broker 重试，outboxId={}, orderId={}",
                    message.outboxId(), message.orderId(), e);
            throw e;
        }
    }

    private DispatchMessage readMessage(String payload) {
        try {
            return objectMapper.readValue(payload, DispatchMessage.class);
        } catch (JsonProcessingException e) {
            // 坏消息也交给 RocketMQ 重试和死信机制，避免静默丢弃。
            throw new IllegalArgumentException("无法解析 RocketMQ 派单消息", e);
        }
    }
}
