package com.itheima.exception;

public class DatabaseException extends ServerException {
    public DatabaseException() {
        super(ErrorCode.DATABASE_ERROR.getMessage());
    }

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
