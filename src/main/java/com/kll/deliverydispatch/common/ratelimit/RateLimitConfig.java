package com.kll.deliverydispatch.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册限流拦截器。关闭 rate-limit.enabled 后整条链路不生效（压测时必须能关）。 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(prefix = "rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(rateLimitService, objectMapper))
                .addPathPatterns("/api/**");
    }
}
