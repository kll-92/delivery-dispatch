package com.kll.deliverydispatch.rider.controller;

import com.kll.deliverydispatch.common.dto.PageResult;
import com.kll.deliverydispatch.common.exception.GlobalExceptionHandler;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.service.RiderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
class RiderControllerTest {
    @Mock
    private RiderService riderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RiderController(riderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createAcceptsOnlyWhitelistedFieldsAndAllowsNonMobilePhoneFormats() throws Exception {
        when(riderService.create(any(Rider.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/v1/riders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"riderNo":"R-1","name":"骑手","phone":"010-12345678",
                                 "longitude":116.4,"latitude":39.9,"id":99,"status":3,
                                 "currentOrderCount":10,"createdAt":"2020-01-01T00:00:00"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<Rider> riderCaptor = ArgumentCaptor.forClass(Rider.class);
        verify(riderService).create(riderCaptor.capture());
        assertNull(riderCaptor.getValue().getId());
        assertNull(riderCaptor.getValue().getStatus());
        assertNull(riderCaptor.getValue().getCurrentOrderCount());
        assertNull(riderCaptor.getValue().getCreatedAt());
        assertEquals("010-12345678", riderCaptor.getValue().getPhone());
    }

    @Test
    void updateRejectsPartialCoordinatesBeforeCallingService() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/riders/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"骑手\",\"phone\":\"座机\",\"longitude\":116.4}"))
                .andExpect(status().isBadRequest());

        verify(riderService, never()).update(org.mockito.ArgumentMatchers.eq(1L), any(Rider.class));
    }

    @Test
    void riderPageForwardsFiltersAndReturnsPageEnvelope() throws Exception {
        when(riderService.page(1, "王", 2, 25))
                .thenReturn(new PageResult<>(List.of(new Rider()), 31, 2, 25));

        mockMvc.perform(get("/api/v1/riders/page")
                        .param("page", "2").param("size", "25")
                        .param("status", "1").param("keyword", "王"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(31))
                .andExpect(jsonPath("$.records").isArray());

        verify(riderService).page(1, "王", 2, 25);
    }

    @Test
    void riderPageForwardsPageForServiceBoundaryValidation() throws Exception {
        mockMvc.perform(get("/api/v1/riders/page").param("page", "0"))
                .andExpect(status().isOk());

        verify(riderService).page(null, null, 0, 20);
    }
}
