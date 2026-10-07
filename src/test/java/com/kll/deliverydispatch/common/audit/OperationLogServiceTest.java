package com.kll.deliverydispatch.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationLogServiceTest {
    @Mock
    private OperationLogMapper operationLogMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordSerializesSnapshotsAndUsesAuthenticatedUsername() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dispatcher", "password", java.util.List.of()));
        when(operationLogMapper.insert(org.mockito.ArgumentMatchers.any(OperationLog.class))).thenReturn(1);
        OperationLogService service = new OperationLogService(operationLogMapper, new ObjectMapper());

        service.record("UPDATE", "RIDER", 3L, Map.of("status", 3), Map.of("status", 1), "运营启用");

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogMapper).insert(captor.capture());
        assertEquals("dispatcher", captor.getValue().getActor());
        assertEquals("{\"status\":3}", captor.getValue().getBeforeJson());
        assertEquals("{\"status\":1}", captor.getValue().getAfterJson());
        assertEquals("运营启用", captor.getValue().getReason());
    }

    @Test
    void recordFailurePropagatesSoTheBusinessTransactionCanRollBack() {
        when(operationLogMapper.insert(org.mockito.ArgumentMatchers.any(OperationLog.class))).thenReturn(0);
        OperationLogService service = new OperationLogService(operationLogMapper, new ObjectMapper());

        assertThrows(IllegalStateException.class,
                () -> service.record("CREATE", "ORDER", 3L, null, Map.of("id", 3), null));
    }

    @Test
    void missingAuthenticationUsesSystemActor() {
        when(operationLogMapper.insert(org.mockito.ArgumentMatchers.any(OperationLog.class))).thenReturn(1);
        OperationLogService service = new OperationLogService(operationLogMapper, new ObjectMapper());

        service.record("DELETE", "ORDER", 4L, Map.of("id", 4), null, "后台清理");

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(operationLogMapper).insert(captor.capture());
        assertEquals("SYSTEM", captor.getValue().getActor());
    }

    @Test
    void invalidTargetIdIsRejectedBeforeQueryingLogs() {
        OperationLogService service = new OperationLogService(operationLogMapper, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.page(1, 20, null, 0L, null, null));

        verify(operationLogMapper, never()).selectCount(org.mockito.ArgumentMatchers.any());
    }
}
