package com.cicd.platform.dto;

import java.time.Instant;

public record GithubApiErrorResponse(
        String error,
        String message,
        int status,
        Instant timestamp
) {
}