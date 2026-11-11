package com.kll.deliverydispatch.rider.controller;

import com.kll.deliverydispatch.common.dto.PageResult;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.rider.dto.RiderLocationRequest;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.service.RiderWorkbenchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 骑手端：只看自己的单，只改自己的位置。 */
@RestController
@RequestMapping("/api/v1/rider/me")
@RequiredArgsConstructor
public class RiderWorkbenchController {

    private final RiderWorkbenchService riderWorkbenchService;

    @GetMapping("/profile")
    public Rider profile(Authentication authentication) {
        return riderWorkbenchService.profile(authentication.getName());
    }

    @GetMapping("/orders")
    public PageResult<DeliveryOrder> myOrders(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer status) {
        return riderWorkbenchService.myOrders(authentication.getName(), status, page, size);
    }

    @PatchMapping("/orders/{orderId}/accept")
    public DeliveryOrder accept(Authentication authentication, @PathVariable Long orderId) {
        return riderWorkbenchService.accept(authentication.getName(), orderId);
    }

    @PatchMapping("/orders/{orderId}/start")
    public DeliveryOrder start(Authentication authentication, @PathVariable Long orderId) {
        return riderWorkbenchService.start(authentication.getName(), orderId);
    }

    @PatchMapping("/orders/{orderId}/complete")
    public DeliveryOrder complete(Authentication authentication, @PathVariable Long orderId) {
        return riderWorkbenchService.complete(authentication.getName(), orderId);
    }

    @PostMapping("/location")
    public Rider updateLocation(Authentication authentication,
                                @Valid @RequestBody RiderLocationRequest request) {
        return riderWorkbenchService.updateMyLocation(authentication.getName(), request);
    }
}
