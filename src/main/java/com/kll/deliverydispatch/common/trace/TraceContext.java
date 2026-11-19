package com.kll.deliverydispatch.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * traceId 的读写入口。
 * <p>
 * MDC 是「跟着线程走的 Map」，日志格式里用 {@code %X{traceId}} 取值。
 * 换线程（MQ 消费者、定时任务）后 MDC 会丢，需要手动 {@link #put}。
 */
public final class TraceContext {

    public static final String KEY = "traceId";

    private TraceContext() {
    }

    public static String current() {
        return MDC.get(KEY);
    }

    public static void put(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(KEY, traceId);
        }
    }

    public static void clear() {
        MDC.remove(KEY);
    }

    /** 16 位短 id，足够定位一次请求，又不至于把日志撑长。 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
