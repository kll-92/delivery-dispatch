package com.kll.deliverydispatch.rider.dto;

import com.kll.deliverydispatch.rider.entity.Rider;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Whitelisted fields accepted when updating rider profile data. */
public record UpdateRiderRequest(
        @NotBlank(message = "不能为空") @Size(max = 64, message = "长度不能超过 64 个字符") String name,
        @NotBlank(message = "不能为空") @Size(max = 20, message = "长度不能超过 20 个字符") String phone,
        @DecimalMin(value = "-180.0", message = "必须在 -180 到 180 之间") @DecimalMax(value = "180.0", message = "必须在 -180 到 180 之间")
        @Digits(integer = 3, fraction = 7, message = "最多支持 7 位小数") BigDecimal longitude,
        @DecimalMin(value = "-85.05112878", message = "超出 Redis GEO 支持范围") @DecimalMax(value = "85.05112878", message = "超出 Redis GEO 支持范围")
        @Digits(integer = 3, fraction = 7, message = "最多支持 7 位小数") BigDecimal latitude) {

    @AssertTrue(message = "经度和纬度必须同时提供")
    public boolean isCoordinatePairValid() {
        return (longitude == null) == (latitude == null);
    }

    public Rider toEntity() {
        Rider rider = new Rider();
        rider.setName(name);
        rider.setPhone(phone);
        rider.setLongitude(longitude);
        rider.setLatitude(latitude);
        return rider;
    }
}
