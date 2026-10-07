package com.kll.deliverydispatch.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kll.deliverydispatch.common.audit.OperationLogService;
import com.kll.deliverydispatch.common.dto.PageResult;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.order.entity.DispatchOutboxMessage;
import com.kll.deliverydispatch.order.event.DispatchEventTypes;
import com.kll.deliverydispatch.order.enums.OrderStatus;
import com.kll.deliverydispatch.order.mapper.DeliveryOrderMapper;
import com.kll.deliverydispatch.order.mapper.DispatchOutboxMapper;
import com.kll.deliverydispatch.rider.dto.NearbyRiderResponse;
import com.kll.deliverydispatch.rider.RiderCapacity;
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
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryOrderService {
    private static final double DISPATCH_RADIUS_KM = 5;
    private static final int DISPATCH_CANDIDATE_LIMIT = 20;

    private final DeliveryOrderMapper deliveryOrderMapper;
    private final DispatchOutboxMapper dispatchOutboxMapper;
    private final RiderMapper riderMapper;
    private final RiderService riderService;
    private final OperationLogService operationLogService;

    @Transactional
    public DeliveryOrder create(DeliveryOrder order){
        order.setId(null);
        order.setOrderNo(null);
        order.setRiderId(null);
        order.setStatus(null);
        order.setCreatedAt(null);
        order.setUpdatedAt(null);
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
        validateCoordinates(order.getMerchantLongitude(), order.getMerchantLatitude());
        validateCoordinates(order.getReceiverLongitude(), order.getReceiverLatitude());
        validateDeliveryFee(order.getDeliveryFee());
        validateOrderFieldLengths(order);

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
        operationLogService.record("CREATE", "ORDER", order.getId(), null,
                copyOrder(order), null);
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
        return deliveryOrderMapper.selectList(Wrappers.<DeliveryOrder>lambdaQuery()
                .orderByDesc(DeliveryOrder::getId).last("LIMIT 200"));
    }

    public PageResult<DeliveryOrder> page(Integer status, String orderNo, LocalDateTime createdFrom,
                                          LocalDateTime createdTo, int page, int size) {
        validatePage(page, size);
        if (status != null && java.util.Arrays.stream(OrderStatus.values())
                .noneMatch(orderStatus -> orderStatus.getCode() == status)) {
            throw new IllegalArgumentException("订单状态无效");
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new IllegalArgumentException("createdFrom 不能晚于 createdTo");
        }
        String normalizedOrderNo = StringUtils.hasText(orderNo) ? orderNo.trim() : null;
        var query = Wrappers.<DeliveryOrder>lambdaQuery()
                .eq(status != null, DeliveryOrder::getStatus, status)
                .like(normalizedOrderNo != null, DeliveryOrder::getOrderNo, normalizedOrderNo)
                .ge(createdFrom != null, DeliveryOrder::getCreatedAt, createdFrom)
                .le(createdTo != null, DeliveryOrder::getCreatedAt, createdTo);
        long total = deliveryOrderMapper.selectCount(query);
        long offset = (long) (page - 1) * size;
        List<DeliveryOrder> records = deliveryOrderMapper.selectList(query.clone()
                .orderByDesc(DeliveryOrder::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
        return new PageResult<>(records, total, page, size);
    }

    @Transactional
    public DeliveryOrder assignRider(Long orderId, Long riderId) {
        DeliveryOrder order = getById(orderId);
        DeliveryOrder before = copyOrder(order);
        if (riderId == null) {
            throw new IllegalArgumentException("骑手 ID 不能为空");
        }
        Rider rider = riderMapper.selectById(riderId);
        DeliveryOrder assigned = assignToRider(order, rider);
        operationLogService.record("ASSIGN", "ORDER", orderId, before, copyOrder(assigned), null);
        return assigned;
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
                DeliveryOrder before = copyOrder(order);
                order.setRiderId(candidate.riderId());
                order.setStatus(OrderStatus.ASSIGNED.getCode());
                operationLogService.record("ASSIGN", "ORDER", orderId, before,
                        copyOrder(order), "自动派单");
                return order;
            }
        }
        throw new NoAvailableRiderException();
    }

    /**
     * RocketMQ 至少一次投递时的幂等入口：已不处于待派单状态的订单视为已处理。
     */
    @Transactional
    public void dispatchFromMessage(Long orderId) {
        // 订单可能已被运营人员删除；这类消息无需继续重试。
        DeliveryOrder order = deliveryOrderMapper.selectById(orderId);
        if (order == null) {
            return;
        }
        if (!Integer.valueOf(OrderStatus.PENDING_DISPATCH.getCode()).equals(order.getStatus())) {
            return;
        }
        dispatch(orderId);
    }

    @Transactional
    public DeliveryOrder startDelivery(Long orderId) {
        DeliveryOrder order = getById(orderId);
        DeliveryOrder before = copyOrder(order);
        if (!Integer.valueOf(OrderStatus.ASSIGNED.getCode()).equals(order.getStatus())) {
            throw new IllegalArgumentException("只有已派单订单可以开始配送");
        }
        int started = deliveryOrderMapper.startIfAssigned(
                orderId, OrderStatus.ASSIGNED.getCode(), OrderStatus.DELIVERING.getCode());
        if (started == 0) {
            throw new IllegalArgumentException("订单状态已变化，请刷新后重试");
        }
        order.setStatus(OrderStatus.DELIVERING.getCode());
        operationLogService.record("START", "ORDER", orderId, before, copyOrder(order), null);
        return order;
    }

    @Transactional
    public DeliveryOrder complete(Long orderId) {
        DeliveryOrder order = getById(orderId);
        DeliveryOrder before = copyOrder(order);
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
        operationLogService.record("COMPLETE", "ORDER", orderId, before, copyOrder(order), null);
        return order;
    }

    @Transactional
    public DeliveryOrder cancel(Long orderId) {
        DeliveryOrder order = getById(orderId);
        DeliveryOrder before = copyOrder(order);
        if (!Integer.valueOf(OrderStatus.PENDING_DISPATCH.getCode()).equals(order.getStatus())) {
            throw new IllegalArgumentException("只有待派单订单可以取消");
        }
        int canceled = deliveryOrderMapper.cancelIfPending(
                orderId, OrderStatus.PENDING_DISPATCH.getCode(), OrderStatus.CANCELED.getCode());
        if (canceled == 0) {
            throw new IllegalArgumentException("订单状态已变化，请刷新后重试");
        }
        order.setStatus(OrderStatus.CANCELED.getCode());
        operationLogService.record("CANCEL", "ORDER", orderId, before, copyOrder(order), null);
        return order;
    }

    @Transactional
    public DeliveryOrder updateDeliveryFee(Long orderId, BigDecimal deliveryFee) {
        if (deliveryFee == null) {
            throw new IllegalArgumentException("配送费不能为空");
        }
        validateDeliveryFee(deliveryFee);
        DeliveryOrder order = getById(orderId);
        DeliveryOrder before = copyOrder(order);
        int updated = deliveryOrderMapper.update(null, Wrappers.<DeliveryOrder>lambdaUpdate()
                .set(DeliveryOrder::getDeliveryFee, deliveryFee)
                .eq(DeliveryOrder::getId, orderId));
        if (updated == 0) {
            throw new IllegalStateException("订单配送费更新失败，请刷新后重试");
        }
        order.setDeliveryFee(deliveryFee);
        operationLogService.record("FEE_UPDATE", "ORDER", orderId, before, copyOrder(order), null);
        return order;
    }

    @Transactional
    public void delete(Long orderId) {
        delete(orderId, null);
    }

    @Transactional
    public void delete(Long orderId, String reason) {
        String deletionReason = StringUtils.hasText(reason) ? reason.trim() : "未填写删除原因（旧接口）";
        if (deletionReason.length() > 255) {
            throw new IllegalArgumentException("删除原因长度不能超过 255 个字符");
        }
        DeliveryOrder order = getById(orderId);
        DeliveryOrder before = copyOrder(order);
        // Check the state again in MySQL. A concurrent assignment may have happened
        // after getById; deleting that newer state would leak the rider's slot.
        int deleted = deliveryOrderMapper.deleteIfUnchanged(
                orderId, order.getStatus(), order.getRiderId());
        if (deleted == 0) {
            throw new IllegalArgumentException("订单状态已变化，请刷新后重试");
        }
        boolean riderSlotClaimed = order.getRiderId() != null
                && (Integer.valueOf(OrderStatus.ASSIGNED.getCode()).equals(order.getStatus())
                || Integer.valueOf(OrderStatus.DELIVERING.getCode()).equals(order.getStatus()));
        if (riderSlotClaimed) {
            if (riderMapper.releaseOrderSlot(order.getRiderId()) == 0) {
                throw new IllegalStateException("骑手名额释放失败，暂不能删除订单");
            }
            riderService.evictDetailCache(order.getRiderId());
        }
        dispatchOutboxMapper.deletePendingByOrderId(orderId);
        operationLogService.record("DELETE", "ORDER", orderId, before, null, deletionReason);
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
                riderId, RiderStatus.AVAILABLE.getCode(), RiderCapacity.MAX_CONCURRENT_ORDERS);
        if (claimed == 0) {
            return false;
        }
        int assigned = deliveryOrderMapper.assignIfPending(
                order.getId(), riderId,
                OrderStatus.PENDING_DISPATCH.getCode(), OrderStatus.ASSIGNED.getCode());
        if (assigned == 0) {
            throw new IllegalArgumentException("订单状态已变化，请刷新后重试");
        }
        riderService.evictDetailCache(riderId);
        return true;
    }

    private static DeliveryOrder copyOrder(DeliveryOrder source) {
        DeliveryOrder copy = new DeliveryOrder();
        copy.setId(source.getId());
        copy.setOrderNo(source.getOrderNo());
        copy.setMerchantName(source.getMerchantName());
        copy.setMerchantPhone(source.getMerchantPhone());
        copy.setMerchantAddress(source.getMerchantAddress());
        copy.setMerchantLongitude(source.getMerchantLongitude());
        copy.setMerchantLatitude(source.getMerchantLatitude());
        copy.setReceiverName(source.getReceiverName());
        copy.setReceiverPhone(source.getReceiverPhone());
        copy.setReceiverAddress(source.getReceiverAddress());
        copy.setReceiverLongitude(source.getReceiverLongitude());
        copy.setReceiverLatitude(source.getReceiverLatitude());
        copy.setRiderId(source.getRiderId());
        copy.setStatus(source.getStatus());
        copy.setDeliveryFee(source.getDeliveryFee());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private static void validatePage(int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须大于等于 1");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size 必须在 1 到 100 之间");
        }
    }

    private static void validateCoordinates(BigDecimal longitude, BigDecimal latitude) {
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("经度必须在 -180 到 180 之间");
        }
        if (latitude.compareTo(BigDecimal.valueOf(-85.05112878)) < 0
                || latitude.compareTo(BigDecimal.valueOf(85.05112878)) > 0) {
            throw new IllegalArgumentException("纬度超出 Redis GEO 支持范围");
        }
        if (Math.max(longitude.scale(), 0) > 7 || Math.max(latitude.scale(), 0) > 7) {
            throw new IllegalArgumentException("坐标最多支持 7 位小数");
        }
    }

    private static void validateDeliveryFee(BigDecimal deliveryFee) {
        if (deliveryFee == null) {
            return;
        }
        if (deliveryFee.compareTo(BigDecimal.ZERO) < 0
                || deliveryFee.compareTo(new BigDecimal("99999999.99")) > 0
                || deliveryFee.scale() > 2) {
            throw new IllegalArgumentException("配送费必须在 0 到 99999999.99 之间且最多两位小数");
        }
    }

    private static void validateOrderFieldLengths(DeliveryOrder order) {
        validateLength(order.getMerchantName(), 64, "商家名称");
        validateLength(order.getMerchantPhone(), 20, "商家联系电话");
        validateLength(order.getMerchantAddress(), 255, "商家地址");
        validateLength(order.getReceiverName(), 64, "收货联系人");
        validateLength(order.getReceiverPhone(), 20, "收货手机号");
        validateLength(order.getReceiverAddress(), 255, "收货地址");
    }

    private static void validateLength(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + "长度不能超过 " + maxLength + " 个字符");
        }
    }

}
