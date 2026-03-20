package com.brandonbot.legalassistant.ui.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.brandonbot.legalassistant.ui.dto.ErrorDto;

@RestControllerAdvice(basePackages = "com.brandonbot.legalassistant.ui")
public class UiApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorDto> handleStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(new ErrorDto(ex.getReason() == null ? "请求失败" : ex.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> handleAny(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorDto("系统异常"));
    }
}
