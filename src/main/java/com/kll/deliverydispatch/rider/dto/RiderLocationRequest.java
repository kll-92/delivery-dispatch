package com.kll.deliverydispatch.rider.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * 骑手主动上报的位置。经度在前，纬度在后。
 */
public record RiderLocationRequest(
        @NotNull(message = "不能为空") @DecimalMin(value = "-180.0", message = "必须在 -180 到 180 之间")
        @DecimalMax(value = "180.0", message = "必须在 -180 到 180 之间")
        @Digits(integer = 3, fraction = 7, message = "最多支持 7 位小数") BigDecimal longitude,
        @NotNull(message = "不能为空") @DecimalMin(value = "-85.05112878", message = "超出 Redis GEO 支持范围")
        @DecimalMax(value = "85.05112878", message = "超出 Redis GEO 支持范围")
        @Digits(integer = 3, fraction = 7, message = "最多支持 7 位小数") BigDecimal latitude) {
}
