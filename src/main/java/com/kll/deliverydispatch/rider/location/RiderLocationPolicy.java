package com.kll.deliverydispatch.rider.location;

import com.kll.deliverydispatch.rider.RiderCapacity;
import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.enums.RiderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class RiderLocationPolicy {
    private static final BigDecimal MIN_LONGITUDE = BigDecimal.valueOf(-180);
    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);
    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-85.05112878");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("85.05112878");
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final RiderLocationProperties properties;

    public long ttlSeconds() {
        return properties.getTtlSeconds();
    }

    public boolean hasValidCoordinates(Rider rider) {
        return rider != null && isValidLongitude(rider.getLongitude())
                && isValidLatitude(rider.getLatitude());
    }

    public boolean isFresh(Rider rider, LocalDateTime dbNow) {
        if (rider == null || rider.getLastLocationAt() == null || dbNow == null) {
            return false;
        }
        LocalDateTime cutoff = dbNow.minusSeconds(ttlSeconds());
        return rider.getLastLocationAt().isAfter(cutoff)
                && !rider.getLastLocationAt().isAfter(dbNow);
    }

    public boolean isEligible(Rider rider, LocalDateTime dbNow) {
        return rider != null
                && Integer.valueOf(RiderStatus.AVAILABLE.getCode()).equals(rider.getStatus())
                && rider.getCurrentOrderCount() != null
                && rider.getCurrentOrderCount() >= 0
                && rider.getCurrentOrderCount() < RiderCapacity.MAX_CONCURRENT_ORDERS
                && hasValidCoordinates(rider)
                && isFresh(rider, dbNow);
    }

    public double distanceKm(BigDecimal longitude1, BigDecimal latitude1,
                             BigDecimal longitude2, BigDecimal latitude2) {
        if (!isValidLongitude(longitude1) || !isValidLatitude(latitude1)
                || !isValidLongitude(longitude2) || !isValidLatitude(latitude2)) {
            throw new IllegalArgumentException("经纬度无效");
        }
        double lat1 = Math.toRadians(latitude1.doubleValue());
        double lat2 = Math.toRadians(latitude2.doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(longitude2.doubleValue() - longitude1.doubleValue());
        double haversine = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLon / 2), 2);
        haversine = Math.max(0, Math.min(1, haversine));
        double centralAngle = 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
        return EARTH_RADIUS_KM * centralAngle;
    }

    private static boolean isValidLongitude(BigDecimal longitude) {
        return longitude != null
                && longitude.compareTo(MIN_LONGITUDE) >= 0
                && longitude.compareTo(MAX_LONGITUDE) <= 0
                && Math.max(longitude.scale(), 0) <= 7;
    }

    private static boolean isValidLatitude(BigDecimal latitude) {
        return latitude != null
                && latitude.compareTo(MIN_LATITUDE) >= 0
                && latitude.compareTo(MAX_LATITUDE) <= 0
                && Math.max(latitude.scale(), 0) <= 7;
    }
}
