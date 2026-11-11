package com.kll.deliverydispatch.rider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kll.deliverydispatch.account.entity.Account;
import com.kll.deliverydispatch.account.mapper.AccountMapper;
import com.kll.deliverydispatch.common.dto.PageResult;
import com.kll.deliverydispatch.common.exception.ConflictException;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.order.enums.OrderStatus;
import com.kll.deliverydispatch.order.mapper.DeliveryOrderMapper;
import com.kll.deliverydispatch.order.service.DeliveryOrderService;
import com.kll.deliverydispatch.rider.dto.RiderLocationRequest;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.mapper.RiderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 骑手工作台。
 * <p>
 * 约定（三个写操作一致）：
 * <ul>
 *   <li>目标状态已达成 → 返回 200 + 当前状态（幂等）</li>
 *   <li>订单不是自己的 → 403（AccessDeniedException）</li>
 *   <li>状态冲突、无法推进 → 409（ConflictException）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RiderWorkbenchService {

    private final AccountMapper accountMapper;
    private final RiderMapper riderMapper;
    private final DeliveryOrderMapper deliveryOrderMapper;
    private final DeliveryOrderService deliveryOrderService;
    private final RiderService riderService;

    public Rider profile(String username) {
        return requireRider(username);
    }

    public PageResult<DeliveryOrder> myOrders(String username, Integer status, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("分页参数不合法：page 需 >= 1，size 需在 1~100 之间");
        }
        Rider rider = requireRider(username);

        var query = Wrappers.<DeliveryOrder>lambdaQuery()
                .eq(DeliveryOrder::getRiderId, rider.getId())
                .eq(status != null, DeliveryOrder::getStatus, status);

        long total = deliveryOrderMapper.selectCount(query);
        long offset = (long) (page - 1) * size;
        List<DeliveryOrder> records = deliveryOrderMapper.selectList(
                query.clone()
                        .orderByDesc(DeliveryOrder::getId)
                        .last("LIMIT " + size + " OFFSET " + offset));
        return new PageResult<>(records, total, page, size);
    }

    /** 接单。已经是我的 → 直接返回（幂等）；是别人的 → 409。 */
    @Transactional
    public DeliveryOrder accept(String username, Long orderId) {
        Rider rider = requireRider(username);
        DeliveryOrder order = deliveryOrderService.getById(orderId);

        if (rider.getId().equals(order.getRiderId())) {
            return order;
        }
        if (order.getRiderId() != null) {
            throw new ConflictException("订单已被其他骑手接走");
        }
        if (!Integer.valueOf(OrderStatus.PENDING_DISPATCH.getCode()).equals(order.getStatus())) {
            throw new ConflictException("订单当前状态不能接单");
        }
        // 容量校验、位置新鲜度、并发抢单兜底都在 assignRider 里
        return deliveryOrderService.assignRider(orderId, rider.getId());
    }

    /** 开始配送。已在配送中 → 直接返回（幂等）。 */
    public DeliveryOrder start(String username, Long orderId) {
        Rider rider = requireRider(username);
        DeliveryOrder order = requireOwnership(rider, orderId);

        int status = order.getStatus() == null ? -1 : order.getStatus();
        if (status == OrderStatus.DELIVERING.getCode()) {
            return order;                       // 已经在配送中，重复点了
        }
        if (status != OrderStatus.ASSIGNED.getCode()) {
            throw new ConflictException("只有已派单订单可以开始配送，当前状态：" + describeStatus(status));
        }
        return deliveryOrderService.startDelivery(orderId);
    }

    /** 送达。已完成 → 直接返回（幂等）。 */
    public DeliveryOrder complete(String username, Long orderId) {
        Rider rider = requireRider(username);
        DeliveryOrder order = requireOwnership(rider, orderId);

        int status = order.getStatus() == null ? -1 : order.getStatus();
        if (status == OrderStatus.COMPLETED.getCode()) {
            return order;                       // 已经送达，重复点了
        }
        if (status != OrderStatus.DELIVERING.getCode()) {
            throw new ConflictException("只有配送中订单可以送达，当前状态：" + describeStatus(status));
        }
        return deliveryOrderService.complete(orderId);
    }

    /** 把状态码翻译成中文，方便前端直接展示。 */
    private static String describeStatus(int code) {
        for (OrderStatus status : OrderStatus.values()) {
            if (status.getCode() == code) {
                return status.getDescription();
            }
        }
        return String.valueOf(code);
    }

    /** 上报自己的位置。骑手 id 来自登录态，请求体里没有这个字段。 */
    public Rider updateMyLocation(String username, RiderLocationRequest request) {
        Rider rider = requireRider(username);
        return riderService.updateLocation(rider.getId(), request);
    }

    /** 校验订单归属，不是自己的直接 403。 */
    private DeliveryOrder requireOwnership(Rider rider, Long orderId) {
        DeliveryOrder order = deliveryOrderService.getById(orderId);
        if (!rider.getId().equals(order.getRiderId())) {
            throw new AccessDeniedException("这不是你的订单");
        }
        return order;
    }

    private Rider requireRider(String username) {
        Account account = accountMapper.selectByUsername(username);
        if (account == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        Rider rider = riderMapper.selectByAccountId(account.getId());
        if (rider == null) {
            throw new IllegalArgumentException("当前账号未绑定骑手");
        }
        return rider;
    }
}
