package com.kll.deliverydispatch.merchant.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 商家下单请求：只含收货信息，**不含任何商家字段**（由后端从登录店铺取）。 */
public record CreateMerchantOrderRequest(
        @NotBlank(message = "不能为空") @Size(max = 64, message = "长度不能超过 64 个字符") String receiverName,
        @NotBlank(message = "不能为空") @Size(max = 20, message = "长度不能超过 20 个字符") String receiverPhone,
        @NotBlank(message = "不能为空") @Size(max = 255, message = "长度不能超过 255 个字符") String receiverAddress,
        @NotNull(message = "不能为空") @DecimalMin(value = "-180.0", message = "必须在 -180 到 180 之间")
        @DecimalMax(value = "180.0", message = "必须在 -180 到 180 之间")
        @Digits(integer = 3, fraction = 7, message = "最多支持 7 位小数") BigDecimal receiverLongitude,
        @NotNull(message = "不能为空") @DecimalMin(value = "-85.05112878", message = "超出 Redis GEO 支持范围")
        @DecimalMax(value = "85.05112878", message = "超出 Redis GEO 支持范围")
        @Digits(integer = 3, fraction = 7, message = "最多支持 7 位小数") BigDecimal receiverLatitude,
        @DecimalMin(value = "0.00", message = "不能小于 0")
        @DecimalMax(value = "99999999.99", message = "不能超过 99999999.99")
        @Digits(integer = 8, fraction = 2, message = "最多支持 2 位小数") BigDecimal deliveryFee) {
}
