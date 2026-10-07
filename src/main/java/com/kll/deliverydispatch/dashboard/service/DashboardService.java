package com.kll.deliverydispatch.dashboard.service;

import com.kll.deliverydispatch.dashboard.dto.DashboardSummary;
import com.kll.deliverydispatch.dashboard.mapper.DashboardMapper;
import com.kll.deliverydispatch.order.enums.OrderStatus;
import com.kll.deliverydispatch.rider.RiderCapacity;
import com.kll.deliverydispatch.rider.enums.RiderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {
    private final DashboardMapper dashboardMapper;

    public DashboardSummary summary() {
        Map<String, Object> orders = dashboardMapper.countOrders(
                OrderStatus.PENDING_DISPATCH.getCode(),
                OrderStatus.ASSIGNED.getCode(),
                OrderStatus.DELIVERING.getCode(),
                OrderStatus.COMPLETED.getCode(),
                OrderStatus.CANCELED.getCode());
        Map<String, Object> riders = dashboardMapper.countRiders(
                RiderStatus.AVAILABLE.getCode(), RiderCapacity.MAX_CONCURRENT_ORDERS);
        return new DashboardSummary(
                count(orders, "totalOrders"),
                count(orders, "pendingOrders"),
                count(orders, "assignedOrders"),
                count(orders, "deliveringOrders"),
                count(orders, "completedOrders"),
                count(orders, "canceledOrders"),
                count(riders, "totalRiders"),
                count(riders, "availableRiders"));
    }

    private static long count(Map<String, Object> values, String key) {
        if (values == null || values.get(key) == null) {
            return 0;
        }
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Dashboard count query returned a non-numeric value for " + key);
    }
}
