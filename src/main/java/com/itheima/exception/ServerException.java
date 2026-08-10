package com.itheima.exception;

public class ServerException extends BusinessException{
    public ServerException(String message){super(ErrorCode.SERVER_ERROR, message);}

    public ServerException(String message, Throwable cause){super(ErrorCode.SERVER_ERROR, message, cause);}
}
