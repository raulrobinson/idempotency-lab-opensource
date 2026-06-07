package com.example.idempotency.application;

import java.util.Map;

public record IdempotencyResult(
        int statusCode,
        Map<String, Object> body
) {

    public static IdempotencyResult success(int statusCode, Map<String, Object> body) {
        return new IdempotencyResult(statusCode, body);
    }

    public static IdempotencyResult error(int statusCode, Map<String, Object> body) {
        return new IdempotencyResult(statusCode, body);
    }
}