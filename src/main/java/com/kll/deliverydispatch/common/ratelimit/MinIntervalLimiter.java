package com.kll.deliverydispatch.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 最小间隔限流：同一个 key 在 intervalSeconds 内只允许成功一次。
 * <p>
 * 适用于「上报类」接口 —— 语义是「两次之间至少隔 N 秒」，而不是「单位时间多少次」。
 * 和令牌桶的区别：令牌桶管"频率"，这个东西管"间隔"。
 */
@Service
@RequiredArgsConstructor
public class MinIntervalLimiter {

    private static final Logger log = LoggerFactory.getLogger(MinIntervalLimiter.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * @param key             调用方拼的业务 key，例如 "loc-interval:4"
     * @param intervalSeconds 最小间隔（秒）
     * @return true = 首次，放行；false = 还在间隔内，应丢弃
     */
    public boolean tryAcquire(String key, long intervalSeconds) {
        // ============================================================
        // 你的任务：把下面 3 步写出来（大约 10 行）
        // ============================================================
        //
        // 第 1 步：一次性完成「不存在才写入」+「设置过期时间」。
        //   用到的 API：
        //     stringRedisTemplate.opsForValue()
        //         .setIfAbsent(key, 值, 时长, 单位)
        //   值随便，用 "1"。
        //   时长用 intervalSeconds，单位 TimeUnit.SECONDS。
        //   返回值是 Boolean（注意：可能是 null）。
        //
        // 第 2 步：把它转成 boolean 并返回。
        //   提示：Boolean.TRUE.equals(那个返回值)
        //
        // 第 3 步：整个 try 包起来，catch (DataAccessException e)
        //   catch 里：log.warn("...", e); 然后 return true;   // fail-open
        //
        // ------------------------------------------------------------
        // 为什么不能先 get 再 set：
        //   两个并发请求会同时读到"不存在"，都以为自己是第一个，
        //   结果两个都放行 —— 间隔限制直接失效。
        //   SETNX 是原子操作，只有一个能成功。这和限流 Lua 要解决的是同一个问题。
        // ------------------------------------------------------------
        try {
            Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", intervalSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(first);
        } catch (DataAccessException e) {
            log.warn("最小间隔限流 Redis 失败，放行 key={}", key, e);
            return true;
        }
    }
}
