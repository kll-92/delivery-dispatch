package com.kll.deliverydispatch.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinIntervalLimiterTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    /** 第一次放行，间隔内第二次拒绝。 */
    @Test
    void firstCallAllowedSecondRejected() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true, false);

        MinIntervalLimiter limiter = new MinIntervalLimiter(stringRedisTemplate);

        assertTrue(limiter.tryAcquire("loc-interval:4", 3), "首次上报应放行");
        assertFalse(limiter.tryAcquire("loc-interval:4", 3), "间隔内重复上报应丢弃");
    }

    /** Redis 异常时放行（fail-open）。 */
    @Test
    void redisFailureFailsOpen() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new DataAccessException("redis down") {});

        MinIntervalLimiter limiter = new MinIntervalLimiter(stringRedisTemplate);

        assertTrue(limiter.tryAcquire("loc-interval:4", 3), "Redis 故障时应 fail-open");
    }

    /** setIfAbsent 返回 null 时按 false 处理，不抛 NPE。 */
    @Test
    void nullResultTreatedAsRejected() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        MinIntervalLimiter limiter = new MinIntervalLimiter(stringRedisTemplate);

        assertFalse(limiter.tryAcquire("loc-interval:4", 3));
    }
}
