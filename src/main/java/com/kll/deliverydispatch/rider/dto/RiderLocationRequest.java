package com.kll.deliverydispatch.rider.dto;

import java.math.BigDecimal;

/**
 * 骑手主动上报的位置。经度在前，纬度在后。
 */
public record RiderLocationRequest(BigDecimal longitude, BigDecimal latitude) {
}
