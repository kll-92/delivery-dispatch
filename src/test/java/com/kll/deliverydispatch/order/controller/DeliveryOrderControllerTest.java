package com.kll.deliverydispatch.order.controller;

import com.kll.deliverydispatch.common.dto.PageResult;
import com.kll.deliverydispatch.order.entity.DeliveryOrder;
import com.kll.deliverydispatch.order.service.DeliveryOrderService;
import com.kll.deliverydispatch.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DeliveryOrderControllerTest {
    @Mock
    private DeliveryOrderService deliveryOrderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DeliveryOrderController(deliveryOrderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createAcceptsOnlyWhitelistedFieldsAndIgnoresClientManagedFields() throws Exception {
        when(deliveryOrderService.create(any(DeliveryOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantName":"店铺","merchantPhone":"座机010-12345678","merchantAddress":"地址",
                                 "merchantLongitude":116.4,"merchantLatitude":39.9,"receiverName":"收件人",
                                 "receiverPhone":"联系人座机","receiverAddress":"收货地址","receiverLongitude":117.0,
                                 "receiverLatitude":40.0,"deliveryFee":0,"id":99,"status":3,"riderId":7,
                                 "orderNo":"attacker-order","createdAt":"2020-01-01T00:00:00"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<DeliveryOrder> orderCaptor = ArgumentCaptor.forClass(DeliveryOrder.class);
        verify(deliveryOrderService).create(orderCaptor.capture());
        assertNull(orderCaptor.getValue().getId());
        assertNull(orderCaptor.getValue().getStatus());
        assertNull(orderCaptor.getValue().getRiderId());
        assertNull(orderCaptor.getValue().getOrderNo());
        assertNull(orderCaptor.getValue().getCreatedAt());
    }

    @Test
    void createRejectsFeeWithMoreThanTwoDecimalPlaces() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOrderJson("12.345")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(deliveryOrderService, never()).create(any(DeliveryOrder.class));
    }

    @Test
    void orderPageForwardsFiltersAndReturnsPageEnvelope() throws Exception {
        when(deliveryOrderService.page(1, "ORD-1", LocalDateTime.parse("2026-10-01T00:00:00"),
                LocalDateTime.parse("2026-10-07T23:59:59"), 2, 10))
                .thenReturn(new PageResult<>(List.of(new DeliveryOrder()), 21, 2, 10));

        mockMvc.perform(get("/api/v1/orders/page")
                        .param("page", "2").param("size", "10").param("status", "1")
                        .param("orderNo", "ORD-1")
                        .param("createdFrom", "2026-10-01T00:00:00")
                        .param("createdTo", "2026-10-07T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(21))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.records").isArray());

        verify(deliveryOrderService).page(1, "ORD-1", LocalDateTime.parse("2026-10-01T00:00:00"),
                LocalDateTime.parse("2026-10-07T23:59:59"), 2, 10);
    }

    @Test
    void orderPageForwardsPageSizeForServiceBoundaryValidation() throws Exception {
        mockMvc.perform(get("/api/v1/orders/page").param("page", "1").param("size", "101"))
                .andExpect(status().isOk());

        verify(deliveryOrderService).page(null, null, null, null, 1, 101);
    }

    private String validOrderJson(String fee) {
        return """
                {"merchantName":"店铺","merchantPhone":"01012345678","merchantAddress":"地址",
                 "merchantLongitude":116.4,"merchantLatitude":39.9,"receiverName":"收件人",
                 "receiverPhone":"13800000000","receiverAddress":"收货地址","receiverLongitude":117.0,
                 "receiverLatitude":40.0,"deliveryFee":%s}
                """.formatted(fee);
    }
}
