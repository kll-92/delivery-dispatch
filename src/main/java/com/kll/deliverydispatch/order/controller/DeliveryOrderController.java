package com.kll.deliverydispatch.order.controller;

import com.kll.deliverydispatch.order.dto.DeliveryFeeUpdateRequest;
import com.kll.deliverydispatch.order.dto.CreateOrderRequest;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.order.service.DeliveryOrderService;
import com.kll.deliverydispatch.common.dto.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class DeliveryOrderController {
    private final DeliveryOrderService deliveryOrderService;

    @PostMapping
    public DeliveryOrder create(@Valid @RequestBody CreateOrderRequest request) {
        return deliveryOrderService.create(request.toEntity());
    }

    @GetMapping("/{id}")
    public DeliveryOrder getById(@PathVariable Long id) {
        return deliveryOrderService.getById(id);
    }

    @GetMapping
    public List<DeliveryOrder> list() {
        return deliveryOrderService.list();
    }

    @GetMapping("/page")
    public PageResult<DeliveryOrder> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime createdTo) {
        return deliveryOrderService.page(status, orderNo, createdFrom, createdTo, page, size);
    }

    @PatchMapping("/{orderId}/assign/{riderId}")
    public DeliveryOrder assignRider(@PathVariable Long orderId, @PathVariable Long riderId) {
        return deliveryOrderService.assignRider(orderId, riderId);
    }

    @PatchMapping("/{orderId}/dispatch")
    public DeliveryOrder dispatch(@PathVariable Long orderId) {
        return deliveryOrderService.dispatch(orderId);
    }

    @PatchMapping("/{orderId}/start-delivery")
    public DeliveryOrder startDelivery(@PathVariable Long orderId) {
        return deliveryOrderService.startDelivery(orderId);
    }

    @PatchMapping("/{orderId}/complete")
    public DeliveryOrder complete(@PathVariable Long orderId) {
        return deliveryOrderService.complete(orderId);
    }

    @PatchMapping("/{orderId}/cancel")
    public DeliveryOrder cancel(@PathVariable Long orderId) {
        return deliveryOrderService.cancel(orderId);
    }

    @PatchMapping("/{orderId}/fee")
    public DeliveryOrder updateFee(@PathVariable Long orderId,
                                   @Valid @RequestBody DeliveryFeeUpdateRequest request) {
        return deliveryOrderService.updateDeliveryFee(orderId, request.deliveryFee());
    }

    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long orderId,
                       @RequestParam(required = false) String reason) {
        deliveryOrderService.delete(orderId, reason);
    }
}
