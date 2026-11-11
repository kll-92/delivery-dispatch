package com.kll.deliverydispatch.merchant.controller;

import com.kll.deliverydispatch.common.dto.PageResult;
import com.kll.deliverydispatch.merchant.dto.CreateMerchantOrderRequest;
import com.kll.deliverydispatch.merchant.service.MerchantOrderService;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 商家端订单：查自己的单、以自己的店铺身份下单。 */
@RestController
@RequestMapping("/api/v1/merchant/me/orders")
@RequiredArgsConstructor
public class MerchantOrderController {

    private final MerchantOrderService merchantOrderService;

    @GetMapping
    public PageResult<DeliveryOrder> myOrders(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer status) {
        // 只传登录名，不接收前端传来的 merchantId —— 传了也不用
        return merchantOrderService.myOrders(authentication.getName(), status, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryOrder createOrder(Authentication authentication,
                                     @Valid @RequestBody CreateMerchantOrderRequest request) {
        return merchantOrderService.createOrder(authentication.getName(), request);
    }
}
