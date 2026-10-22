package com.finflow.provider;

/** No response in time. The provider may have processed the request, so the outcome is unknown. */
public final class ProviderTimeoutException extends ProviderException {

    public ProviderTimeoutException(String message) {
        super(message);
    }
}
