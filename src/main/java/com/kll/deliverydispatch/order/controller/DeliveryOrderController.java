package com.kll.deliverydispatch.order.controller;

import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.order.service.DeliveryOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class DeliveryOrderController {
    private final DeliveryOrderService deliveryOrderService;

    @PostMapping
    public DeliveryOrder create(@RequestBody DeliveryOrder order) {
        return deliveryOrderService.create(order);
    }

    @GetMapping("/{id}")
    public DeliveryOrder getById(@PathVariable Long id) {
        return deliveryOrderService.getById(id);
    }

    @GetMapping
    public List<DeliveryOrder> list() {
        return deliveryOrderService.list();
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
}
