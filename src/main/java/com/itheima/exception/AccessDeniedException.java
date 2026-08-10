package com.itheima.exception;

public class AccessDeniedException extends ForbiddenException {
    public AccessDeniedException() {
        super(ErrorCode.ACCESS_DENIED.getMessage());
    }

    public AccessDeniedException(String message) {
        super(message);
    }

    public AccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
