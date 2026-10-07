package com.kll.deliverydispatch.common.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.common.dto.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OperationLogService {
    private static final int MAX_PAGE_SIZE = 100;

    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    /** Must join the business transaction so an audit failure rolls the operation back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String targetType, Long targetId,
                       Object before, Object after, String reason) {
        validateText(action, 32, "action");
        validateText(targetType, 32, "targetType");
        validateOptionalText(reason, 255, "reason");
        OperationLog log = new OperationLog();
        log.setAction(action.trim());
        log.setTargetType(targetType.trim());
        log.setTargetId(targetId);
        log.setActor(currentActor());
        log.setBeforeJson(toJson(before));
        log.setAfterJson(toJson(after));
        log.setReason(trimToNull(reason));
        if (operationLogMapper.insert(log) != 1) {
            throw new IllegalStateException("操作记录写入失败，业务操作已取消");
        }
    }

    public PageResult<OperationLog> page(int page, int size, String targetType,
                                         Long targetId, String actor, String action) {
        validatePage(page, size);
        if (targetId != null && targetId <= 0) {
            throw new IllegalArgumentException("targetId 必须大于 0");
        }
        validateOptionalText(targetType, 32, "targetType");
        validateOptionalText(actor, 64, "actor");
        validateOptionalText(action, 32, "action");
        var query = Wrappers.<OperationLog>lambdaQuery()
                .eq(StringUtils.hasText(targetType), OperationLog::getTargetType, trimToNull(targetType))
                .eq(targetId != null, OperationLog::getTargetId, targetId)
                .eq(StringUtils.hasText(actor), OperationLog::getActor, trimToNull(actor))
                .eq(StringUtils.hasText(action), OperationLog::getAction, trimToNull(action));
        long total = operationLogMapper.selectCount(query);
        long offset = (long) (page - 1) * size;
        List<OperationLog> records = operationLogMapper.selectList(query.clone()
                .orderByDesc(OperationLog::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
        return new PageResult<>(records, total, page, size);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("操作记录序列化失败，业务操作已取消", exception);
        }
    }

    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return "SYSTEM";
        }
        String username = authentication.getName();
        return StringUtils.hasText(username) ? username : "SYSTEM";
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static void validatePage(int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须大于等于 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size 必须在 1 到 100 之间");
        }
    }

    private static void validateText(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value) || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + "不能为空且长度不能超过 " + maxLength + " 个字符");
        }
    }

    private static void validateOptionalText(String value, int maxLength, String field) {
        if (StringUtils.hasText(value) && value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + "长度不能超过 " + maxLength + " 个字符");
        }
    }
}
