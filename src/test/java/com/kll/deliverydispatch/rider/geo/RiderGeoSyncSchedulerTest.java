package com.kll.deliverydispatch.rider.geo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RiderGeoSyncSchedulerTest {

    @Test
    void springCanCreateSchedulerBeanWithProductionConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(SchedulerTestConfiguration.class)
                .run(context -> {
                    assertTrue(context.isRunning());
                    assertNotNull(context.getBean(RiderGeoSyncScheduler.class));
                });
    }

    @Test
    void runsSynchronizationOnBackgroundExecutor() throws InterruptedException {
        RiderGeoIndexService geoIndexService = mock(RiderGeoIndexService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RiderGeoSyncScheduler scheduler = new RiderGeoSyncScheduler(geoIndexService, executor);
        CountDownLatch synchronizedRider = new CountDownLatch(1);
        doAnswer(invocation -> {
            synchronizedRider.countDown();
            return RiderGeoIndexService.SyncResult.ADDED;
        }).when(geoIndexService).synchronize(42L);

        try {
            scheduler.schedule(42L);

            assertTrue(synchronizedRider.await(1, TimeUnit.SECONDS));
            verify(geoIndexService).synchronize(42L);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void ignoresRejectedTaskAfterShutdown() {
        RiderGeoIndexService geoIndexService = mock(RiderGeoIndexService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RiderGeoSyncScheduler scheduler = new RiderGeoSyncScheduler(geoIndexService, executor);
        scheduler.shutdown();

        assertDoesNotThrow(() -> scheduler.schedule(42L));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RiderGeoSyncScheduler.class)
    static class SchedulerTestConfiguration {
        @Bean
        RiderGeoIndexService riderGeoIndexService() {
            return mock(RiderGeoIndexService.class);
        }
    }
}
