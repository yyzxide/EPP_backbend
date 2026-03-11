package com.epp.backend.exception;

import com.epp.backend.common.CommonResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public CommonResult<Object> handleRuntimeException(RuntimeException e) {
        return CommonResult.failed(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public CommonResult<Object> handleException(Exception e) {
        return CommonResult.failed("服务器内部错误");
    }
}