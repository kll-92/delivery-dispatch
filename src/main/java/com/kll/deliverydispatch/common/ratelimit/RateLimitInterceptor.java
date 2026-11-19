package com.kll.deliverydispatch.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.common.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/** 拦截 /api/** ，超限返回 429 + Retry-After。 */
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        RateLimitResult result = rateLimitService.check(request);
        if (result.allowed()) {
            return true;
        }

        long retryAfter = Math.max(1, result.retryAfterSeconds());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setHeader("X-RateLimit-Limit-Level", result.level().name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                new ApiError(HttpStatus.TOO_MANY_REQUESTS.value(),
                        "请求过于频繁，请 " + retryAfter + " 秒后重试"));
        return false;
    }
}
