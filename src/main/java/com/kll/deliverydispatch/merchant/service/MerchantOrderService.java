package com.kll.deliverydispatch.merchant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kll.deliverydispatch.account.entity.Account;
import com.kll.deliverydispatch.account.mapper.AccountMapper;
import com.kll.deliverydispatch.common.dto.PageResult;
import com.kll.deliverydispatch.merchant.dto.CreateMerchantOrderRequest;
import com.kll.deliverydispatch.merchant.entity.Merchant;
import com.kll.deliverydispatch.merchant.mapper.MerchantMapper;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.order.mapper.DeliveryOrderMapper;
import com.kll.deliverydispatch.order.service.DeliveryOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 商家端：查询自己的订单、以自己的店铺身份下单。 */
@Service
@RequiredArgsConstructor
public class MerchantOrderService {

    private final AccountMapper accountMapper;
    private final MerchantMapper merchantMapper;
    private final DeliveryOrderMapper deliveryOrderMapper;
    private final DeliveryOrderService deliveryOrderService;

    public PageResult<DeliveryOrder> myOrders(String username, Integer status, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("分页参数不合法：page 需 >= 1，size 需在 1~100 之间");
        }
        Merchant merchant = requireMerchant(username);

        var query = Wrappers.<DeliveryOrder>lambdaQuery()
                .eq(DeliveryOrder::getMerchantId, merchant.getId())
                .eq(status != null, DeliveryOrder::getStatus, status);

        long total = deliveryOrderMapper.selectCount(query);

        long offset = (long) (page - 1) * size;
        List<DeliveryOrder> records = deliveryOrderMapper.selectList(
                query.clone()
                        .orderByDesc(DeliveryOrder::getId)
                        .last("LIMIT " + size + " OFFSET " + offset));

        return new PageResult<>(records, total, page, size);
    }

    /**
     * 商家下单。商家信息（店名/电话/地址/坐标）全部取自数据库，
     * 请求体里就算带了也不会被采用——这是防止冒充其他店铺下单的关键。
     */
    @Transactional
    public DeliveryOrder createOrder(String username, CreateMerchantOrderRequest request) {
        Merchant merchant = requireMerchant(username);
        if (!Boolean.TRUE.equals(merchant.getEnabled())) {
            throw new IllegalArgumentException("店铺已停用，无法下单");
        }

        DeliveryOrder order = new DeliveryOrder();
        order.setMerchantId(merchant.getId());
        order.setMerchantName(merchant.getName());
        order.setMerchantPhone(merchant.getPhone());
        order.setMerchantAddress(merchant.getAddress());
        order.setMerchantLongitude(merchant.getLongitude());
        order.setMerchantLatitude(merchant.getLatitude());
        order.setReceiverName(request.receiverName());
        order.setReceiverPhone(request.receiverPhone());
        order.setReceiverAddress(request.receiverAddress());
        order.setReceiverLongitude(request.receiverLongitude());
        order.setReceiverLatitude(request.receiverLatitude());
        order.setDeliveryFee(request.deliveryFee());

        return deliveryOrderService.create(order);
    }

    /** 从登录名解析出店铺，账号不存在或未开店都直接拒绝。 */
    private Merchant requireMerchant(String username) {
        Account account = accountMapper.selectByUsername(username);
        if (account == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        Merchant merchant = merchantMapper.selectByAccountId(account.getId());
        if (merchant == null) {
            throw new IllegalArgumentException("当前账号未开店");
        }
        return merchant;
    }
}
