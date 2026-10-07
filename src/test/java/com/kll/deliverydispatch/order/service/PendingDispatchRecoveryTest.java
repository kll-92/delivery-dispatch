package com.kll.deliverydispatch.order.service;

import com.kll.deliverydispatch.order.mapper.DeliveryOrderMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingDispatchRecoveryTest {
    @Mock
    private DeliveryOrderMapper deliveryOrderMapper;

    @Mock
    private DeliveryOrderService deliveryOrderService;

    @Test
    void shouldRetryAnUnassignedOrderOnTheNextSweep() {
        when(deliveryOrderMapper.selectPendingPublishedIdsAfter(0, 20))
                .thenReturn(List.of(42L));
        when(deliveryOrderMapper.selectPendingPublishedIdsAfter(42, 20))
                .thenReturn(List.of());
        doThrow(new NoAvailableRiderException())
                .doNothing()
                .when(deliveryOrderService).dispatchFromMessage(42L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PendingDispatchRecovery recovery = new PendingDispatchRecovery(
                deliveryOrderMapper, deliveryOrderService, registry);

        recovery.retryPendingOrders();
        recovery.retryPendingOrders();

        verify(deliveryOrderService, times(2)).dispatchFromMessage(42L);
        assertEquals(1, registry.get("dispatch.recovery.attempts")
                .tag("result", "no_rider").counter().count());
        assertEquals(1, registry.get("dispatch.recovery.attempts")
                .tag("result", "processed").counter().count());
    }
}
