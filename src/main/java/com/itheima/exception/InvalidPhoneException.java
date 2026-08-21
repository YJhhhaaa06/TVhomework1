package com.itheima.exception;

public class InvalidPhoneException extends ParamException {
    public InvalidPhoneException() {
        super(ErrorCode.INVALID_PHONE.getMessage());
    }

    public InvalidPhoneException(String message) {
        super(message);
    }

    public InvalidPhoneException(String message, Throwable cause) {
        super(message, cause);
    }
}
