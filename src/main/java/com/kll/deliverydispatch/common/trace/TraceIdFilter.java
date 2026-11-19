package com.kll.deliverydispatch.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求入口：生成（或沿用上游传来的）traceId，放进 MDC，并回写到响应头。
 * <p>
 * 放在过滤器而不是拦截器，是为了让它最先执行，覆盖 Security 之前的日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 允许上游网关/前端透传，便于跨服务串联；没有就新生成一个。 */
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = TraceContext.newTraceId();
        }
        TraceContext.put(traceId);
        // 回写给客户端：用户反馈问题时可以把这个 id 报给你
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理：线程池会复用线程，不清会把 traceId 带到下一个请求
            TraceContext.clear();
        }
    }
}
