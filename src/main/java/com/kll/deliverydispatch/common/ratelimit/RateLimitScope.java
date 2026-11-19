package com.kll.deliverydispatch.common.ratelimit;

/** 限流维度：按登录用户，或按客户端 IP。 */
public enum RateLimitScope {
    USER,
    IP
}
