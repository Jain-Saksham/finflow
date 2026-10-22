package com.finflow.security;

import com.finflow.cache.CacheProperties;
import com.finflow.cache.JsonCache;
import com.finflow.merchant.MerchantRepository;
import org.springframework.stereotype.Component;

/**
 * API key hash -> merchant, cached in Redis because it runs on every request. Invalidation: the
 * entry is evicted when a key is rotated, and expires after {@code finflow.cache.api-key-ttl}
 * regardless. Unknown keys are not cached (no negative caching), so a new key works immediately.
 */
@Component
public class ApiKeyResolver {

    static final String CACHE = "apikey";

    private final MerchantRepository merchants;
    private final JsonCache cache;
    private final CacheProperties properties;

    public ApiKeyResolver(MerchantRepository merchants, JsonCache cache, CacheProperties properties) {
        this.merchants = merchants;
        this.cache = cache;
        this.properties = properties;
    }

    public MerchantPrincipal resolve(String apiKeyHash) {
        return cache.getOrLoad(CACHE, apiKeyHash, MerchantPrincipal.class, properties.apiKeyTtl(),
                () -> merchants.findByApiKeyHash(apiKeyHash)
                        .map(m -> new MerchantPrincipal(m.getId(), m.getName(), m.isDemo()))
                        .orElse(null));
    }

    public void evict(String apiKeyHash) {
        cache.evict(CACHE, apiKeyHash);
    }
}
