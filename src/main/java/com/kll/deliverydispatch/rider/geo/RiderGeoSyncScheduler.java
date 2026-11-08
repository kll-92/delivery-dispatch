package com.kll.deliverydispatch.rider.geo;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs post-commit GEO synchronization away from the request transaction
 * thread, so acquiring a fresh DB connection cannot hold up transaction cleanup.
 */
@Component
@Slf4j
public class RiderGeoSyncScheduler {
    private static final int QUEUE_CAPACITY = 100;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final RiderGeoIndexService geoIndexService;
    private final ExecutorService executor;

    @Autowired
    public RiderGeoSyncScheduler(RiderGeoIndexService geoIndexService) {
        this(geoIndexService, new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                daemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()));
    }

    RiderGeoSyncScheduler(RiderGeoIndexService geoIndexService, ExecutorService executor) {
        this.geoIndexService = geoIndexService;
        this.executor = executor;
    }

    /** Never propagates executor rejection or a GEO sync runtime failure to the caller. */
    public void schedule(Long riderId) {
        try {
            executor.execute(() -> {
                try {
                    geoIndexService.synchronize(riderId);
                } catch (RuntimeException e) {
                    log.warn("异步骑手 GEO 同步异常，riderId={}", riderId, e);
                }
            });
        } catch (RejectedExecutionException e) {
            // This round is intentionally dropped; bounded periodic reconciliation is the fallback.
            log.warn("骑手 GEO 同步队列已满或已关闭，本轮跳过并等待恢复扫描，riderId={}", riderId);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static ThreadFactory daemonThreadFactory() {
        return task -> {
            Thread thread = new Thread(task,
                    "rider-geo-sync-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
