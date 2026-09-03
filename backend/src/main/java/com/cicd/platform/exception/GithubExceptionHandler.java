package com.cicd.platform.exception;

import com.cicd.platform.dto.GithubApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestControllerAdvice
public class GithubExceptionHandler {

    @ExceptionHandler(GithubApiException.class)
    public ResponseEntity<GithubApiErrorResponse> handleGithubApiException(GithubApiException exception) {
        HttpStatus status = exception.getStatus();
        return ResponseEntity.status(status)
                .body(new GithubApiErrorResponse(
                        status.getReasonPhrase(),
                        exception.getMessage(),
                        status.value(),
                        Instant.now()
                ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<GithubApiErrorResponse> handleResponseStatusException(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return ResponseEntity.status(status)
                .body(new GithubApiErrorResponse(
                        status.getReasonPhrase(),
                        exception.getReason() != null ? exception.getReason() : status.getReasonPhrase(),
                        status.value(),
                        Instant.now()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<GithubApiErrorResponse> handleIllegalArgumentException(IllegalArgumentException exception) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new GithubApiErrorResponse(
                        status.getReasonPhrase(),
                        exception.getMessage(),
                        status.value(),
                        Instant.now()
                ));
    }
}