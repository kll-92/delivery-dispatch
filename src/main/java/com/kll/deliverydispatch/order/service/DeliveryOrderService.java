package com.kll.deliverydispatch.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.order.entity.DispatchOutboxMessage;
import com.kll.deliverydispatch.order.event.DispatchEventTypes;
import com.kll.deliverydispatch.order.enums.OrderStatus;
import com.kll.deliverydispatch.order.mapper.DeliveryOrderMapper;
import com.kll.deliverydispatch.order.mapper.DispatchOutboxMapper;
import com.kll.deliverydispatch.rider.dto.NearbyRiderResponse;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.enums.RiderStatus;
import com.kll.deliverydispatch.rider.mapper.RiderMapper;
import com.kll.deliverydispatch.rider.service.RiderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryOrderService {
    private static final double DISPATCH_RADIUS_KM = 5;
    private static final int DISPATCH_CANDIDATE_LIMIT = 20;
    private static final int MAX_CONCURRENT_ORDERS_PER_RIDER = 3;

    private final DeliveryOrderMapper deliveryOrderMapper;
    private final DispatchOutboxMapper dispatchOutboxMapper;
    private final RiderMapper riderMapper;
    private final RiderService riderService;

    @Transactional
    public DeliveryOrder create(DeliveryOrder order){
        if (!StringUtils.hasText(order.getMerchantName())) {
            throw new IllegalArgumentException("商家名称不能为空");
        }
        if (!StringUtils.hasText(order.getMerchantPhone())) {
            throw new IllegalArgumentException("商家联系电话不能为空");
        }
        if (!StringUtils.hasText(order.getMerchantAddress())) {
            throw new IllegalArgumentException("商家地址不能为空");
        }
        if (!StringUtils.hasText(order.getReceiverName())) {
            throw new IllegalArgumentException("收货联系人不能为空");
        }
        if (!StringUtils.hasText(order.getReceiverPhone())) {
            throw new IllegalArgumentException("收货手机号不能为空");
        }
        if (!StringUtils.hasText(order.getReceiverAddress())) {
            throw new IllegalArgumentException("收货地址不能为空");
        }
        if (order.getMerchantLongitude() == null || order.getMerchantLatitude() == null) {
            throw new IllegalArgumentException("商家坐标不能为空");
        }
        if (order.getReceiverLongitude() == null || order.getReceiverLatitude() == null) {
            throw new IllegalArgumentException("收货坐标不能为空");
        }
        if (order.getDeliveryFee() != null
                && order.getDeliveryFee().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("配送费不能小于 0");
        }

        String orderNo = "ORD"+ UUID.randomUUID().toString()
                        .replace("-","")
                        .substring(0,29);
        order.setStatus(OrderStatus.PENDING_DISPATCH.getCode());
        order.setRiderId(null);
        order.setOrderNo(orderNo);
        if (order.getDeliveryFee() == null) {
            order.setDeliveryFee(BigDecimal.ZERO);
        }
        deliveryOrderMapper.insert(order);

        DispatchOutboxMessage outboxMessage = new DispatchOutboxMessage();
        outboxMessage.setOrderId(order.getId());
        outboxMessage.setEventType(DispatchEventTypes.DISPATCH_REQUEST);
        outboxMessage.setStatus(DispatchOutboxMessage.PENDING);
        dispatchOutboxMapper.insert(outboxMessage);
        return order;
    }

    public DeliveryOrder getById(Long id) {
        DeliveryOrder order = deliveryOrderMapper.selectById(id);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }
        return order;
    }

    public List<DeliveryOrder> list() {
        return deliveryOrderMapper.selectList(null);
    }

    @Transactional
    public DeliveryOrder assignRider(Long orderId, Long riderId) {
        DeliveryOrder order = getById(orderId);
        if (riderId == null) {
            throw new IllegalArgumentException("骑手 ID 不能为空");
        }
        Rider rider = riderMapper.selectById(riderId);
        return assignToRider(order, rider);
    }

    @Transactional
    public DeliveryOrder dispatch(Long orderId) {
        DeliveryOrder order = getById(orderId);
        if (!Integer.valueOf(OrderStatus.PENDING_DISPATCH.getCode()).equals(order.getStatus())) {
            throw new IllegalArgumentException("只有待派单订单可以自动派单");
        }

        List<NearbyRiderResponse> candidates = riderService.findNearby(
                order.getMerchantLongitude(), order.getMerchantLatitude(),
                DISPATCH_RADIUS_KM, DISPATCH_CANDIDATE_LIMIT).stream()
                .sorted(Comparator.comparing(NearbyRiderResponse::distanceKm)
                        .thenComparing(NearbyRiderResponse::currentOrderCount,
                                Comparator.nullsLast(Integer::compareTo)))
                .toList();

        for (NearbyRiderResponse candidate : candidates) {
            if (tryAssign(order, candidate.riderId())) {
                order.setRiderId(candidate.riderId());
                order.setStatus(OrderStatus.ASSIGNED.getCode());
                return order;
            }
        }
        throw new IllegalArgumentException("附近暂无可用骑手");
    }

    /**
     * RocketMQ 至少一次投递时的幂等入口：已不处于待派单状态的订单视为已处理。
     */
    @Transactional
    public void dispatchFromMessage(Long orderId) {
        DeliveryOrder order = getById(orderId);
        if (!Integer.valueOf(OrderStatus.PENDING_DISPATCH.getCode()).equals(order.getStatus())) {
            return;
        }
        dispatch(orderId);
    }

    public DeliveryOrder startDelivery(Long orderId) {
        DeliveryOrder order = getById(orderId);
        if (!Integer.valueOf(OrderStatus.ASSIGNED.getCode()).equals(order.getStatus())) {
            throw new IllegalArgumentException("只有已派单订单可以开始配送");
        }
        int started = deliveryOrderMapper.startIfAssigned(
                orderId, OrderStatus.ASSIGNED.getCode(), OrderStatus.DELIVERING.getCode());
        if (started == 0) {
            throw new IllegalArgumentException("订单状态已变化，请刷新后重试");
        }
        order.setStatus(OrderStatus.DELIVERING.getCode());
        return order;
    }

    @Transactional
    public DeliveryOrder complete(Long orderId) {
        DeliveryOrder order = getById(orderId);
        if (!Integer.valueOf(OrderStatus.DELIVERING.getCode()).equals(order.getStatus())) {
            throw new IllegalArgumentException("只有配送中订单可以完成");
        }
        if (order.getRiderId() == null) {
            throw new IllegalArgumentException("订单未分配骑手");
        }
        int completed = deliveryOrderMapper.completeIfDelivering(
                orderId, OrderStatus.DELIVERING.getCode(), OrderStatus.COMPLETED.getCode());
        if (completed == 0) {
            throw new IllegalStateException("订单状态已变化，无法完成订单");
        }
        int released = riderMapper.releaseOrderSlot(order.getRiderId());
        if (released == 0) {
            throw new IllegalStateException("骑手接单数据异常，无法释放名额");
        }
        riderService.evictDetailCache(order.getRiderId());
        order.setStatus(OrderStatus.COMPLETED.getCode());
        return order;
    }

    public DeliveryOrder cancel(Long orderId) {
        DeliveryOrder order = getById(orderId);
        if (!Integer.valueOf(OrderStatus.PENDING_DISPATCH.getCode()).equals(order.getStatus())) {
            throw new IllegalArgumentException("只有待派单订单可以取消");
        }
        int canceled = deliveryOrderMapper.cancelIfPending(
                orderId, OrderStatus.PENDING_DISPATCH.getCode(), OrderStatus.CANCELED.getCode());
        if (canceled == 0) {
            throw new IllegalArgumentException("订单状态已变化，请刷新后重试");
        }
        order.setStatus(OrderStatus.CANCELED.getCode());
        return order;
    }

    private DeliveryOrder assignToRider(DeliveryOrder order, Rider rider) {
        if (!Integer.valueOf(OrderStatus.PENDING_DISPATCH.getCode()).equals(order.getStatus())) {
            throw new IllegalArgumentException("只有待派单订单可以分配骑手");
        }
        if (rider == null || !Integer.valueOf(RiderStatus.AVAILABLE.getCode()).equals(rider.getStatus())) {
            throw new IllegalArgumentException("骑手当前不可接单");
        }
        if (!tryAssign(order, rider.getId())) {
            throw new IllegalArgumentException("骑手刚被其他订单占用，请重新选择");
        }
        order.setRiderId(rider.getId());
        order.setStatus(OrderStatus.ASSIGNED.getCode());
        return order;
    }

    /**
     * 先原子占用骑手，再条件更新订单。订单状态已变化时抛异常，事务会回滚前面的骑手占用。
     */
    private boolean tryAssign(DeliveryOrder order, Long riderId) {
        int claimed = riderMapper.tryClaimForOrder(
                riderId, RiderStatus.AVAILABLE.getCode(), MAX_CONCURRENT_ORDERS_PER_RIDER);
        if (claimed == 0) {
            return false;
        }
        int assigned = deliveryOrderMapper.assignIfPending(
                order.getId(), riderId,
                OrderStatus.PENDING_DISPATCH.getCode(), OrderStatus.ASSIGNED.getCode());
        if (assigned == 0) {
            throw new IllegalStateException("订单状态已变化，无法完成派单");
        }
        riderService.evictDetailCache(riderId);
        return true;
    }

}
