package com.kll.deliverydispatch.common.ratelimit;

/**
 * 一条限流规则。
 * <p>
 * {@code path} 支持 Ant 通配（如 {@code /api/v1/rider/me/orders/*\/accept}）。
 * 限流 key 用这个模板而不是真实 URI —— 否则每个 orderId 会生成一个独立的桶，限流直接失效。
 */
public record RateLimitRule(
        String method,
        String path,
        RateLimitScope scope,
        RateLimitLevel level,
        double capacity,
        double refillPerSecond) {
}
