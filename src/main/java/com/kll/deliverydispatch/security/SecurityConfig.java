package com.kll.deliverydispatch.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JsonSecurityErrorWriter errorWriter;
    private final SessionAccountEnabledFilter sessionAccountEnabledFilter;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    DaoAuthenticationProvider accountAuthenticationProvider(UserDetailsService userDetailsService,
                                                              PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(java.util.List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    @Bean
    FilterRegistrationBean<SessionAccountEnabledFilter> disableContainerRegistration(
            SessionAccountEnabledFilter filter) {
        FilterRegistrationBean<SessionAccountEnabledFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityContextRepository securityContextRepository,
                                            DaoAuthenticationProvider accountAuthenticationProvider,
                                            CsrfTokenRepository csrfTokenRepository) throws Exception {
        http
                .authenticationProvider(accountAuthenticationProvider)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                errorWriter.write(response, HttpStatus.UNAUTHORIZED, "请先登录"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                errorWriter.write(response, HttpStatus.FORBIDDEN, "没有权限执行此操作")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/ping", "/api/v1/auth/csrf",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/auth/logout", "/api/v1/auth/me").authenticated()
                        .requestMatchers("/api/v1/accounts/**", "/api/v1/operation-logs/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/orders/*/fee").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/orders/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/**", "/api/v1/riders/**")
                            .hasAnyRole("ADMIN", "DISPATCHER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/dashboard/summary")
                            .hasAnyRole("ADMIN", "DISPATCHER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders")
                            .hasAnyRole("ADMIN", "DISPATCHER")
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/v1/orders/*/assign/*", "/api/v1/orders/*/dispatch",
                                "/api/v1/orders/*/start-delivery", "/api/v1/orders/*/complete",
                                "/api/v1/orders/*/cancel")
                            .hasAnyRole("ADMIN", "DISPATCHER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/riders").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/riders/*", "/api/v1/riders/*/location")
                            .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/riders/*/disable", "/api/v1/riders/*/enable")
                            .hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .addFilterBefore(sessionAccountEnabledFilter, AnonymousAuthenticationFilter.class)
                .logout(logout -> logout.disable());
        return http.build();
    }
}
