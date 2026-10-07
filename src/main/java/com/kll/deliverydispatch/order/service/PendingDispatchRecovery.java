package com.kll.deliverydispatch.order.service;

import com.kll.deliverydispatch.order.mapper.DeliveryOrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Retries older pending orders after their original event has reached RocketMQ. */
@Component
@ConditionalOnProperty(prefix = "dispatch.pending-recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class PendingDispatchRecovery {
    private static final int BATCH_SIZE = 20;

    private final DeliveryOrderMapper deliveryOrderMapper;
    private final DeliveryOrderService deliveryOrderService;
    private final MeterRegistry meterRegistry;
    private long lastScannedOrderId;

    @Scheduled(
            fixedDelayString = "${dispatch.pending-recovery.delay-ms:30000}",
            initialDelayString = "${dispatch.pending-recovery.delay-ms:30000}")
    public void retryPendingOrders() {
        List<Long> orderIds = deliveryOrderMapper.selectPendingPublishedIdsAfter(
                lastScannedOrderId, BATCH_SIZE);
        if (orderIds.isEmpty()) {
            lastScannedOrderId = 0;
            orderIds = deliveryOrderMapper.selectPendingPublishedIdsAfter(0, BATCH_SIZE);
        }
        for (Long orderId : orderIds) {
            lastScannedOrderId = orderId;
            try {
                // This method checks the current order state and uses the same
                // conditional MySQL updates as the RocketMQ consumer.
                deliveryOrderService.dispatchFromMessage(orderId);
                meterRegistry.counter("dispatch.recovery.attempts", "result", "processed")
                        .increment();
            } catch (NoAvailableRiderException e) {
                // No nearby rider is expected to change as riders come online.
                meterRegistry.counter("dispatch.recovery.attempts", "result", "no_rider")
                        .increment();
                log.debug("待派单订单仍无可用骑手，orderId={}", orderId);
            } catch (RuntimeException e) {
                meterRegistry.counter("dispatch.recovery.attempts", "result", "error")
                        .increment();
                log.warn("待派单订单补偿失败，下轮继续重试，orderId={}", orderId, e);
            }
        }
    }
}
