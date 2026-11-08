package com.kll.deliverydispatch.rider.geo;

import com.kll.deliverydispatch.common.cache.RedisLockService;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.location.RiderLocationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.kll.deliverydispatch.rider.geo.RiderGeoIndexService.SyncResult.ADDED;
import static com.kll.deliverydispatch.rider.geo.RiderGeoIndexService.SyncResult.ERROR;
import static com.kll.deliverydispatch.rider.geo.RiderGeoIndexService.SyncResult.REMOVED;
import static com.kll.deliverydispatch.rider.geo.RiderGeoIndexService.SyncResult.SKIPPED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderGeoIndexServiceTest {
    @Mock
    private RiderGeoIndexReader reader;
    @Mock
    private RiderLocationPolicy locationPolicy;
    @Mock
    private RedisLockService redisLockService;
    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private RiderGeoIndexService service;

    @Test
    void addsFreshEligibleRiderUsingLockGuardedLua() {
        Rider rider = new Rider();
        rider.setId(12L);
        rider.setLongitude(new BigDecimal("116.4"));
        rider.setLatitude(new BigDecimal("39.9"));
        when(redisLockService.tryLock(eq("geo:sync:lock:rider:12"), any())).thenReturn(true);
        when(reader.readLatest(12L)).thenReturn(new RiderGeoIndexSnapshot(rider, LocalDateTime.now()));
        when(locationPolicy.isEligible(eq(rider), any())).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        assertEquals(ADDED, service.synchronize(12L));

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> operation = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> longitude = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> latitude = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> member = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), token.capture(),
                operation.capture(), longitude.capture(), latitude.capture(), member.capture());
        assertEquals(List.of("geo:sync:lock:rider:12", "geo:rider:available"), keys.getValue());
        assertEquals("ADD", operation.getValue());
        assertEquals("116.4", longitude.getValue());
        assertEquals("39.9", latitude.getValue());
        assertEquals("rider:12", member.getValue());
        verify(redisLockService).unlock(eq("geo:sync:lock:rider:12"), eq(token.getValue()));
    }

    @Test
    void removesIneligibleRiderWithoutDeletingTheWholeGeoKey() {
        Rider rider = new Rider();
        when(redisLockService.tryLock(eq("geo:sync:lock:rider:12"), any())).thenReturn(true);
        when(reader.readLatest(12L)).thenReturn(new RiderGeoIndexSnapshot(rider, LocalDateTime.now()));
        when(locationPolicy.isEligible(eq(rider), any())).thenReturn(false);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        assertEquals(REMOVED, service.synchronize(12L));

        verify(redisTemplate, never()).delete(any(String.class));
        verify(redisLockService).unlock(eq("geo:sync:lock:rider:12"), any());
    }

    @Test
    void skipsWhenAnotherSyncOwnsRiderLock() {
        when(redisLockService.tryLock(eq("geo:sync:lock:rider:12"), any())).thenReturn(false);

        assertEquals(SKIPPED, service.synchronize(12L));

        verifyNoInteractions(reader, locationPolicy, redisTemplate);
        verify(redisLockService, never()).unlock(any(), any());
    }

    @Test
    void refusesWriteWhenLockTokenWasReplacedBeforeLuaRuns() {
        when(redisLockService.tryLock(eq("geo:sync:lock:rider:12"), any())).thenReturn(true);
        when(reader.readLatest(12L)).thenReturn(new RiderGeoIndexSnapshot(null, LocalDateTime.now()));
        when(locationPolicy.isEligible(eq(null), any())).thenReturn(false);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(-1L);

        assertEquals(SKIPPED, service.synchronize(12L));

        verify(redisLockService).unlock(eq("geo:sync:lock:rider:12"), any());
    }

    @Test
    void convertsDatabaseFailureToErrorForAfterCommitCaller() {
        when(redisLockService.tryLock(eq("geo:sync:lock:rider:12"), any())).thenReturn(true);
        when(reader.readLatest(12L)).thenThrow(new IllegalStateException("database unavailable"));

        assertEquals(ERROR, service.synchronize(12L));

        verifyNoInteractions(redisTemplate);
        verify(redisLockService).unlock(eq("geo:sync:lock:rider:12"), any());
    }

    @Test
    void convertsRedisFailureToErrorForAfterCommitCaller() {
        when(redisLockService.tryLock(eq("geo:sync:lock:rider:12"), any())).thenReturn(true);
        when(reader.readLatest(12L)).thenReturn(new RiderGeoIndexSnapshot(null, LocalDateTime.now()));
        when(locationPolicy.isEligible(eq(null), any())).thenReturn(false);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis offline"));

        assertEquals(ERROR, service.synchronize(12L));
        verify(redisLockService).unlock(eq("geo:sync:lock:rider:12"), any());
    }
}
