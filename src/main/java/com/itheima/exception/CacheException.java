package com.itheima.exception;

public class CacheException extends ServerException {
    public CacheException() {
        super(ErrorCode.CACHE_ERROR.getMessage());
    }

    public CacheException(String message) {
        super(message);
    }

    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
