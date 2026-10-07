package com.kll.deliverydispatch.common.audit;

import com.kll.deliverydispatch.common.dto.PageResult;
import com.kll.deliverydispatch.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OperationLogControllerTest {
    @Mock
    private OperationLogService operationLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OperationLogController(operationLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void pageForwardsAuditFiltersAndReturnsPageEnvelope() throws Exception {
        OperationLog record = new OperationLog();
        record.setAction("DELETE");
        record.setTargetType("ORDER");
        record.setTargetId(88L);
        when(operationLogService.page(2, 15, "ORDER", 88L, "ops", "DELETE"))
                .thenReturn(new PageResult<>(List.of(record), 41, 2, 15));

        mockMvc.perform(get("/api/v1/operation-logs/page")
                        .param("page", "2").param("size", "15")
                        .param("targetType", "ORDER").param("targetId", "88")
                        .param("actor", "ops").param("action", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(41))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(15))
                .andExpect(jsonPath("$.records[0].action").value("DELETE"))
                .andExpect(jsonPath("$.records[0].targetId").value(88));

        verify(operationLogService).page(2, 15, "ORDER", 88L, "ops", "DELETE");
    }
}
