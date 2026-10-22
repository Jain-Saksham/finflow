package com.finflow.provider;

/** The provider rejected the connection before processing anything; retrying is safe. */
public final class ProviderUnavailableException extends ProviderException {

    public ProviderUnavailableException(String message) {
        super(message);
    }
}
