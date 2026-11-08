package com.kll.deliverydispatch.rider.location;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "dispatch.rider-location")
public class RiderLocationProperties {
    @Min(1)
    private long ttlSeconds = 300;

    private boolean recoveryEnabled = true;

    @Min(1)
    private long recoveryDelayMs = 30_000;

    @Min(1)
    private int recoveryBatchSize = 100;
}
