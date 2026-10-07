package com.kll.deliverydispatch.common.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisLockService {

    private final StringRedisTemplate stringRedisTemplate;
    private static final Logger log = LoggerFactory.getLogger(RedisLockService.class);
    public boolean tryLock(String lockKey, String lockToken) {
        // Let callers distinguish Redis failure from an ordinary lock contender.
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(locked);
    }
    public void unlock(String lockKey, String lockToken) {
        String script = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """;

        try {
            stringRedisTemplate.execute(
                    new DefaultRedisScript<>(script, Long.class),
                    List.of(lockKey),
                    lockToken
            );
        } catch (DataAccessException e) {
            log.warn("Redis 释放缓存重建锁失败，lockKey={}", lockKey, e);
        }
    }

}
