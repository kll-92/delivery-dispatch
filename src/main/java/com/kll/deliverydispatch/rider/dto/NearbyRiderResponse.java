package com.kll.deliverydispatch.rider.dto;

import java.math.BigDecimal;

/**
 * GEO 找到的候选骑手，不代表已经被订单分配。
 */
public record NearbyRiderResponse(
        Long riderId,
        String riderNo,
        String name,
        BigDecimal distanceKm,
        Integer currentOrderCount) {
}
