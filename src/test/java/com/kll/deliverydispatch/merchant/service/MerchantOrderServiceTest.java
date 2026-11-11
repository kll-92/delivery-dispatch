package com.kll.deliverydispatch.merchant.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kll.deliverydispatch.account.entity.Account;
import com.kll.deliverydispatch.account.mapper.AccountMapper;
import com.kll.deliverydispatch.common.dto.PageResult;
import com.kll.deliverydispatch.merchant.entity.Merchant;
import com.kll.deliverydispatch.merchant.mapper.MerchantMapper;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.order.mapper.DeliveryOrderMapper;
import com.kll.deliverydispatch.order.service.DeliveryOrderService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantOrderServiceTest {
    @Mock private AccountMapper accountMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private DeliveryOrderMapper deliveryOrderMapper;
    @Mock private DeliveryOrderService deliveryOrderService;
    private MerchantOrderService merchantOrderService;

    /**
     * 纯单测没有 Spring 容器，MyBatis-Plus 的实体元数据缓存是空的，
     * 解析 DeliveryOrder::getMerchantId 这类方法引用会报 "can not find lambda cache"。
     * 这里手动初始化一次。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), DeliveryOrder.class);
    }

    @BeforeEach
    void setUp() {
        merchantOrderService = new MerchantOrderService(accountMapper, merchantMapper, deliveryOrderMapper, deliveryOrderService);
    }

    @Test
    void rejectsInvalidPagingBeforeTouchingDatabase() {
        assertThrows(IllegalArgumentException.class,
                () -> merchantOrderService.myOrders("shop01", null, 0, 20));
        assertThrows(IllegalArgumentException.class,
                () -> merchantOrderService.myOrders("shop01", null, 1, 101));

        verifyNoInteractions(accountMapper, merchantMapper, deliveryOrderMapper);
    }

    @Test
    void throwsWhenAccountNotFound() {
        assertThrows(IllegalArgumentException.class,
                () -> merchantOrderService.myOrders("ghost", null, 1, 20));

        verifyNoInteractions(merchantMapper, deliveryOrderMapper);
    }

    @Test
    void throwsWhenMerchantHasNoShop() {
        when(accountMapper.selectByUsername("shop01")).thenReturn(account(1L));

        assertThrows(IllegalArgumentException.class,
                () -> merchantOrderService.myOrders("shop01", null, 1, 20));

        verifyNoInteractions(deliveryOrderMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesOnlyOwnMerchantOrders() {
        when(accountMapper.selectByUsername("shop01")).thenReturn(account(1L));
        when(merchantMapper.selectByAccountId(1L)).thenReturn(merchant(9L));
        when(deliveryOrderMapper.selectCount(any())).thenReturn(2L);
        when(deliveryOrderMapper.selectList(any())).thenReturn(List.of(new DeliveryOrder(), new DeliveryOrder()));

        PageResult<DeliveryOrder> result = merchantOrderService.myOrders("shop01", null, 1, 20);

        assertEquals(2L, result.total());
        assertEquals(2, result.records().size());
        assertEquals(1, result.page());
        assertEquals(20, result.size());

        // 核心断言：查询条件里必须带 merchant_id，否则就是越权
        ArgumentCaptor<Wrapper<DeliveryOrder>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(deliveryOrderMapper).selectCount(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("merchant_id"), "查询条件必须包含 merchant_id：" + sql);
        assertFalse(sql.contains("status"), "status 为空时不应拼接状态条件：" + sql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesStatusFilterOnlyWhenProvided() {
        when(accountMapper.selectByUsername("shop01")).thenReturn(account(1L));
        when(merchantMapper.selectByAccountId(1L)).thenReturn(merchant(9L));
        when(deliveryOrderMapper.selectCount(any())).thenReturn(0L);
        when(deliveryOrderMapper.selectList(any())).thenReturn(List.of());

        merchantOrderService.myOrders("shop01", 2, 1, 20);

        ArgumentCaptor<Wrapper<DeliveryOrder>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(deliveryOrderMapper).selectCount(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("status"));
    }

    private static Account account(Long id) {
        Account account = new Account();
        account.setId(id);
        account.setUsername("shop01");
        account.setRole("MERCHANT");
        account.setEnabled(true);
        return account;
    }

    private static Merchant merchant(Long id) {
        Merchant merchant = new Merchant();
        merchant.setId(id);
        merchant.setAccountId(1L);
        merchant.setName("黑糖奶茶店");
        merchant.setEnabled(true);
        return merchant;
    }
}
