package com.cicd.platform.exception;

import org.springframework.http.HttpStatus;

public class GithubApiException extends RuntimeException {

    private final HttpStatus status;

    public GithubApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}