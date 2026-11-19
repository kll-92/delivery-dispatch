package com.kll.deliverydispatch.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.common.trace.TraceContext;
import com.kll.deliverydispatch.order.event.DispatchEventTypes;
import com.kll.deliverydispatch.order.service.DeliveryOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
                7L, 42L, DispatchEventTypes.DISPATCH_REQUEST, "trace-1", Instant.now()));

        consumer.onMessage(payload);

        verify(deliveryOrderService).dispatchFromMessage(42L);
    }

    @Test
    void shouldIgnoreUnknownEvent() throws Exception {
        String payload = objectMapper.writeValueAsString(new DispatchMessage(
                7L, 42L, "UNKNOWN", "trace-1", Instant.now()));

        consumer.onMessage(payload);

        verify(deliveryOrderService, never()).dispatchFromMessage(42L);
    }

    @Test
    void shouldRethrowBusinessFailureForRocketMqRetry() throws Exception {
        String payload = objectMapper.writeValueAsString(new DispatchMessage(
                7L, 42L, DispatchEventTypes.DISPATCH_REQUEST, "trace-1", Instant.now()));
        doThrow(new IllegalArgumentException("暂无可用骑手"))
                .when(deliveryOrderService).dispatchFromMessage(42L);

        assertThrows(IllegalArgumentException.class, () -> consumer.onMessage(payload));
    }

    /** 消费线程是新线程，MDC 是空的；处理中要恢复，处理完必须清理。 */
    @Test
    void restoresTraceIdWhileHandlingAndClearsAfterwards() throws Exception {
        String payload = objectMapper.writeValueAsString(new DispatchMessage(
                7L, 42L, DispatchEventTypes.DISPATCH_REQUEST, "trace-abc", Instant.now()));

        AtomicReference<String> traceIdSeenByService = new AtomicReference<>();
        doAnswer(invocation -> {
            traceIdSeenByService.set(TraceContext.current());
            return null;
        }).when(deliveryOrderService).dispatchFromMessage(42L);

        consumer.onMessage(payload);

        assertEquals("trace-abc", traceIdSeenByService.get(), "处理过程中 MDC 应带上消息里的 traceId");
        assertNull(TraceContext.current(), "处理结束后必须清理 MDC，避免污染线程池里的下一条消息");
    }
}
