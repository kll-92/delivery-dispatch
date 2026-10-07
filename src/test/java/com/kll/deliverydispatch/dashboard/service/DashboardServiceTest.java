package com.kll.deliverydispatch.dashboard.service;

import com.kll.deliverydispatch.dashboard.mapper.DashboardMapper;
import com.kll.deliverydispatch.order.enums.OrderStatus;
import com.kll.deliverydispatch.rider.RiderCapacity;
import com.kll.deliverydispatch.rider.enums.RiderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {
    @Mock
    private DashboardMapper dashboardMapper;

    @InjectMocks
    private DashboardService dashboardService;

    @Test
    void summaryCombinesWholeTableOrderAndRiderCounts() {
        when(dashboardMapper.countOrders(0, 1, 2, 3, 4)).thenReturn(Map.of(
                "totalOrders", 20L,
                "pendingOrders", 4L,
                "assignedOrders", 3L,
                "deliveringOrders", 2L,
                "completedOrders", 8L,
                "canceledOrders", 3L));
        when(dashboardMapper.countRiders(1, RiderCapacity.MAX_CONCURRENT_ORDERS)).thenReturn(Map.of(
                "totalRiders", 6L,
                "availableRiders", 4L));

        var summary = dashboardService.summary();

        assertEquals(20, summary.totalOrders());
        assertEquals(4, summary.pendingOrders());
        assertEquals(3, summary.assignedOrders());
        assertEquals(2, summary.deliveringOrders());
        assertEquals(8, summary.completedOrders());
        assertEquals(3, summary.canceledOrders());
        assertEquals(6, summary.totalRiders());
        assertEquals(4, summary.availableRiders());
        verify(dashboardMapper).countOrders(
                OrderStatus.PENDING_DISPATCH.getCode(), OrderStatus.ASSIGNED.getCode(),
                OrderStatus.DELIVERING.getCode(), OrderStatus.COMPLETED.getCode(),
                OrderStatus.CANCELED.getCode());
        verify(dashboardMapper).countRiders(RiderStatus.AVAILABLE.getCode(), RiderCapacity.MAX_CONCURRENT_ORDERS);
    }

    @Test
    void missingAggregateValuesAreReportedAsZeroForAnEmptyDatabase() {
        when(dashboardMapper.countOrders(0, 1, 2, 3, 4)).thenReturn(Map.of());
        when(dashboardMapper.countRiders(1, RiderCapacity.MAX_CONCURRENT_ORDERS)).thenReturn(Map.of());

        var summary = dashboardService.summary();

        assertEquals(0, summary.totalOrders());
        assertEquals(0, summary.pendingOrders());
        assertEquals(0, summary.assignedOrders());
        assertEquals(0, summary.deliveringOrders());
        assertEquals(0, summary.completedOrders());
        assertEquals(0, summary.canceledOrders());
        assertEquals(0, summary.totalRiders());
        assertEquals(0, summary.availableRiders());
    }
}
