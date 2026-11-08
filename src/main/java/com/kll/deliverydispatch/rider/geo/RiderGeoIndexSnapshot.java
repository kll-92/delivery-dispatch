package com.kll.deliverydispatch.rider.geo;

import com.kll.deliverydispatch.rider.entity.Rider;

import java.time.LocalDateTime;

record RiderGeoIndexSnapshot(Rider rider, LocalDateTime databaseNow) {
}
