package com.finflow.security;

import com.finflow.common.error.ErrorCode;
import com.finflow.common.web.RequestIdFilter;
import com.finflow.merchant.MerchantRepository;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, MerchantRepository merchants,
                                            AuthenticationEntryPoint entryPoint) throws Exception {
        http
                // Stateless API authenticated by a header, not cookies, so there is no ambient
                // credential for a cross-site request to ride on: CSRF protection is unnecessary.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new ApiKeyAuthenticationFilter(merchants), AbstractPreAuthenticatedProcessingFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/demo/**", "/api/v1/simulator/scenarios").permitAll()
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
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
            body.put("detail", "A valid X-API-Key header is required.");
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
        config.setExposedHeaders(List.of(RequestIdFilter.HEADER, "Idempotent-Replayed"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
