package com.itheima.controller;

import com.itheima.exception.BusinessException;

import java.util.function.Supplier;

public class ControllerUtil {

//    public static <T> Result<T> execute(Supplier<T> action) {
//        try {
//            return Result.success(action.get());
//        } catch (BusinessException e) {
//            return Result.fail(e.getCode(), e.getMessage());
//        } catch (Exception e) {
//            return Result.fail(500, "服务器错误");
//        }
//    }
}
