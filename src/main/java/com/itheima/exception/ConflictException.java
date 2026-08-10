package com.itheima.exception;

public class ConflictException extends BusinessException{
    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    public ConflictException(String message, Throwable cause) {
        super(ErrorCode.CONFLICT, message, cause);
    }
}
