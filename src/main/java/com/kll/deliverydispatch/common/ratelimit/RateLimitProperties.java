package com.kll.deliverydispatch.common.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 限流配置。阈值随环境不同，压测时必须能整体关闭。 */
@Data
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    /** 未命中任何规则时使用。 */
    private DefaultRule defaultRule = new DefaultRule();

    private List<RateLimitRule> rules = new ArrayList<>();

    /** 不参与限流的路径（监控、文档、健康检查）。 */
    private List<String> skipPaths = new ArrayList<>(List.of(
            "/actuator/**", "/swagger-ui/**", "/v3/api-docs/**", "/api/v1/ping"));

    @Data
    public static class DefaultRule {
        private double capacity = 60;
        private double refillPerSecond = 10;
        private RateLimitLevel level = RateLimitLevel.P1;
    }
}
