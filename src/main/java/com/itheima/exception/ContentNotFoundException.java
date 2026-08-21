package com.itheima.exception;

public class ContentNotFoundException extends NotFoundException {
    public ContentNotFoundException() {
        super(ErrorCode.CONTENT_NOT_FOUND.getMessage());
    }

    public ContentNotFoundException(String message) {
        super(message);
    }

    public ContentNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
