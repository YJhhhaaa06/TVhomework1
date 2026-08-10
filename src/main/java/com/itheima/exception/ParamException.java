package com.itheima.exception;

public class ParamException extends BusinessException{
    public ParamException(String message) {
        super(ErrorCode.PARAM_ERROR, message);
    }

    public ParamException(String message, Throwable cause) {
        super(ErrorCode.PARAM_ERROR, message, cause);
    }
}
