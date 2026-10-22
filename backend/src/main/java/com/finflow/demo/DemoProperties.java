package com.finflow.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled exposes {@code POST /api/v1/demo/session}, which hands out the demo merchant's key
 * @param seed    populate the demo merchant with sample orders/payments on first start
 * @param apiKey  optional fixed demo key (needed when running several instances); random if blank
 */
@ConfigurationProperties("finflow.demo")
public record DemoProperties(boolean enabled, boolean seed, String apiKey) {
}
