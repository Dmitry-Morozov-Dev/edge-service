package com.messenger.edge_service.error;

public class ServiceDegradedException extends RuntimeException {
    private final boolean isRedisBulkError;

    public ServiceDegradedException(String message, Throwable cause, boolean isRedisBulkError) {
        super(message, cause);
        this.isRedisBulkError = isRedisBulkError;
    }

    public boolean isRedisBulkError() {
        return isRedisBulkError;
    }
}