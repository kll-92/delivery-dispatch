package com.kll.deliverydispatch.order.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.order.entity.DispatchOutboxMessage;
import com.kll.deliverydispatch.order.mapper.DispatchOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 定时把 MySQL Outbox 中尚未发布的事件发送到 RocketMQ。
 */
@Component
@ConditionalOnProperty(prefix = "dispatch.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DispatchOutboxPublisher {
    private static final int BATCH_SIZE = 20;

    private final DispatchOutboxMapper dispatchOutboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${dispatch.outbox.publish-delay-ms:5000}")
    @Transactional
    public void publishPendingMessages() {
        List<DispatchOutboxMessage> messages =
                dispatchOutboxMapper.selectPendingForUpdate(BATCH_SIZE);
        for (DispatchOutboxMessage message : messages) {
            SendResult sendResult;
            try {
                String payload = objectMapper.writeValueAsString(new DispatchMessage(
                        message.getId(),
                        message.getOrderId(),
                        message.getEventType(),
                        Instant.now()));
                sendResult = rocketMQTemplate.syncSend(DispatchRocketMqConstants.DESTINATION, payload);
            } catch (JsonProcessingException | RuntimeException e) {
                // 不标记为已发布；事务结束后释放行锁，下一轮会继续尝试。
                log.warn("派单 Outbox 写入 RocketMQ 失败，保留待重试，outboxId={}",
                        message.getId(), e);
                break;
            }

            if (sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
                log.warn("RocketMQ 未确认派单消息发送成功，Outbox 保留待重试，outboxId={}, sendResult={}",
                        message.getId(), sendResult);
                break;
            }

            int updated = dispatchOutboxMapper.markAsPublished(message.getId());
            if (updated != 1) {
                // Broker 可能已经收到消息；事务回滚后再次发送会重复，消费者按订单状态幂等处理。
                throw new IllegalStateException("Outbox 状态更新失败，outboxId=" + message.getId());
            }
            log.info("派单 Outbox 已发布到 RocketMQ，outboxId={}, messageId={}",
                    message.getId(), sendResult.getMsgId());
        }
    }
}
