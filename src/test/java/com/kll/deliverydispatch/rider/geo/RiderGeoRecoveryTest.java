package com.kll.deliverydispatch.rider.geo;

import com.kll.deliverydispatch.rider.location.RiderLocationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiderGeoRecoveryTest {

    @Test
    void disabledRecoveryDoesNotTouchDatabaseOrRedis() {
        RiderGeoIndexMapper mapper = mock(RiderGeoIndexMapper.class);
        RiderGeoIndexService indexService = mock(RiderGeoIndexService.class);
        RiderLocationProperties properties = new RiderLocationProperties();
        properties.setRecoveryEnabled(false);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RiderGeoRecovery recovery = new RiderGeoRecovery(mapper, indexService, properties, redis);

        recovery.recoverBatch();

        verifyNoInteractions(mapper, indexService, redis);
    }

    @Test
    void scansBoundedPagesAndCarriesRedisCursorAcrossBatches() {
        RiderGeoIndexMapper mapper = mock(RiderGeoIndexMapper.class);
        RiderGeoIndexService indexService = mock(RiderGeoIndexService.class);
        RiderLocationProperties properties = new RiderLocationProperties();
        properties.setRecoveryBatchSize(1);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        when(mapper.selectRiderIdsAfter(0L, 1)).thenReturn(List.of(1L));
        when(mapper.selectRiderIdsAfter(1L, 1)).thenReturn(List.of(2L));
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(scanPage("9", "rider:1"), scanPage("0", "rider:2"));
        RiderGeoRecovery recovery = new RiderGeoRecovery(mapper, indexService, properties, redis);

        recovery.recoverBatch();
        recovery.recoverBatch();

        verify(indexService, times(2)).synchronize(1L);
        verify(indexService, times(2)).synchronize(2L);
        var cursors = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(redis, times(2)).execute(any(RedisScript.class), eq(List.of(RiderGeoIndexService.GEO_KEY)),
                cursors.capture(), any());
        assertEquals("0", cursors.getAllValues().get(0));
        assertEquals("9", cursors.getAllValues().get(1));
    }

    private static List<String> scanPage(String cursor, String member) {
        return List.of(cursor, member);
    }
}
