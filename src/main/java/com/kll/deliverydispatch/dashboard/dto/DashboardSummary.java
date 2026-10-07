package com.kll.deliverydispatch.dashboard.dto;

public record DashboardSummary(
        long totalOrders,
        long pendingOrders,
        long assignedOrders,
        long deliveringOrders,
        long completedOrders,
        long canceledOrders,
        long totalRiders,
        long availableRiders) {
}
