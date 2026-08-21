package com.itheima.exception;

public class PasswordIncorrectException extends AuthException {
    public PasswordIncorrectException() {
        super(ErrorCode.WRONG_PASSWORD.getMessage());
    }

    public PasswordIncorrectException(String message) {
        super(message);
    }

    public PasswordIncorrectException(String message, Throwable cause) {
        super(message, cause);
    }
}
