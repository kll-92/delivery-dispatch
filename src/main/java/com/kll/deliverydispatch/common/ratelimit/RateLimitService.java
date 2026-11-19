package com.kll.deliverydispatch.common.ratelimit;

import com.kll.deliverydispatch.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

/** 匹配规则、解析身份、拼 key，然后交给 {@link TokenBucketLimiter}。 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final RateLimitProperties properties;
    private final TokenBucketLimiter tokenBucketLimiter;

    public RateLimitResult check(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return RateLimitResult.allowed("", RateLimitLevel.P1);
        }

        String uri = request.getRequestURI();
        for (String skipPath : properties.getSkipPaths()) {
            if (PATH_MATCHER.match(skipPath, uri)) {
                return RateLimitResult.allowed("", RateLimitLevel.P1);
            }
        }

        Match match = findRule(request.getMethod(), uri);
        String identifier = resolveIdentifier(match.scope(), request);
        if (!StringUtils.hasText(identifier)) {
            // 未登录且拿不到 IP：放行，但记录，便于发现异常来源
            log.debug("无法解析限流身份，放行 uri={}", uri);
            return RateLimitResult.allowed(match.pathTemplate(), match.level());
        }

        // key 里用规则的 path 模板，而不是真实 URI：
        // 否则 /orders/1/accept 和 /orders/2/accept 会是两个桶，限流形同虚设
        String key = "rate:" + match.scope().name().toLowerCase() + ":" + identifier
                + ":" + request.getMethod().toUpperCase() + ":" + match.pathTemplate();

        return tokenBucketLimiter.tryAcquire(key, match.capacity(), match.refillPerSecond(),
                match.pathTemplate(), match.level());
    }

    private Match findRule(String method, String uri) {
        for (RateLimitRule rule : properties.getRules()) {
            boolean methodMatches = "*".equals(rule.method())
                    || method.equalsIgnoreCase(rule.method());
            if (methodMatches && PATH_MATCHER.match(rule.path(), uri)) {
                return new Match(rule.path(), rule.scope(), rule.level(),
                        rule.capacity(), rule.refillPerSecond());
            }
        }
        RateLimitProperties.DefaultRule fallback = properties.getDefaultRule();
        return new Match("*", RateLimitScope.USER, fallback.getLevel(),
                fallback.getCapacity(), fallback.getRefillPerSecond());
    }

    private String resolveIdentifier(RateLimitScope scope, HttpServletRequest request) {
        if (scope == RateLimitScope.USER) {
            String accountId = currentAccountId();
            // 未登录时退回 IP，避免匿名请求绕过用户维度限流
            return accountId != null ? accountId : clientIp(request);
        }
        return clientIp(request);
    }

    private String currentAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AccountPrincipal accountPrincipal) {
            return String.valueOf(accountPrincipal.accountId());
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            // 取第一个，即真实客户端；后面的是各级代理
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private record Match(String pathTemplate, RateLimitScope scope, RateLimitLevel level,
                         double capacity, double refillPerSecond) {
    }
}
