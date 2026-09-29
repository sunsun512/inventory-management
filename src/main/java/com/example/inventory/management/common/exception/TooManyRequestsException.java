package com.example.inventory.management.common.exception;

/** The client sent more POST requests than its rate limit allows. Nothing was processed. */
public class TooManyRequestsException extends InventoryException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super(ErrorCode.TOO_MANY_REQUESTS,
                "요청이 너무 많습니다. " + retryAfterSeconds + "초 후 다시 시도하세요.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
