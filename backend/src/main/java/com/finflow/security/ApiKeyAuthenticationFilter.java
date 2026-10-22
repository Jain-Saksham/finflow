package com.finflow.security;

import com.finflow.merchant.ApiKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the {@code X-API-Key} header to a merchant. Requests without a valid key simply stay
 * anonymous; the security rules then reject them on protected routes with a 401.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiKeyResolver resolver;

    public ApiKeyAuthenticationFilter(ApiKeyResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            MerchantPrincipal principal = resolver.resolve(ApiKeys.hash(apiKey.strip()));
            if (principal != null) {
                var auth = new PreAuthenticatedAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
