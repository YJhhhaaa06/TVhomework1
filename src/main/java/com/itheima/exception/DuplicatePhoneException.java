package com.itheima.exception;

public class DuplicatePhoneException extends ConflictException {
    public DuplicatePhoneException() {
        super(ErrorCode.DUPLICATE_PHONE.getMessage());
    }

    public DuplicatePhoneException(String message) {
        super(message);
    }

    public DuplicatePhoneException(String message, Throwable cause) {
        super(message, cause);
    }
}
