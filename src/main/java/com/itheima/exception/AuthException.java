package com.itheima.exception;

public class AuthException extends BusinessException{

    public AuthException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }

    public AuthException(String message, Throwable cause) {
        super(ErrorCode.UNAUTHORIZED, message, cause);
    }
}
