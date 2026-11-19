package com.kll.deliverydispatch.common.ratelimit;

/** 业务优先级：过载时按 P0 → P3 依次牺牲，P0 最不能拒绝。 */
public enum RateLimitLevel {
    P0, P1, P2, P3
}
