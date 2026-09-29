package com.github.xingjianluo.firmwareanalysisorchestratorjava.web;

import com.github.xingjianluo.firmwareanalysisorchestratorjava.model.FailureReason;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.sample.MalformedSampleException;
import com.github.xingjianluo.firmwareanalysisorchestratorjava.service.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常处理：对外只返回稳定的错误码与可读信息，
 * 底层解析异常（含其 cause 链）一律不直接透出。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MalformedSampleException.class)
    public ResponseEntity<Map<String, String>> handleMalformed(MalformedSampleException e) {
        log.warn("请求被拒 reason={} 判定依据={}", e.getReason().getCode(), e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "code", e.getReason().getCode(),
                "message", e.getReason().getUserMessage()));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TaskNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code", FailureReason.TASK_NOT_FOUND.getCode(),
                "message", FailureReason.TASK_NOT_FOUND.getUserMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_REQUEST",
                "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "INVALID_STATE",
                "message", e.getMessage()));
    }
}
