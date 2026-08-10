package com.itheima.exception;

public class CommentNotFoundException extends NotFoundException {
    public CommentNotFoundException() {
        super(ErrorCode.COMMENT_NOT_FOUND.getMessage());
    }

    public CommentNotFoundException(String message) {
        super(message);
    }

    public CommentNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
