package com.kll.deliverydispatch.rider.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.common.cache.RedisLockService;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.mapper.RiderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private ObjectMapper objectMapper;

    @InjectMocks
    private RiderService riderService;

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
}
