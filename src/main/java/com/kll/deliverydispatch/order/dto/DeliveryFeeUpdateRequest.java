package com.kll.deliverydispatch.order.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

/**
 * 订单配送费的局部更新请求。
 */
public record DeliveryFeeUpdateRequest(
        @DecimalMin(value = "0.00", message = "不能小于 0")
        @DecimalMax(value = "99999999.99", message = "不能超过 99999999.99")
        @Digits(integer = 8, fraction = 2, message = "最多支持 2 位小数")
        BigDecimal deliveryFee) {
}
