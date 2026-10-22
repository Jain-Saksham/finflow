package com.finflow.security;

import com.finflow.common.error.ErrorCode;
import com.finflow.common.web.RequestIdFilter;
import com.finflow.ratelimit.RateLimitFilter;
import com.finflow.ratelimit.RateLimitProperties;
import com.finflow.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @ConfigurationProperties("finflow.cors")
    public record CorsProperties(List<String> allowedOrigins) {
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyResolver apiKeys, AuthenticationEntryPoint entryPoint,
                                            RateLimiter rateLimiter, RateLimitProperties rateLimits, JsonMapper jsonMapper,
                                            @Value("${finflow.ops.token:}") String opsToken) throws Exception {
        http
                // Stateless API authenticated by a header, not cookies, so there is no ambient
                // credential for a cross-site request to ride on: CSRF protection is unnecessary.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new OpsTokenFilter(opsToken), AbstractPreAuthenticatedProcessingFilter.class)
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeys), AbstractPreAuthenticatedProcessingFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter, rateLimits, jsonMapper),
                        AbstractPreAuthenticatedProcessingFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            jsonMapper.writeValue(response.getOutputStream(), Map.of("type",
                                    "https://finflow.dev/errors/forbidden", "title", "Forbidden", "status", 403,
                                    "detail", "This credential cannot access this resource.", "code",
                                    ErrorCode.FORBIDDEN.name()));
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/demo/**", "/api/v1/simulator/**").permitAll()
                        .requestMatchers("/api/ops/**").hasRole("OPS")
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers("/api/**").hasRole("MERCHANT")
                        .anyRequest().denyAll());
        return http.build();
    }

    /** 401s use the same problem-details shape as every other API error. */
    @Bean
    AuthenticationEntryPoint problemAuthenticationEntryPoint(JsonMapper jsonMapper) {
        return (request, response, ex) -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "https://finflow.dev/errors/unauthenticated");
            body.put("title", "Unauthorized");
            body.put("status", 401);
            body.put("detail", request.getRequestURI().startsWith("/api/ops/")
                    ? "A valid X-Ops-Token header is required." : "A valid X-API-Key header is required.");
            body.put("instance", request.getRequestURI());
            body.put("code", ErrorCode.UNAUTHENTICATED.name());
            body.put("requestId", MDC.get(RequestIdFilter.MDC_KEY));
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            jsonMapper.writeValue(response.getOutputStream(), body);
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", ApiKeyAuthenticationFilter.HEADER, "Idempotency-Key", RequestIdFilter.HEADER));
        config.setExposedHeaders(List.of(RequestIdFilter.HEADER, "Idempotent-Replayed", "Retry-After",
                "X-RateLimit-Limit", "X-RateLimit-Remaining"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
