package com.itheima.exception;

public class AuthException extends BusinessException{

    public AuthException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}
