package com.kll.deliverydispatch.order.service;

import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.order.entity.DispatchOutboxMessage;
import com.kll.deliverydispatch.order.event.DispatchEventTypes;
import com.kll.deliverydispatch.order.mapper.DeliveryOrderMapper;
import com.kll.deliverydispatch.order.mapper.DispatchOutboxMapper;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.mapper.RiderMapper;
import com.kll.deliverydispatch.rider.service.RiderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryOrderServiceTest {

    @Mock
    private DeliveryOrderMapper deliveryOrderMapper;

    @Mock
    private DispatchOutboxMapper dispatchOutboxMapper;

    @Mock
    private RiderMapper riderMapper;

    @Mock
    private RiderService riderService;

    @InjectMocks
    private DeliveryOrderService deliveryOrderService;

    @Test
    void shouldCreateOrderAndMatchingOutboxMessage() {
        DeliveryOrder order = validOrder();
        doAnswer(invocation -> {
            DeliveryOrder insertedOrder = invocation.getArgument(0);
            insertedOrder.setId(11L);
            return 1;
        }).when(deliveryOrderMapper).insert(any(DeliveryOrder.class));

        deliveryOrderService.create(order);

        ArgumentCaptor<DispatchOutboxMessage> outboxCaptor =
                ArgumentCaptor.forClass(DispatchOutboxMessage.class);
        verify(dispatchOutboxMapper).insert(outboxCaptor.capture());
        assertEquals(11L, outboxCaptor.getValue().getOrderId());
        assertEquals(DispatchEventTypes.DISPATCH_REQUEST, outboxCaptor.getValue().getEventType());
        assertEquals(DispatchOutboxMessage.PENDING, outboxCaptor.getValue().getStatus());
    }

    @Test
    void shouldIgnoreDuplicateMessageForAlreadyAssignedOrder() {
        DeliveryOrder order = order(1L, 1, 2L);
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order);

        deliveryOrderService.dispatchFromMessage(1L);

        verify(deliveryOrderMapper).selectById(1L);
        verifyNoMoreInteractions(deliveryOrderMapper);
        verifyNoInteractions(dispatchOutboxMapper, riderMapper, riderService);
    }

    @Test
    void shouldStartAssignedOrder() {
        DeliveryOrder order = order(1L, 1, 2L);
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order);
        when(deliveryOrderMapper.startIfAssigned(1L, 1, 2)).thenReturn(1);

        DeliveryOrder result = deliveryOrderService.startDelivery(1L);

        assertEquals(2, result.getStatus());
        verify(deliveryOrderMapper).startIfAssigned(1L, 1, 2);
    }

    @Test
    void shouldRejectStartWhenOrderChangesAfterInitialRead() {
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order(1L, 1, 2L));
        when(deliveryOrderMapper.startIfAssigned(1L, 1, 2)).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> deliveryOrderService.startDelivery(1L));

        verify(deliveryOrderMapper).startIfAssigned(1L, 1, 2);
    }

    @Test
    void shouldRejectCancelWhenDispatchWinsTheRace() {
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order(1L, 0, null));
        when(deliveryOrderMapper.cancelIfPending(1L, 0, 4)).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> deliveryOrderService.cancel(1L));

        verify(deliveryOrderMapper).selectById(1L);
        verify(deliveryOrderMapper).cancelIfPending(1L, 0, 4);
        verifyNoMoreInteractions(deliveryOrderMapper);
    }

    @Test
    void shouldCompleteOrderAndDecreaseRiderLoad() {
        DeliveryOrder order = order(1L, 2, 2L);
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order);
        when(deliveryOrderMapper.completeIfDelivering(1L, 2, 3)).thenReturn(1);
        when(riderMapper.releaseOrderSlot(2L)).thenReturn(1);

        deliveryOrderService.complete(1L);

        assertEquals(3, order.getStatus());
        verify(riderMapper).releaseOrderSlot(2L);
        verify(riderService).evictDetailCache(2L);
    }

    @Test
    void shouldRejectCancelOfAssignedOrder() {
        DeliveryOrder order = order(1L, 1, 2L);
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order);

        assertThrows(IllegalArgumentException.class, () -> deliveryOrderService.cancel(1L));
    }

    private DeliveryOrder order(Long id, Integer status, Long riderId) {
        DeliveryOrder order = new DeliveryOrder();
        order.setId(id);
        order.setStatus(status);
        order.setRiderId(riderId);
        return order;
    }

    private DeliveryOrder validOrder() {
        DeliveryOrder order = new DeliveryOrder();
        order.setMerchantName("便利店");
        order.setMerchantPhone("01012345678");
        order.setMerchantAddress("商家地址");
        order.setMerchantLongitude(new BigDecimal("116.3971280"));
        order.setMerchantLatitude(new BigDecimal("39.9165270"));
        order.setReceiverName("李四");
        order.setReceiverPhone("13800000002");
        order.setReceiverAddress("收货地址");
        order.setReceiverLongitude(new BigDecimal("116.4000000"));
        order.setReceiverLatitude(new BigDecimal("39.9200000"));
        return order;
    }

    private Rider rider(Long id, Integer status, Integer currentOrderCount) {
        Rider rider = new Rider();
        rider.setId(id);
        rider.setStatus(status);
        rider.setCurrentOrderCount(currentOrderCount);
        return rider;
    }
}
