package com.finflow.ratelimit;

import com.finflow.common.error.ErrorCode;
import com.finflow.common.web.RequestIdFilter;
import com.finflow.security.MerchantPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applied after authentication: per-merchant limits for API calls (stricter for writes) and
 * per-client-IP limits for unauthenticated public endpoints such as the demo session.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final RateLimitProperties properties;
    private final JsonMapper json;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties, JsonMapper json) {
        this.limiter = limiter;
        this.properties = properties;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith("/api/")
                || request.getRequestURI().startsWith("/api/ops/") || "OPTIONS".equals(request.getMethod())
                // FinFlow's own webhook worker calls the simulated receiver from localhost; those
                // requests are signature-checked before any work, so they must not eat the public budget.
                || request.getRequestURI().startsWith("/api/v1/simulator/webhook-receiver/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        RateLimiter.Decision decision;
        if (auth != null && auth.getPrincipal() instanceof MerchantPrincipal merchant) {
            String subject = merchant.merchantId().toString();
            decision = limiter.check("merchant", subject, properties.requestsPerMinute(), MINUTE);
            if (decision.allowed() && !"GET".equals(request.getMethod())) {
                decision = limiter.check("merchant-writes", subject, properties.writesPerMinute(), MINUTE);
            }
        } else {
            // getRemoteAddr honours X-Forwarded-For only when server.forward-headers-strategy is set,
            // i.e. only behind a trusted proxy; otherwise clients could spoof their IP.
            decision = limiter.check("public", request.getRemoteAddr(), properties.publicPerMinute(), MINUTE);
        }
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "https://finflow.dev/errors/rate-limited");
            body.put("title", "Too Many Requests");
            body.put("status", 429);
            body.put("detail", "Rate limit exceeded. Retry after " + decision.retryAfterSeconds() + " seconds.");
            body.put("instance", request.getRequestURI());
            body.put("code", ErrorCode.RATE_LIMITED.name());
            body.put("requestId", MDC.get(RequestIdFilter.MDC_KEY));
            json.writeValue(response.getOutputStream(), body);
            return;
        }
        chain.doFilter(request, response);
    }
}
