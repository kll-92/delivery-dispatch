package com.kll.deliverydispatch.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.order.event.DispatchEventTypes;
import com.kll.deliverydispatch.order.service.DeliveryOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class DispatchRocketMqConsumerTest {
    @Mock
    private DeliveryOrderService deliveryOrderService;

    private ObjectMapper objectMapper;
    private DispatchRocketMqConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        consumer = new DispatchRocketMqConsumer(objectMapper, deliveryOrderService);
    }

    @Test
    void shouldDispatchKnownEvent() throws Exception {
        String payload = objectMapper.writeValueAsString(new DispatchMessage(
                7L, 42L, DispatchEventTypes.DISPATCH_REQUEST, Instant.now()));

        consumer.onMessage(payload);

        verify(deliveryOrderService).dispatchFromMessage(42L);
    }

    @Test
    void shouldIgnoreUnknownEvent() throws Exception {
        String payload = objectMapper.writeValueAsString(new DispatchMessage(
                7L, 42L, "UNKNOWN", Instant.now()));

        consumer.onMessage(payload);

        verify(deliveryOrderService, never()).dispatchFromMessage(42L);
    }

    @Test
    void shouldRethrowBusinessFailureForRocketMqRetry() throws Exception {
        String payload = objectMapper.writeValueAsString(new DispatchMessage(
                7L, 42L, DispatchEventTypes.DISPATCH_REQUEST, Instant.now()));
        doThrow(new IllegalArgumentException("暂无可用骑手"))
                .when(deliveryOrderService).dispatchFromMessage(42L);

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(payload));
    }
}
