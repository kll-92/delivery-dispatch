package com.kll.deliverydispatch.rider.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kll.deliverydispatch.common.cache.RedisLockService;
import com.kll.deliverydispatch.rider.dto.NearbyRiderResponse;
import com.kll.deliverydispatch.rider.dto.RiderLocationRequest;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.enums.RiderStatus;
import com.kll.deliverydispatch.rider.mapper.RiderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RiderService {
    private final RiderMapper riderMapper;
    private final RedisLockService redisLockService;
    private static final String RIDER_DETAIL_KEY_PREFIX="rider:detail:";
    private static final String RIDER_REBUILD_LOCK_PREFIX = "rebuild:lock:rider:";
    private static final String AVAILABLE_RIDER_GEO_KEY = "geo:rider:available";
    private static final String GEO_RIDER_MEMBER_PREFIX = "rider:";
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    //记录日志
    private static final Logger log = LoggerFactory.getLogger(RiderService.class);

    //创建骑手

    public Rider create(Rider rider){
        if(!StringUtils.hasText(rider.getRiderNo())){
            throw new IllegalArgumentException("骑手编号不能为空");
        }
        if(!StringUtils.hasText(rider.getName())){
            throw new IllegalArgumentException("骑手名字不能为空");
        }
        if(!StringUtils.hasText(rider.getPhone())){
            throw new IllegalArgumentException("骑手手机号不能为空");
        }

        Long riderNoCount= riderMapper.selectCount(
                Wrappers.<Rider>lambdaQuery()
                        .eq(Rider::getRiderNo,rider.getRiderNo()));
        if(riderNoCount>0) {
            throw new IllegalArgumentException("骑手编号已存在");
        }
        Long phoneCount= riderMapper.selectCount(
                Wrappers.<Rider>lambdaQuery()
                        .eq(Rider::getPhone,rider.getPhone())
        );
        if(phoneCount>0){
            throw new IllegalArgumentException("手机号已存在");
        }
        rider.setStatus(RiderStatus.AVAILABLE.getCode());
        rider.setCurrentOrderCount(0);
        riderMapper.insert(rider);
        syncRiderLocationToGeo(rider);
        return rider;
    }

    //查询骑手
    public Rider getById(Long id) {
        String cacheKey = RIDER_DETAIL_KEY_PREFIX+id;
        Rider cachedRider = readRiderFromCache(cacheKey);
        if (cachedRider != null) {
            return cachedRider;
        }

        //抢锁
        String lockKey = RIDER_REBUILD_LOCK_PREFIX + id;
        String lockToken = UUID.randomUUID().toString();
        boolean lockAcquired = redisLockService.tryLock(lockKey, lockToken);
        if (!lockAcquired) {
            Rider waitedRider = waitForRiderCache(cacheKey);
            if (waitedRider != null) {
                return waitedRider;
            }
            throw new IllegalStateException("骑手详情正在重建，请稍后重试");
        }
        try {
            Rider cachedAfterLock = readRiderFromCache(cacheKey);
            if (cachedAfterLock != null) {
                return cachedAfterLock;
            }

            Rider rider = riderMapper.selectById(id);
            if (rider == null) {
                try {
                    stringRedisTemplate.opsForValue()
                            .set(cacheKey, "", 60, TimeUnit.SECONDS);
                } catch (DataAccessException e) {
                    log.warn("Redis 写入骑手空缓存失败，cacheKey={}", cacheKey, e);
                }
                throw new IllegalArgumentException("骑手不存在");
            }
            try{
                String riderJson = objectMapper.writeValueAsString(rider);
                long ttlSeconds =600+ ThreadLocalRandom.current().nextLong(61);
                stringRedisTemplate.opsForValue()
                        .set(cacheKey,riderJson,ttlSeconds, TimeUnit.SECONDS);
            } catch (JsonProcessingException e){
                log.warn("骑手缓存序列化失败，cacheKey={}", cacheKey, e);
            } catch (DataAccessException e) {
                log.warn("Redis 写入骑手缓存失败，cacheKey={}", cacheKey, e);
            }
            return rider;
        } finally {
            redisLockService.unlock(lockKey, lockToken);
        }
    }

    private Rider readRiderFromCache(String cacheKey) {
        String cacheJson = null;
        try {
            cacheJson = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (DataAccessException e){
            log.warn("Redis 读取骑手缓存失败，cacheKey={}", cacheKey, e);
        }
        if (cacheJson != null && cacheJson.isEmpty()) {
            throw new IllegalArgumentException("骑手不存在");
        }
        if (StringUtils.hasText(cacheJson)) {
            try {
                return objectMapper.readValue(cacheJson, Rider.class);
            } catch (JsonProcessingException e) {
                log.warn("骑手缓存 JSON 解析失败，cacheKey={}", cacheKey, e);
                deleteRiderCache(cacheKey);
            }
        }
        return null;
    }

    private Rider waitForRiderCache(String cacheKey) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            Rider cachedRider = readRiderFromCache(cacheKey);
            if (cachedRider != null) {
                return cachedRider;
            }
        }
        return null;
    }

    private Rider getByIdFromDatabase(Long id) {
        Rider rider = riderMapper.selectById(id);
        if (rider == null) {
            throw new IllegalArgumentException("骑手不存在");
        }
        return rider;
    }

    private void deleteRiderCache (String cacheKey){
        try {
            stringRedisTemplate.delete(cacheKey);
        } catch (DataAccessException e) {
            log.warn("Redis 删除骑手缓存失败，cacheKey={}", cacheKey, e);
        }
    }

    /**
     * 骑手详情变化后统一失效缓存。有事务时等 MySQL 提交成功后再删除，
     * 避免其他请求在提交前读到旧数据并重新写回缓存。
     */
    public void evictDetailCache(Long riderId) {
        String cacheKey = RIDER_DETAIL_KEY_PREFIX + riderId;
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            deleteRiderCache(cacheKey);
                        }
                    });
            return;
        }
        deleteRiderCache(cacheKey);
    }

    public List<Rider>list(){
        return riderMapper.selectList(null);
    }

    /**
     * 更新骑手资料。只更新允许修改的列，避免把查询到的旧接单数/状态整行写回，覆盖并发派单结果。
     */
    public Rider update(Long id,Rider request){
        Rider rider = getByIdFromDatabase(id);
        if (!StringUtils.hasText(request.getName())) {
            throw new IllegalArgumentException("骑手名字不能为空");
        }
        if (!StringUtils.hasText(request.getPhone())) {
            throw new IllegalArgumentException("骑手手机号不能为空");
        }
        Long num = riderMapper.selectCount(Wrappers.<Rider>lambdaQuery()
                .eq(Rider::getPhone,request.getPhone())
                .ne(Rider::getId,id));
        if(num>0){
            throw new IllegalArgumentException("手机号已存在");
        }
        var update = Wrappers.<Rider>lambdaUpdate()
                .eq(Rider::getId, id)
                .set(Rider::getName, request.getName())
                .set(Rider::getPhone, request.getPhone());
        if (request.getLatitude() != null) {
            update.set(Rider::getLatitude, request.getLatitude());
        }
        if (request.getLongitude() != null) {
            update.set(Rider::getLongitude, request.getLongitude());
        }
        riderMapper.update(null, update);
        Rider updatedRider = getByIdFromDatabase(id);
        syncRiderLocationToGeo(updatedRider);
        evictDetailCache(id);
        return updatedRider;
    }

    //更新骑手状态
    public Rider disable(Long id) {
        Rider rider = getByIdFromDatabase(id);
        if (rider.getCurrentOrderCount() != null && rider.getCurrentOrderCount() > 0) {
            throw new IllegalArgumentException("骑手还有进行中订单，不能停用");
        }
        int disabled = riderMapper.disableIfIdle(
                id, RiderStatus.AVAILABLE.getCode(), RiderStatus.DISABLED.getCode());
        if (disabled == 0) {
            throw new IllegalArgumentException("骑手状态或接单数已变化，请刷新后重试");
        }
        rider.setStatus(RiderStatus.DISABLED.getCode());
        removeRiderFromGeo(id);
        evictDetailCache(id);
        return rider;
    }

    /**
     * 位置更新写入 MySQL，随后同步 Redis GEO 索引。Redis 故障不阻塞位置事实落库，
     * 但会暂时影响附近骑手查询，之后可由位置上报或补偿任务恢复索引。
     */
    @Transactional
    public Rider updateLocation(Long id, RiderLocationRequest request) {
        validateCoordinates(request.longitude(), request.latitude());
        getByIdFromDatabase(id);
        riderMapper.update(null, Wrappers.<Rider>lambdaUpdate()
                .eq(Rider::getId, id)
                .set(Rider::getLongitude, request.longitude())
                .set(Rider::getLatitude, request.latitude()));
        Rider updatedRider = getByIdFromDatabase(id);
        syncRiderLocationToGeo(updatedRider);
        evictDetailCache(id);
        return updatedRider;
    }

    /**
     * Redis GEO 只负责按距离找候选人；数据库再确认骑手状态和当前负载。
     */
    public List<NearbyRiderResponse> findNearby(
            BigDecimal longitude, BigDecimal latitude, double radiusKm, int limit) {
        validateCoordinates(longitude, latitude);
        if (radiusKm <= 0 || radiusKm > 50) {
            throw new IllegalArgumentException("查询半径必须在 0 到 50 公里之间");
        }
        if (limit <= 0 || limit > 50) {
            throw new IllegalArgumentException("候选骑手数量必须在 1 到 50 之间");
        }

        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults;
        try {
            geoResults = stringRedisTemplate.opsForGeo().search(
                    AVAILABLE_RIDER_GEO_KEY,
                    GeoReference.fromCoordinate(longitude.doubleValue(), latitude.doubleValue()),
                    new Distance(radiusKm, Metrics.KILOMETERS),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                            .includeDistance()
                            .sortAscending()
                            .limit(limit));
        } catch (DataAccessException e) {
            log.warn("Redis GEO 查询附近骑手失败", e);
            throw new IllegalStateException("附近骑手服务暂不可用，请稍后重试");
        }

        if (geoResults == null || geoResults.getContent().isEmpty()) {
            return List.of();
        }

        List<Long> riderIds = geoResults.getContent().stream()
                .map(result -> riderIdFromGeoMember(result.getContent().getName()))
                .toList();
        Map<Long, Rider> ridersById = riderMapper.selectBatchIds(riderIds).stream()
                .collect(Collectors.toMap(Rider::getId, Function.identity()));

        return geoResults.getContent().stream()
                .map(result -> toNearbyRider(result, ridersById))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private NearbyRiderResponse toNearbyRider(
            GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult, Map<Long, Rider> ridersById) {
        Long riderId = riderIdFromGeoMember(geoResult.getContent().getName());
        Rider rider = ridersById.get(riderId);
        if (rider == null || !Integer.valueOf(RiderStatus.AVAILABLE.getCode()).equals(rider.getStatus())) {
            return null;
        }
        double distanceKm = geoResult.getDistance().in(Metrics.KILOMETERS).getValue();
        return new NearbyRiderResponse(
                rider.getId(), rider.getRiderNo(), rider.getName(),
                BigDecimal.valueOf(distanceKm).setScale(3, java.math.RoundingMode.HALF_UP),
                rider.getCurrentOrderCount());
    }

    private Long riderIdFromGeoMember(String member) {
        if (!member.startsWith(GEO_RIDER_MEMBER_PREFIX)) {
            throw new IllegalStateException("骑手 GEO 索引数据格式错误: " + member);
        }
        return Long.valueOf(member.substring(GEO_RIDER_MEMBER_PREFIX.length()));
    }

    private void syncRiderLocationToGeo(Rider rider) {
        if (rider.getStatus() == null || !Integer.valueOf(RiderStatus.AVAILABLE.getCode()).equals(rider.getStatus())
                || rider.getLongitude() == null || rider.getLatitude() == null) {
            removeRiderFromGeo(rider.getId());
            return;
        }
        try {
            stringRedisTemplate.opsForGeo().add(
                    AVAILABLE_RIDER_GEO_KEY,
                    new Point(rider.getLongitude().doubleValue(), rider.getLatitude().doubleValue()),
                    GEO_RIDER_MEMBER_PREFIX + rider.getId());
        } catch (DataAccessException e) {
            log.warn("Redis GEO 同步骑手位置失败，riderId={}", rider.getId(), e);
        }
    }

    private void removeRiderFromGeo(Long riderId) {
        try {
            stringRedisTemplate.opsForGeo().remove(
                    AVAILABLE_RIDER_GEO_KEY, GEO_RIDER_MEMBER_PREFIX + riderId);
        } catch (DataAccessException e) {
            log.warn("Redis GEO 移除骑手位置失败，riderId={}", riderId, e);
        }
    }

    private void validateCoordinates(BigDecimal longitude, BigDecimal latitude) {
        if (longitude == null || latitude == null) {
            throw new IllegalArgumentException("经纬度不能为空");
        }
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("经度必须在 -180 到 180 之间");
        }
        if (latitude.compareTo(BigDecimal.valueOf(-85.05112878)) < 0
                || latitude.compareTo(BigDecimal.valueOf(85.05112878)) > 0) {
            throw new IllegalArgumentException("纬度超出 Redis GEO 支持范围");
        }
    }
}
