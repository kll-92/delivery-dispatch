package com.kll.deliverydispatch.common.ratelimit;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 执行令牌桶 Lua 脚本。整段逻辑在 Redis 内原子完成，避免"读-改-写"竞态。 */
@Service
@RequiredArgsConstructor
public class TokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    private final StringRedisTemplate stringRedisTemplate;
    private DefaultRedisScript<List> script;

    @PostConstruct
    @SuppressWarnings({"unchecked", "rawtypes"})
    void loadScript() throws IOException {
        try (InputStream in = new ClassPathResource("lua/token_bucket.lua").getInputStream()) {
            String lua = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            this.script = new DefaultRedisScript<>(lua, List.class);
        }
    }

    /**
     * 尝试取一个令牌。
     * <p>
     * Redis 不可用时 <b>fail-open</b>：限流是保护措施，不该成为新的故障点。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RateLimitResult tryAcquire(String key, double capacity, double refillPerSecond,
                                      String rulePath, RateLimitLevel level) {
        try {
            List result = stringRedisTemplate.execute(script, List.of(key),
                    String.valueOf(capacity), String.valueOf(refillPerSecond));
            if (result == null || result.size() < 2) {
                return RateLimitResult.allowed(rulePath, level);
            }
            long allowed = ((Number) result.get(0)).longValue();
            long retryAfter = ((Number) result.get(1)).longValue();
            return allowed == 1
                    ? RateLimitResult.allowed(rulePath, level)
                    : RateLimitResult.rejected(retryAfter, rulePath, level);
        } catch (DataAccessException exception) {
            log.warn("限流判定失败，放行请求（fail-open）key={}", key, exception);
            return RateLimitResult.allowed(rulePath, level);
        }
    }
}
