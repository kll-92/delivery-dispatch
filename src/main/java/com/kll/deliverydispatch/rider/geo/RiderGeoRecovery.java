package com.kll.deliverydispatch.rider.geo;

import com.kll.deliverydispatch.rider.location.RiderLocationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Best-effort, bounded reconciliation of the database rider set and Redis GEO
 * members. Each rider write is still serialized by RiderGeoIndexService.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RiderGeoRecovery {
    private final RiderGeoIndexMapper mapper;
    private final RiderGeoIndexService geoIndexService;
    private final RiderLocationProperties properties;
    private final StringRedisTemplate redisTemplate;

    private static final DefaultRedisScript<List> GEO_SCAN_PAGE = new DefaultRedisScript<>("""
            local page = redis.call('ZSCAN', KEYS[1], ARGV[1], 'COUNT', ARGV[2])
            local result = {page[1]}
            for i = 1, #page[2], 2 do
                table.insert(result, page[2][i])
            end
            return result
            """, List.class);

    private long lastScannedRiderId;
    private String geoCursor = "0";
    private final Deque<String> pendingGeoMembers = new ArrayDeque<>();

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        recoverBatch();
    }

    @Scheduled(fixedDelayString = "${dispatch.rider-location.recovery-delay-ms:30000}")
    public synchronized void recoverBatch() {
        if (!properties.isRecoveryEnabled()) {
            return;
        }

        int batchSize = properties.getRecoveryBatchSize();
        try {
            reconcileDatabaseBatch(batchSize);
            reconcileGeoBatch(batchSize);
        } catch (RuntimeException e) {
            // Leave the relevant cursor where it is so the next scheduled pass can retry.
            log.warn("骑手 GEO 索引恢复批次失败，下一轮继续，lastRiderId={}, geoCursor={}",
                    lastScannedRiderId, geoCursor, e);
        }
    }

    private void reconcileDatabaseBatch(int batchSize) {
        List<Long> riderIds = mapper.selectRiderIdsAfter(lastScannedRiderId, batchSize);
        if (riderIds == null || riderIds.isEmpty()) {
            lastScannedRiderId = 0;
            return;
        }

        for (Long riderId : riderIds) {
            lastScannedRiderId = riderId;
            geoIndexService.synchronize(riderId);
        }
    }

    private void reconcileGeoBatch(int batchSize) {
        if (pendingGeoMembers.isEmpty()) {
            GeoScanPage page = scanGeoPage(geoCursor, batchSize);
            geoCursor = page.nextCursor();
            pendingGeoMembers.addAll(page.members());
        }

        int processed = 0;
        while (processed < batchSize && !pendingGeoMembers.isEmpty()) {
            reconcileGeoMember(pendingGeoMembers.removeFirst());
            processed++;
        }
    }

    private void reconcileGeoMember(String member) {
        Long riderId = riderIdFromMember(member);
        if (riderId == null) {
            // This exact malformed member cannot be produced by the normal writer.
            redisTemplate.opsForZSet().remove(RiderGeoIndexService.GEO_KEY, member);
            return;
        }
        // Reloads current DB state under the rider lock; stale scan observations
        // can therefore never remove a newer location for the same rider.
        geoIndexService.synchronize(riderId);
    }

    private GeoScanPage scanGeoPage(String cursor, int count) {
        List<?> values = redisTemplate.execute(GEO_SCAN_PAGE,
                List.of(RiderGeoIndexService.GEO_KEY), cursor, Integer.toString(count));
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException("Redis ZSCAN 返回格式无效");
        }

        String nextCursor = asString(values.get(0));
        List<String> members = new ArrayList<>(Math.max(0, values.size() - 1));
        for (int i = 1; i < values.size(); i++) {
            members.add(asString(values.get(i)));
        }
        return new GeoScanPage(nextCursor, members);
    }

    private static String asString(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalStateException("Redis ZSCAN 返回成员类型无效");
    }

    private static Long riderIdFromMember(String member) {
        if (member == null || !member.startsWith(RiderGeoIndexService.MEMBER_PREFIX)) {
            return null;
        }
        String suffix = member.substring(RiderGeoIndexService.MEMBER_PREFIX.length());
        try {
            long riderId = Long.parseLong(suffix);
            if (riderId <= 0 || !member.equals(RiderGeoIndexService.MEMBER_PREFIX + riderId)) {
                return null;
            }
            return riderId;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record GeoScanPage(String nextCursor, List<String> members) {
    }
}
