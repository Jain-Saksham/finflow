package com.finflow.provider;

public abstract sealed class ProviderException extends RuntimeException
        permits ProviderTimeoutException, ProviderUnavailableException {

    protected ProviderException(String message) {
        super(message);
    }
}
