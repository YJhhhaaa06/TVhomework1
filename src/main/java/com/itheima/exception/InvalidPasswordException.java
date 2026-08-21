package com.itheima.exception;

public class InvalidPasswordException extends ParamException {
    public InvalidPasswordException() {
        super(ErrorCode.INVALID_PASSWORD.getMessage());
    }

    public InvalidPasswordException(String message) {
        super(message);
    }

    public InvalidPasswordException(String message, Throwable cause) {
        super(message, cause);
    }
}
