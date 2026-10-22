package com.finflow.security;

import java.util.UUID;

/** The authenticated caller. Every data access is scoped by {@link #merchantId()}. */
public record MerchantPrincipal(UUID merchantId, String name, boolean demo) {
}
