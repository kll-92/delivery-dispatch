package com.kll.deliverydispatch.security;

import com.kll.deliverydispatch.account.entity.Account;
import com.kll.deliverydispatch.account.mapper.AccountMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class SessionAccountEnabledFilter extends OncePerRequestFilter {
    private final AccountMapper accountMapper;
    private final JsonSecurityErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            Account current = accountMapper.selectById(principal.accountId());
            if (current == null || !Boolean.TRUE.equals(current.getEnabled())) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) {
                    request.getSession(false).invalidate();
                }
                errorWriter.write(response, HttpStatus.UNAUTHORIZED, "账号已停用，请重新登录");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
