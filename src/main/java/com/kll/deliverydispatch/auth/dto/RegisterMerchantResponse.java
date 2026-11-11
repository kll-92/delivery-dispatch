package com.kll.deliverydispatch.auth.dto;

/** 注册成功返回。只回必要信息，不回密码。 */
public record RegisterMerchantResponse(
        Long accountId,
        String username,
        String role,
        Long merchantId,
        String shopName) {
}
