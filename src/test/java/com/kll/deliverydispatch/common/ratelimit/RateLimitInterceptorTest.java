package com.kll.deliverydispatch.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitInterceptorTest {

    @Test
    void rejectedRequestReturns429WithRetryAfter() throws Exception {
        RateLimitService service = mock(RateLimitService.class);
        when(service.check(any())).thenReturn(RateLimitResult.rejected(7, "/api/v1/orders", RateLimitLevel.P0));

        RateLimitInterceptor interceptor = new RateLimitInterceptor(service, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchant/me/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertFalse(proceed, "超限时不应继续执行");
        assertEquals(429, response.getStatus());
        assertEquals("7", response.getHeader("Retry-After"));
        assertEquals("P0", response.getHeader("X-RateLimit-Limit-Level"));
        assertTrue(response.getContentAsString().contains("请求过于频繁"));
    }

    @Test
    void allowedRequestContinues() throws Exception {
        RateLimitService service = mock(RateLimitService.class);
        when(service.check(any())).thenReturn(RateLimitResult.allowed("/x", RateLimitLevel.P1));

        RateLimitInterceptor interceptor = new RateLimitInterceptor(service, new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v1/ping"), response, new Object());

        assertTrue(proceed);
        assertEquals(200, response.getStatus());
    }
}
