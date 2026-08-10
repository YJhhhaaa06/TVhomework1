package com.itheima.exception;

public class DuplicateLikeException extends ConflictException {
    public DuplicateLikeException() {
        super(ErrorCode.DUPLICATE_LIKE.getMessage());
    }

    public DuplicateLikeException(String message) {
        super(message);
    }

    public DuplicateLikeException(String message, Throwable cause) {
        super(message, cause);
    }
}
