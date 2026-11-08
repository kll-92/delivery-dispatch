package com.kll.deliverydispatch.rider.geo;

import com.kll.deliverydispatch.common.cache.RedisLockService;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.location.RiderLocationPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Synchronizes one rider's database state into the shared Redis GEO index. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiderGeoIndexService {
    static final String GEO_KEY = "geo:rider:available";
    static final String MEMBER_PREFIX = "rider:";
    static final String LOCK_PREFIX = "geo:sync:lock:rider:";

    private static final DefaultRedisScript<Long> GUARDED_GEO_WRITE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return -1
            end
            if ARGV[2] == 'ADD' then
                redis.call('GEOADD', KEYS[2], ARGV[3], ARGV[4], ARGV[5])
            else
                redis.call('ZREM', KEYS[2], ARGV[5])
            end
            return 1
            """, Long.class);

    private final RiderGeoIndexReader reader;
    private final RiderLocationPolicy locationPolicy;
    private final RedisLockService redisLockService;
    private final StringRedisTemplate redisTemplate;

    public SyncResult synchronize(Long riderId) {
        if (riderId == null || riderId <= 0) {
            log.warn("忽略无效骑手 ID 的 GEO 同步，riderId={}", riderId);
            return SyncResult.ERROR;
        }

        String lockKey = LOCK_PREFIX + riderId;
        String lockToken = UUID.randomUUID().toString();
        boolean lockAcquired = false;
        try {
            lockAcquired = redisLockService.tryLock(lockKey, lockToken);
            if (!lockAcquired) {
                return SyncResult.SKIPPED;
            }

            RiderGeoIndexSnapshot snapshot = reader.readLatest(riderId);
            Rider rider = snapshot.rider();
            boolean shouldBeIndexed = locationPolicy.isEligible(rider, snapshot.databaseNow());
            String operation = shouldBeIndexed ? "ADD" : "REMOVE";
            String member = MEMBER_PREFIX + riderId;
            String longitude = shouldBeIndexed ? rider.getLongitude().toPlainString() : "0";
            String latitude = shouldBeIndexed ? rider.getLatitude().toPlainString() : "0";
            Long result = redisTemplate.execute(GUARDED_GEO_WRITE,
                    List.of(lockKey, GEO_KEY), lockToken, operation, longitude, latitude, member);

            if (result == null) {
                return SyncResult.ERROR;
            }
            if (result < 0) {
                // The lease expired or was replaced before the atomic write.
                return SyncResult.SKIPPED;
            }
            return shouldBeIndexed ? SyncResult.ADDED : SyncResult.REMOVED;
        } catch (RuntimeException e) {
            // GEO is a derived index: Redis/DB failures must not escape an after-commit callback.
            log.warn("骑手 GEO 索引同步失败，riderId={}", riderId, e);
            return SyncResult.ERROR;
        } finally {
            if (lockAcquired) {
                try {
                    redisLockService.unlock(lockKey, lockToken);
                } catch (RuntimeException e) {
                    log.warn("释放骑手 GEO 同步锁失败，riderId={}", riderId, e);
                }
            }
        }
    }

    public enum SyncResult {
        ADDED,
        REMOVED,
        SKIPPED,
        ERROR
    }
}
