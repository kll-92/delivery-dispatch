package com.kll.deliverydispatch.rider.location;

import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.enums.RiderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiderLocationPolicyTest {
    private static final LocalDateTime DB_NOW = LocalDateTime.parse("2026-10-07T10:00:00");

    @Test
    void shouldUseDefaultAndConfiguredTtl() {
        RiderLocationProperties defaults = new RiderLocationProperties();
        assertEquals(300, new RiderLocationPolicy(defaults).ttlSeconds());

        RiderLocationProperties custom = new RiderLocationProperties();
        custom.setTtlSeconds(30);
        assertEquals(30, new RiderLocationPolicy(custom).ttlSeconds());
    }

    @Test
    void shouldApplyStrictFreshnessBoundaryAndRejectFutureOrMissingTime() {
        RiderLocationProperties properties = new RiderLocationProperties();
        properties.setTtlSeconds(30);
        RiderLocationPolicy policy = new RiderLocationPolicy(properties);

        assertFalse(policy.isFresh(riderAt(DB_NOW.minusSeconds(30)), DB_NOW));
        assertTrue(policy.isFresh(riderAt(DB_NOW.minusSeconds(29)), DB_NOW));
        assertFalse(policy.isFresh(riderAt(DB_NOW.plusSeconds(1)), DB_NOW));
        assertFalse(policy.isFresh(riderAt(null), DB_NOW));
        assertFalse(policy.isFresh(riderAt(DB_NOW), null));
    }

    @Test
    void shouldEnforceCoordinatesStatusAndCapacityEligibility() {
        RiderLocationPolicy policy = new RiderLocationPolicy(new RiderLocationProperties());

        assertTrue(policy.isEligible(riderAt(DB_NOW.minusSeconds(1)), DB_NOW));
        assertTrue(policy.isEligible(riderWithCount(2), DB_NOW));

        Rider noLongitude = riderAt(DB_NOW.minusSeconds(1));
        noLongitude.setLongitude(null);
        assertFalse(policy.isEligible(noLongitude, DB_NOW));

        Rider outOfRange = riderAt(DB_NOW.minusSeconds(1));
        outOfRange.setLatitude(new BigDecimal("86.0000000"));
        assertFalse(policy.isEligible(outOfRange, DB_NOW));

        Rider tooPrecise = riderAt(DB_NOW.minusSeconds(1));
        tooPrecise.setLongitude(new BigDecimal("116.12345678"));
        assertFalse(policy.isEligible(tooPrecise, DB_NOW));

        Rider disabled = riderAt(DB_NOW.minusSeconds(1));
        disabled.setStatus(RiderStatus.DISABLED.getCode());
        assertFalse(policy.isEligible(disabled, DB_NOW));

        assertFalse(policy.isEligible(riderWithCount(-1), DB_NOW));
        assertFalse(policy.isEligible(riderWithCount(3), DB_NOW));
        assertFalse(policy.isEligible(null, DB_NOW));
    }

    private static Rider riderAt(LocalDateTime lastLocationAt) {
        Rider rider = new Rider();
        rider.setStatus(RiderStatus.AVAILABLE.getCode());
        rider.setCurrentOrderCount(0);
        rider.setLongitude(new BigDecimal("116.4000000"));
        rider.setLatitude(new BigDecimal("39.9000000"));
        rider.setLastLocationAt(lastLocationAt);
        return rider;
    }

    private static Rider riderWithCount(int count) {
        Rider rider = riderAt(DB_NOW.minusSeconds(1));
        rider.setCurrentOrderCount(count);
        return rider;
    }
}
