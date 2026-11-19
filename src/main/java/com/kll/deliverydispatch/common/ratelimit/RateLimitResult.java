package com.kll.deliverydispatch.common.ratelimit;

/** 单次限流判定的结果。 */
public record RateLimitResult(boolean allowed, long retryAfterSeconds, String rulePath, RateLimitLevel level) {

    public static RateLimitResult allowed(String rulePath, RateLimitLevel level) {
        return new RateLimitResult(true, 0, rulePath, level);
    }

    public static RateLimitResult rejected(long retryAfterSeconds, String rulePath, RateLimitLevel level) {
        return new RateLimitResult(false, retryAfterSeconds, rulePath, level);
    }
}
