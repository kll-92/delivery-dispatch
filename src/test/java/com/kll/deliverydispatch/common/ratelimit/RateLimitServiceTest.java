package com.kll.deliverydispatch.common.ratelimit;

import com.kll.deliverydispatch.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock private TokenBucketLimiter tokenBucketLimiter;

    private RateLimitProperties properties;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        service = new RateLimitService(properties, tokenBucketLimiter);
    }

    /** 只给真正会调用 limiter 的用例打桩，避免 Mockito 报 UnnecessaryStubbing。 */
    private void stubAllow() {
        when(tokenBucketLimiter.tryAcquire(anyString(), anyDouble(), anyDouble(), anyString(), any()))
                .thenReturn(RateLimitResult.allowed("/x", RateLimitLevel.P1));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void keyUsesRuleTemplateRatherThanRealUri() {
        properties.setRules(List.of(new RateLimitRule("PATCH", "/api/v1/rider/me/orders/*/accept",
                RateLimitScope.USER, RateLimitLevel.P0, 5, 0.15)));
        stubAllow();
        loginAs(7L);

        service.check(request("PATCH", "/api/v1/rider/me/orders/123/accept"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(tokenBucketLimiter).tryAcquire(keyCaptor.capture(), anyDouble(), anyDouble(), anyString(), any());
        String key = keyCaptor.getValue();

        // 关键的 path 模板，而不是 /123/
        assertTrue(key.contains("/api/v1/rider/me/orders/*/accept"), key);
        assertFalse(key.contains("/123/"), key);
        assertTrue(key.contains(":7:"), key);
        assertTrue(key.contains("PATCH"), key);
    }

    @Test
    void unmatchedPathFallsBackToDefaultRule() {
        stubAllow();
        service.check(request("GET", "/api/v1/something/unknown"));

        ArgumentCaptor<Double> capacity = ArgumentCaptor.forClass(Double.class);
        verify(tokenBucketLimiter).tryAcquire(anyString(), capacity.capture(), anyDouble(), anyString(), any());
        assertTrue(capacity.getValue() == 60d, "未命中规则应使用默认容量 60");
    }

    @Test
    void skipPathIsNotLimited() {
        service.check(request("GET", "/actuator/health"));
        verifyNoInteractions(tokenBucketLimiter);
    }

    @Test
    void disabledReturnsAllowedWithoutTouchingRedis() {
        properties.setEnabled(false);
        RateLimitResult result = service.check(request("POST", "/api/v1/merchant/me/orders"));

        assertTrue(result.allowed());
        verifyNoInteractions(tokenBucketLimiter);
    }

    @Test
    void anonymousUserScopeFallsBackToClientIp() {
        properties.setRules(List.of(new RateLimitRule("POST", "/api/v1/auth/register/merchant",
                RateLimitScope.USER, RateLimitLevel.P3, 3, 0.05)));
        stubAllow();

        MockHttpServletRequest request = request("POST", "/api/v1/auth/register/merchant");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.1");
        service.check(request);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(tokenBucketLimiter).tryAcquire(keyCaptor.capture(), anyDouble(), anyDouble(), anyString(), eq(RateLimitLevel.P3));
        assertTrue(keyCaptor.getValue().contains("1.2.3.4"), keyCaptor.getValue());
        assertFalse(keyCaptor.getValue().contains("10.0.0.1"), "只应取第一个 IP");
    }

    @Test
    void rejectedResultIsPropagated() {
        when(tokenBucketLimiter.tryAcquire(anyString(), anyDouble(), anyDouble(), anyString(), any()))
                .thenReturn(RateLimitResult.rejected(7, "/x", RateLimitLevel.P2));

        RateLimitResult result = service.check(request("GET", "/api/v1/riders/nearby"));

        assertFalse(result.allowed());
        assertTrue(result.retryAfterSeconds() == 7);
    }

    private void loginAs(Long accountId) {
        AccountPrincipal principal = new AccountPrincipal(accountId, "rider_zhang", "", "RIDER", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("9.9.9.9");
        return request;
    }
}
