package com.kll.deliverydispatch.order.service;

import com.kll.deliverydispatch.common.audit.OperationLogService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.order.entity.DispatchOutboxMessage;
import com.kll.deliverydispatch.order.event.DispatchEventTypes;
import com.kll.deliverydispatch.order.mapper.DeliveryOrderMapper;
import com.kll.deliverydispatch.order.mapper.DispatchOutboxMapper;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.mapper.RiderMapper;
import com.kll.deliverydispatch.rider.service.RiderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private DeliveryOrderService deliveryOrderService;

    @BeforeEach
    void initializeMyBatisEntityMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), DeliveryOrder.class);
    }

    @Test
    void shouldCreateOrderAndMatchingOutboxMessage() {
        DeliveryOrder order = validOrder();
        order.setId(90L);
        order.setStatus(4);
        order.setRiderId(55L);
        doAnswer(invocation -> {
            DeliveryOrder insertedOrder = invocation.getArgument(0);
            insertedOrder.setId(11L);
            return 1;
        }).when(deliveryOrderMapper).insert(any(DeliveryOrder.class));

        deliveryOrderService.create(order);

        ArgumentCaptor<DispatchOutboxMessage> outboxCaptor =
                ArgumentCaptor.forClass(DispatchOutboxMessage.class);
        verify(dispatchOutboxMapper).insert(outboxCaptor.capture());
        assertEquals(null, order.getRiderId());
        assertEquals(0, order.getStatus());
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
        ArgumentCaptor<Object> snapshotCaptor = ArgumentCaptor.forClass(Object.class);
        org.mockito.ArgumentCaptor<String> actionCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> targetTypeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Long> targetIdCaptor = org.mockito.ArgumentCaptor.forClass(Long.class);
        org.mockito.ArgumentCaptor<String> reasonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> afterCaptor = ArgumentCaptor.forClass(Object.class);
        verify(operationLogService).record(actionCaptor.capture(), targetTypeCaptor.capture(),
                targetIdCaptor.capture(), snapshotCaptor.capture(), afterCaptor.capture(), reasonCaptor.capture());
        assertEquals("START", actionCaptor.getValue());
        assertEquals("ORDER", targetTypeCaptor.getValue());
        assertEquals(1L, targetIdCaptor.getValue());
        assertEquals(1, ((DeliveryOrder) snapshotCaptor.getValue()).getStatus());
        assertEquals(2, ((DeliveryOrder) afterCaptor.getValue()).getStatus());
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

    @Test
    void shouldNotDeleteAnOrderThatChangedAfterTheInitialRead() {
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order(1L, 0, null));
        when(deliveryOrderMapper.deleteIfUnchanged(1L, 0, null)).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> deliveryOrderService.delete(1L));

        verify(deliveryOrderMapper).deleteIfUnchanged(1L, 0, null);
        verifyNoInteractions(riderMapper, riderService, dispatchOutboxMapper);
    }

    @Test
    void shouldReleaseRiderSlotAfterDeletingTheExpectedAssignedOrder() {
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order(1L, 1, 2L));
        when(deliveryOrderMapper.deleteIfUnchanged(1L, 1, 2L)).thenReturn(1);
        when(riderMapper.releaseOrderSlot(2L)).thenReturn(1);

        deliveryOrderService.delete(1L);

        verify(riderMapper).releaseOrderSlot(2L);
        verify(riderService).evictDetailCache(2L);
        verify(dispatchOutboxMapper).deletePendingByOrderId(1L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void shouldAllowDeletingOrdersInEveryStateAndReleaseOnlyClaimedSlots(int orderStatus) {
        Long riderId = 2L;
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order(1L, orderStatus, riderId));
        when(deliveryOrderMapper.deleteIfUnchanged(1L, orderStatus, riderId)).thenReturn(1);
        if (orderStatus == 1 || orderStatus == 2) {
            when(riderMapper.releaseOrderSlot(riderId)).thenReturn(1);
        }

        deliveryOrderService.delete(1L, "duplicate request");

        if (orderStatus == 1 || orderStatus == 2) {
            verify(riderMapper).releaseOrderSlot(riderId);
        } else {
            verify(riderMapper, org.mockito.Mockito.never()).releaseOrderSlot(riderId);
        }
        verify(dispatchOutboxMapper).deletePendingByOrderId(1L);
        verify(operationLogService).record(org.mockito.ArgumentMatchers.eq("DELETE"),
                org.mockito.ArgumentMatchers.eq("ORDER"), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("duplicate request"));
    }

    @Test
    void shouldRejectInvalidFeePrecisionBeforeReadingOrder() {
        assertThrows(IllegalArgumentException.class,
                () -> deliveryOrderService.updateDeliveryFee(1L, null));
        assertThrows(IllegalArgumentException.class,
                () -> deliveryOrderService.updateDeliveryFee(1L, new BigDecimal("12.345")));
        assertThrows(IllegalArgumentException.class,
                () -> deliveryOrderService.updateDeliveryFee(1L, new BigDecimal("100000000.00")));
        verifyNoInteractions(deliveryOrderMapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void shouldAllowFeeChangesInEveryOrderStateIncludingZero(int orderStatus) {
        DeliveryOrder order = order(1L, orderStatus, null);
        order.setDeliveryFee(new BigDecimal("5.00"));
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order);
        when(deliveryOrderMapper.update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);

        DeliveryOrder updated = deliveryOrderService.updateDeliveryFee(1L, BigDecimal.ZERO);

        assertEquals(BigDecimal.ZERO, updated.getDeliveryFee());
        assertEquals(orderStatus, updated.getStatus());
        verify(operationLogService).record(org.mockito.ArgumentMatchers.eq("FEE_UPDATE"),
                org.mockito.ArgumentMatchers.eq("ORDER"), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void shouldRejectInvalidPageAndReversedCreatedRangeBeforeQuerying() {
        LocalDateTime from = LocalDateTime.parse("2026-10-07T10:00:00");
        LocalDateTime to = LocalDateTime.parse("2026-10-07T09:00:00");

        assertThrows(IllegalArgumentException.class,
                () -> deliveryOrderService.page(null, null, null, null, 1, 101));
        assertThrows(IllegalArgumentException.class,
                () -> deliveryOrderService.page(null, null, from, to, 1, 20));

        verifyNoInteractions(deliveryOrderMapper);
    }

    @Test
    void shouldPropagateAuditFailureFromAnOrderMutation() {
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order(1L, 1, 2L));
        when(deliveryOrderMapper.startIfAssigned(1L, 1, 2)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit storage unavailable"))
                .when(operationLogService).record(
                        org.mockito.ArgumentMatchers.eq("START"),
                        org.mockito.ArgumentMatchers.eq("ORDER"),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.isNull());

        assertThrows(IllegalStateException.class, () -> deliveryOrderService.startDelivery(1L));
    }

    @Test
    void shouldRejectAssignmentWhenTheOrderChangesAfterTheRiderSlotIsClaimed() {
        when(deliveryOrderMapper.selectById(1L)).thenReturn(order(1L, 0, null));
        when(riderMapper.selectById(2L)).thenReturn(rider(2L, 1, 0));
        when(riderMapper.tryClaimForOrder(2L, 1, 3)).thenReturn(1);
        when(deliveryOrderMapper.assignIfPending(1L, 2L, 0, 1)).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> deliveryOrderService.assignRider(1L, 2L));

        verify(deliveryOrderMapper).assignIfPending(1L, 2L, 0, 1);
        verifyNoInteractions(riderService);
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
