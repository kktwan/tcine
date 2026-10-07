package com.t.tcine.global.exception;

import com.t.tcine.global.response.ApiResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** REST API 컨트롤러의 예외를 ApiResponse.error 형식으로 변환 (Thymeleaf 화면은 제외) */
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handle(Exception e) {
        return ApiResponse.error(e.getMessage());
    }
}
