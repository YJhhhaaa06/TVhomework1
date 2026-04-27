package com.itheima.exception;

public class ParamException extends BusinessException{
    public ParamException(String message) {
        super(ErrorCode.PARAM_ERROR, message);
    }
}
