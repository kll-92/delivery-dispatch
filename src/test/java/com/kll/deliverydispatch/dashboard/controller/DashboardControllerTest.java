package com.kll.deliverydispatch.dashboard.controller;

import com.kll.deliverydispatch.dashboard.dto.DashboardSummary;
import com.kll.deliverydispatch.dashboard.service.DashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {
    @Mock
    private DashboardService dashboardService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardController(dashboardService)).build();
    }

    @Test
    void summaryEndpointReturnsEveryAggregateCounter() throws Exception {
        when(dashboardService.summary()).thenReturn(new DashboardSummary(20, 4, 3, 2, 8, 3, 6, 4));

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(20))
                .andExpect(jsonPath("$.pendingOrders").value(4))
                .andExpect(jsonPath("$.assignedOrders").value(3))
                .andExpect(jsonPath("$.deliveringOrders").value(2))
                .andExpect(jsonPath("$.completedOrders").value(8))
                .andExpect(jsonPath("$.canceledOrders").value(3))
                .andExpect(jsonPath("$.totalRiders").value(6))
                .andExpect(jsonPath("$.availableRiders").value(4));

        verify(dashboardService).summary();
    }
}
