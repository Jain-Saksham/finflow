package com.finflow.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Operator authentication for /api/ops/**, completely separate from merchant API keys: no merchant
 * (and in particular not the shared demo merchant) can reach operational functions. When no token
 * is configured, nothing can authenticate as an operator and the ops API is effectively disabled.
 */
public class OpsTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Ops-Token";

    private final byte[] token;

    public OpsTokenFilter(String token) {
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/ops/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (token != null && presented != null
                && MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            var auth = new PreAuthenticatedAuthenticationToken("ops", null, List.of(new SimpleGrantedAuthority("ROLE_OPS")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
