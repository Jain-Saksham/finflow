package com.finflow.demo;

import com.finflow.merchant.ApiKeys;
import com.finflow.merchant.Merchant;
import com.finflow.merchant.MerchantRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single, clearly separated demo merchant. Its API key is (re)issued at startup and kept
 * only in memory, so no demo secret ever lives in the repository or the database in plaintext.
 */
@Component
public class DemoAccount {

    public static final String MERCHANT_NAME = "Northwind Coffee (Demo)";

    private final MerchantRepository merchants;
    private final DemoProperties properties;
    private volatile Merchant merchant;
    private volatile String apiKey;

    public DemoAccount(MerchantRepository merchants, DemoProperties properties) {
        this.merchants = merchants;
        this.properties = properties;
    }

    @Transactional
    public Merchant initialize() {
        String key = properties.apiKey() == null || properties.apiKey().isBlank()
                ? ApiKeys.generate()
                : properties.apiKey().strip();
        Merchant m = merchants.findByDemoTrue().orElseGet(() -> new Merchant(MERCHANT_NAME, "USD", true, key));
        m.rotateApiKey(key);
        this.merchant = merchants.save(m);
        this.apiKey = key;
        return merchant;
    }

    public record Session(Merchant merchant, String apiKey) {
    }

    public Optional<Session> session() {
        return properties.enabled() && merchant != null ? Optional.of(new Session(merchant, apiKey)) : Optional.empty();
    }
}
