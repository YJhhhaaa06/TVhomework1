package com.itheima.exception;

public class TokenExpiredException extends AuthException {
    public TokenExpiredException() {
        super(ErrorCode.TOKEN_EXPIRED.getMessage());
    }

    public TokenExpiredException(String message) {
        super(message);
    }

    public TokenExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
