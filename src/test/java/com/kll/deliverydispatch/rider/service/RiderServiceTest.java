package com.kll.deliverydispatch.rider.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.common.audit.OperationLogService;
import com.kll.deliverydispatch.common.cache.RedisLockService;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.geo.RiderGeoSyncScheduler;
import com.kll.deliverydispatch.rider.location.RiderLocationPolicy;
import com.kll.deliverydispatch.rider.location.RiderLocationProperties;
import com.kll.deliverydispatch.rider.mapper.RiderMapper;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderServiceTest {

    @Mock
    private RiderMapper riderMapper;

    @Mock
    private RedisLockService redisLockService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private GeoOperations<String, String> geoOperations;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private RiderGeoSyncScheduler riderGeoSyncScheduler;

    private RiderLocationPolicy riderLocationPolicy;
    private RiderService riderService;

    @BeforeEach
    void initializeMyBatisEntityMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Rider.class);
        riderLocationPolicy = new RiderLocationPolicy(new RiderLocationProperties());
        riderService = new RiderService(riderMapper, redisLockService, stringRedisTemplate,
                objectMapper, operationLogService, riderLocationPolicy, riderGeoSyncScheduler);
    }

    @Test
    void shouldNotWriteInvalidLocationCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> riderService.updateLocation(7L,
                new com.kll.deliverydispatch.rider.dto.RiderLocationRequest(
                        new BigDecimal("181"), new BigDecimal("40"))));

        verifyNoInteractions(riderMapper, stringRedisTemplate, operationLogService);
    }

    @Test
    void shouldRejectInvalidRiderPageBeforeQuerying() {
        assertThrows(IllegalArgumentException.class, () -> riderService.page(null, null, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> riderService.page(null, null, 1, 101));

        verifyNoInteractions(riderMapper);
    }

    @Test
    void shouldTreatEnableOfAlreadyAvailableRiderAsIdempotent() {
        Rider rider = rider(7L, 1, 0);
        when(riderMapper.selectById(7L)).thenReturn(rider);

        assertSame(rider, riderService.enable(7L));

        verify(riderMapper).selectById(7L);
        verifyNoInteractions(operationLogService, stringRedisTemplate);
    }

    @Test
    void shouldEnableOnlyAnIdleDisabledRiderAndAuditTheTransition() {
        Rider rider = rider(7L, 3, 0);
        LocalDateTime lastLocationAt = LocalDateTime.parse("2026-10-07T09:30:00.123456");
        rider.setLongitude(new BigDecimal("116.4000000"));
        rider.setLatitude(new BigDecimal("39.9000000"));
        rider.setLastLocationAt(lastLocationAt);
        when(riderMapper.selectById(7L)).thenReturn(rider);
        when(riderMapper.enableIfIdle(7L, 3, 1)).thenReturn(1);

        Rider enabled = riderService.enable(7L);

        assertEquals(1, enabled.getStatus());
        verify(riderMapper).enableIfIdle(7L, 3, 1);
        verify(riderGeoSyncScheduler).schedule(7L);
        assertEquals(lastLocationAt, enabled.getLastLocationAt());
        verify(operationLogService).record(eq("ENABLE"), eq("RIDER"), eq(7L),
                any(Rider.class), any(Rider.class), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void shouldResolveEnableRaceAsIdempotentWhenAnotherRequestEnabledRider() {
        Rider disabled = rider(7L, 3, 0);
        Rider enabled = rider(7L, 1, 0);
        when(riderMapper.selectById(7L)).thenReturn(disabled, enabled);
        when(riderMapper.enableIfIdle(7L, 3, 1)).thenReturn(0);

        assertSame(enabled, riderService.enable(7L));

        verify(riderMapper).enableIfIdle(7L, 3, 1);
        verifyNoInteractions(operationLogService, stringRedisTemplate);
    }

    @Test
    void shouldRefreshTimestampAndSynchronizeRepeatedLocationOnlyAfterTransactionCommit() {
        Rider before = rider(7L, 1, 0);
        before.setLongitude(new BigDecimal("116.4000000"));
        before.setLatitude(new BigDecimal("39.9000000"));
        Rider after = rider(7L, 1, 0);
        after.setLongitude(new BigDecimal("116.4000000"));
        after.setLatitude(new BigDecimal("39.9000000"));
        after.setLastLocationAt(LocalDateTime.parse("2026-10-07T10:00:00"));
        when(riderMapper.selectById(7L)).thenReturn(before, after);
        when(riderMapper.updateLocation(7L, after.getLongitude(), after.getLatitude())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            Rider updated = riderService.updateLocation(7L,
                    new com.kll.deliverydispatch.rider.dto.RiderLocationRequest(
                            after.getLongitude(), after.getLatitude()));
            assertEquals(after.getLastLocationAt(), updated.getLastLocationAt());
            verifyNoInteractions(riderGeoSyncScheduler);

            TransactionSynchronizationUtils.triggerAfterCommit();

            verify(riderGeoSyncScheduler).schedule(7L);
            verify(riderMapper).updateLocation(7L, after.getLongitude(), after.getLatitude());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void shouldCreateRiderWithDatabaseLocationTimeOnlyWhenCoordinatesAreSupplied() {
        LocalDateTime dbNow = LocalDateTime.parse("2026-10-07T10:00:00.123456");
        when(riderMapper.selectUtcNow()).thenReturn(dbNow);
        when(riderMapper.insert(any(Rider.class))).thenAnswer(invocation -> {
            Rider inserted = invocation.getArgument(0);
            inserted.setId(11L);
            return 1;
        });

        Rider created = new Rider();
        created.setRiderNo("R11");
        created.setName("测试骑手");
        created.setPhone("13800000011");
        created.setLongitude(new BigDecimal("116.4000000"));
        created.setLatitude(new BigDecimal("39.9000000"));
        created.setLastLocationAt(LocalDateTime.parse("2000-01-01T00:00:00"));
        when(riderMapper.selectCount(any())).thenReturn(0L);

        Rider result = riderService.create(created);

        assertEquals(dbNow, result.getLastLocationAt());
        verify(riderMapper).selectUtcNow();
        verify(riderGeoSyncScheduler).schedule(11L);
    }

    @Test
    void shouldNotCreateFreshLocationTimestampWithoutCoordinates() {
        LocalDateTime dbNow = LocalDateTime.parse("2026-10-07T10:00:00.123456");
        when(riderMapper.insert(any(Rider.class))).thenAnswer(invocation -> {
            Rider inserted = invocation.getArgument(0);
            inserted.setId(12L);
            return 1;
        });
        when(riderMapper.selectCount(any())).thenReturn(0L);

        Rider created = new Rider();
        created.setRiderNo("R12");
        created.setName("无坐标骑手");
        created.setPhone("13800000012");
        created.setLastLocationAt(dbNow);

        Rider result = riderService.create(created);

        assertEquals(null, result.getLastLocationAt());
        verify(riderMapper, org.mockito.Mockito.never()).selectUtcNow();
    }

    @Test
    void shouldUseDatabaseCoordinatesAndLocationFreshnessInsteadOfGeoDistance() {
        LocalDateTime dbNow = LocalDateTime.parse("2026-10-07T10:00:00");
        when(riderMapper.selectUtcNow()).thenReturn(dbNow);
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq("geo:rider:available"), any(GeoReference.class),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                .thenReturn(geoResults(1));
        Rider indexedAtOriginButActuallyFar = rider(1L, 1, 0);
        indexedAtOriginButActuallyFar.setLongitude(new BigDecimal("10.0000000"));
        indexedAtOriginButActuallyFar.setLatitude(new BigDecimal("10.0000000"));
        indexedAtOriginButActuallyFar.setLastLocationAt(dbNow.minusSeconds(10));
        when(riderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(indexedAtOriginButActuallyFar));

        var nearby = riderService.findNearby(BigDecimal.ZERO, BigDecimal.ZERO, 5, 20);

        assertEquals(0, nearby.size());
    }

    @Test
    void shouldSkipMalformedGeoMembersAndReadDatabaseTimeAfterCandidateBatch() {
        LocalDateTime dbNow = LocalDateTime.parse("2026-10-07T10:00:00");
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq("geo:rider:available"), any(GeoReference.class),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                .thenReturn(geoResultsWithMembers("invalid", "rider:0", "rider:2"));
        when(riderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                riderAt(2L, 1, 0, BigDecimal.ZERO, BigDecimal.ZERO, dbNow.minusSeconds(1))));
        when(riderMapper.selectUtcNow()).thenReturn(dbNow);

        var nearby = riderService.findNearby(BigDecimal.ZERO, BigDecimal.ZERO, 5, 20);

        assertEquals(1, nearby.size());
        assertEquals(2L, nearby.get(0).riderId());
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(riderMapper);
        inOrder.verify(riderMapper).selectBatchIds(anyCollection());
        inOrder.verify(riderMapper).selectUtcNow();
    }

    @Test
    void shouldFilterStaleNullFutureAndFullRidersFromNearbyResults() {
        LocalDateTime dbNow = LocalDateTime.parse("2026-10-07T10:00:00");
        when(riderMapper.selectUtcNow()).thenReturn(dbNow);
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq("geo:rider:available"), any(GeoReference.class),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                .thenReturn(geoResults(4));
        List<Rider> riders = List.of(
                riderAt(1L, 1, 0, BigDecimal.ZERO, BigDecimal.ZERO, dbNow.minusSeconds(300)),
                riderAt(2L, 1, 0, BigDecimal.ZERO, BigDecimal.ZERO, null),
                riderAt(3L, 1, 0, BigDecimal.ZERO, BigDecimal.ZERO, dbNow.plusSeconds(1)),
                riderAt(4L, 1, 3, BigDecimal.ZERO, BigDecimal.ZERO, dbNow.minusSeconds(1)));
        when(riderMapper.selectBatchIds(anyCollection())).thenReturn(riders);

        var nearby = riderService.findNearby(BigDecimal.ZERO, BigDecimal.ZERO, 5, 20);

        assertEquals(0, nearby.size());
    }

    @Test
    void shouldReturnDistanceComputedFromDatabaseCoordinates() {
        LocalDateTime dbNow = LocalDateTime.parse("2026-10-07T10:00:00");
        when(riderMapper.selectUtcNow()).thenReturn(dbNow);
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq("geo:rider:available"), any(GeoReference.class),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                .thenReturn(geoResults(1));
        Rider rider = riderAt(1L, 1, 0, new BigDecimal("0.0100000"), BigDecimal.ZERO,
                dbNow.minusSeconds(1));
        when(riderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(rider));

        var nearby = riderService.findNearby(BigDecimal.ZERO, BigDecimal.ZERO, 5, 20);

        assertEquals(1, nearby.size());
        assertEquals(1.112, nearby.get(0).distanceKm().doubleValue(), 0.01);
    }

    @Test
    void shouldSortCandidatesByRecomputedDatabaseDistance() {
        LocalDateTime dbNow = LocalDateTime.parse("2026-10-07T10:00:00");
        when(riderMapper.selectUtcNow()).thenReturn(dbNow);
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq("geo:rider:available"), any(GeoReference.class),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                .thenReturn(geoResults(2));
        when(riderMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                riderAt(1L, 1, 0, new BigDecimal("0.0400000"), BigDecimal.ZERO, dbNow.minusSeconds(1)),
                riderAt(2L, 1, 0, new BigDecimal("0.0100000"), BigDecimal.ZERO, dbNow.minusSeconds(1))));

        var nearby = riderService.findNearby(BigDecimal.ZERO, BigDecimal.ZERO, 5, 2);

        assertEquals(List.of(2L, 1L), nearby.stream().map(response -> response.riderId()).toList());
    }

    @Test
    void shouldKeepHaversineDistanceFiniteAcrossAntimeridianAndAntipodes() {
        double acrossAntimeridianKm = riderLocationPolicy.distanceKm(
                new BigDecimal("179.9999999"), BigDecimal.ZERO,
                new BigDecimal("-179.9999999"), BigDecimal.ZERO);
        double antipodalKm = riderLocationPolicy.distanceKm(
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("180.0000000"), BigDecimal.ZERO);

        org.junit.jupiter.api.Assertions.assertTrue(Double.isFinite(acrossAntimeridianKm));
        org.junit.jupiter.api.Assertions.assertTrue(acrossAntimeridianKm < 0.1);
        org.junit.jupiter.api.Assertions.assertTrue(Double.isFinite(antipodalKm));
        assertEquals(Math.PI * 6371.0088, antipodalKm, 0.001);
    }

    @Test
    void shouldKeepLocationTimestampWhenUpdatingOnlyProfileFields() {
        LocalDateTime lastLocationAt = LocalDateTime.parse("2026-10-07T09:30:00.123456");
        Rider before = riderAt(7L, 1, 0, new BigDecimal("116.4000000"),
                new BigDecimal("39.9000000"), lastLocationAt);
        Rider after = riderAt(7L, 1, 0, new BigDecimal("116.4000000"),
                new BigDecimal("39.9000000"), lastLocationAt);
        when(riderMapper.selectById(7L)).thenReturn(before, after);
        when(riderMapper.selectCount(any())).thenReturn(0L);
        when(riderMapper.updateProfile(7L, "新名字", "13800000007", null, null)).thenReturn(1);

        Rider updated = riderService.update(7L, profile("新名字", "13800000007", null, null));

        assertEquals(lastLocationAt, updated.getLastLocationAt());
        verify(riderMapper).updateProfile(7L, "新名字", "13800000007", null, null);
    }

    private Rider rider(Long id, Integer status, Integer currentOrderCount) {
        Rider rider = new Rider();
        rider.setId(id);
        rider.setStatus(status);
        rider.setCurrentOrderCount(currentOrderCount);
        return rider;
    }

    private Rider riderAt(Long id, Integer status, Integer count,
                          BigDecimal longitude, BigDecimal latitude, LocalDateTime lastLocationAt) {
        Rider rider = rider(id, status, count);
        rider.setRiderNo("R" + id);
        rider.setName("骑手" + id);
        rider.setLongitude(longitude);
        rider.setLatitude(latitude);
        rider.setLastLocationAt(lastLocationAt);
        return rider;
    }

    private Rider profile(String name, String phone, BigDecimal longitude, BigDecimal latitude) {
        Rider rider = new Rider();
        rider.setName(name);
        rider.setPhone(phone);
        rider.setLongitude(longitude);
        rider.setLatitude(latitude);
        return rider;
    }

    @Test
    void shouldNotDisableRiderIfAnOrderWasClaimedAfterTheRead() {
        Rider rider = new Rider();
        rider.setId(7L);
        rider.setStatus(1);
        rider.setCurrentOrderCount(0);
        when(riderMapper.selectById(7L)).thenReturn(rider);
        when(riderMapper.disableIfIdle(7L, 1, 3)).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> riderService.disable(7L));

        verify(riderMapper).disableIfIdle(7L, 1, 3);
        verifyNoInteractions(redisLockService, stringRedisTemplate, objectMapper);
    }

    @Test
    void shouldReadMySqlImmediatelyWhenRedisCacheReadFails() {
        Rider rider = new Rider();
        rider.setId(7L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("rider:detail:7"))
                .thenThrow(new RedisConnectionFailureException("Redis offline"));
        when(riderMapper.selectById(7L)).thenReturn(rider);

        assertSame(rider, riderService.getById(7L));

        verifyNoInteractions(redisLockService);
    }

    @Test
    void shouldReadMySqlWhenRedisLockAcquisitionFails() {
        Rider rider = new Rider();
        rider.setId(7L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("rider:detail:7")).thenReturn(null);
        when(redisLockService.tryLock(eq("rebuild:lock:rider:7"), anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis offline"));
        when(riderMapper.selectById(7L)).thenReturn(rider);

        assertSame(rider, riderService.getById(7L));
    }

    @Test
    void shouldFindAnEligibleRiderBeyondTheFirstPageOfFullRiders() {
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(eq("geo:rider:available"), any(GeoReference.class),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                .thenReturn(geoResults(20), geoResults(21));
        when(riderMapper.selectUtcNow()).thenReturn(LocalDateTime.parse("2026-10-07T10:00:00"));
        when(riderMapper.selectBatchIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> {
                return riderAt(id, 1, id <= 20 ? 3 : 0, BigDecimal.ZERO, BigDecimal.ZERO,
                        LocalDateTime.parse("2026-10-07T09:59:00"));
            }).toList();
        });

        var nearby = riderService.findNearby(BigDecimal.ZERO, BigDecimal.ZERO, 5, 20);

        assertEquals(1, nearby.size());
        assertEquals(21L, nearby.get(0).riderId());
        org.mockito.Mockito.verify(geoOperations, times(2)).search(
                eq("geo:rider:available"), any(GeoReference.class),
                any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class));
    }

    @Test
    void shouldRejectNonFiniteNearbyRadiusBeforeAccessingRedis() {
        assertThrows(IllegalArgumentException.class,
                () -> riderService.findNearby(BigDecimal.ZERO, BigDecimal.ZERO, Double.NaN, 20));
        assertThrows(IllegalArgumentException.class,
                () -> riderService.findNearby(BigDecimal.ZERO, BigDecimal.ZERO, Double.POSITIVE_INFINITY, 20));
        verifyNoInteractions(stringRedisTemplate);
    }

    private GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults(int count) {
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = IntStream.rangeClosed(1, count)
                .mapToObj(id -> new GeoResult<>(
                        new RedisGeoCommands.GeoLocation<>("rider:" + id, new Point(0, 0)),
                        new Distance(id * 0.01, Metrics.KILOMETERS)))
                .toList();
        return new GeoResults<>(content);
    }

    private GeoResults<RedisGeoCommands.GeoLocation<String>> geoResultsWithMembers(String... members) {
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = java.util.Arrays.stream(members)
                .map(member -> new GeoResult<>(
                        new RedisGeoCommands.GeoLocation<>(member, new Point(0, 0)),
                        new Distance(0, Metrics.KILOMETERS)))
                .toList();
        return new GeoResults<>(content);
    }
}
