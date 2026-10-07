package com.kll.deliverydispatch.common.audit;

import com.kll.deliverydispatch.common.dto.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operation-logs")
@RequiredArgsConstructor
public class OperationLogController {
    private final OperationLogService operationLogService;

    @GetMapping("/page")
    public PageResult<OperationLog> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action) {
        return operationLogService.page(page, size, targetType, targetId, actor, action);
    }
}
