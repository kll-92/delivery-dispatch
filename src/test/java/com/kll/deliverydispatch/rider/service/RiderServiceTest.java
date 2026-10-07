package com.kll.deliverydispatch.rider.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.common.audit.OperationLogService;
import com.kll.deliverydispatch.common.cache.RedisLockService;
import com.kll.deliverydispatch.rider.entity.Rider;
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
import org.mockito.InjectMocks;
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

    @InjectMocks
    private RiderService riderService;

    @BeforeEach
    void initializeMyBatisEntityMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Rider.class);
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
        rider.setLongitude(new BigDecimal("116.4000000"));
        rider.setLatitude(new BigDecimal("39.9000000"));
        when(riderMapper.selectById(7L)).thenReturn(rider);
        when(riderMapper.enableIfIdle(7L, 3, 1)).thenReturn(1);
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);

        Rider enabled = riderService.enable(7L);

        assertEquals(1, enabled.getStatus());
        verify(riderMapper).enableIfIdle(7L, 3, 1);
        verify(geoOperations).add(anyString(), any(Point.class), eq("rider:7"));
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
    void shouldSynchronizeLocationToGeoOnlyAfterTransactionCommit() {
        Rider before = rider(7L, 1, 0);
        Rider after = rider(7L, 1, 0);
        after.setLongitude(new BigDecimal("116.4000000"));
        after.setLatitude(new BigDecimal("39.9000000"));
        when(riderMapper.selectById(7L)).thenReturn(before, after);
        when(riderMapper.update(eq(null), any())).thenReturn(1);
        when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            riderService.updateLocation(7L,
                    new com.kll.deliverydispatch.rider.dto.RiderLocationRequest(
                            after.getLongitude(), after.getLatitude()));
            verifyNoInteractions(geoOperations);

            TransactionSynchronizationUtils.triggerAfterCommit();

            verify(geoOperations).add(anyString(), any(Point.class), eq("rider:7"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private Rider rider(Long id, Integer status, Integer currentOrderCount) {
        Rider rider = new Rider();
        rider.setId(id);
        rider.setStatus(status);
        rider.setCurrentOrderCount(currentOrderCount);
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
        when(riderMapper.selectBatchIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> {
                Rider rider = new Rider();
                rider.setId(id);
                rider.setRiderNo("R" + id);
                rider.setName("测试骑手" + id);
                rider.setStatus(1);
                rider.setCurrentOrderCount(id <= 20 ? 3 : 0);
                return rider;
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
}
