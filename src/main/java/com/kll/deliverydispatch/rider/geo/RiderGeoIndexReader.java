package com.kll.deliverydispatch.rider.geo;

import com.kll.deliverydispatch.rider.entity.Rider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Reads the source of truth in a new READ_COMMITTED transaction. Keeping this
 * in a separate bean ensures calls from after-commit callbacks do not reuse a
 * transaction-bound connection or an old repeatable-read snapshot.
 */
@Service
@RequiredArgsConstructor
public class RiderGeoIndexReader {
    private final RiderGeoIndexMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED, readOnly = true)
    public RiderGeoIndexSnapshot readLatest(Long riderId) {
        Rider rider = mapper.selectRiderById(riderId);
        LocalDateTime databaseNow = mapper.selectUtcNow();
        return new RiderGeoIndexSnapshot(rider, databaseNow);
    }
}
