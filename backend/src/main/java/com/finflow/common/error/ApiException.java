package com.finflow.common.error;

/** A business-rule or request error that maps directly to an HTTP error response. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException notFound(String resource, Object id) {
        return new ApiException(ErrorCode.NOT_FOUND, resource + " " + id + " was not found");
    }
}
