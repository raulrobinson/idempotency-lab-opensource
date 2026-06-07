package com.example.idempotency.application;

import java.util.Map;

/**
 * This record represents the result of an idempotent operation, encapsulating both the HTTP status code and the response body.
 * It provides static factory methods for creating success and error results, allowing for clear and consistent handling of idempotent responses.
 */
public record IdempotencyResult(
        int statusCode,             // HTTP status code to be returned to the client,
                                    // indicating the outcome of the operation (e.g., 200 for success, 400 for client error, etc.)
        Map<String, Object> body    // A map representing the response body to be sent back to the client,
                                    // which can contain any relevant data or error messages based on the result of the idempotent operation.
) {

    /** Static factory method to create a success result with the given status code and response body.
     * @param statusCode the HTTP status code to be returned for a successful operation (e.g., 200)
     * @param body the response body containing relevant data for a successful operation
     * @return an instance of IdempotencyResult representing a successful outcome
     */
    public static IdempotencyResult success(int statusCode,
                                            Map<String, Object> body) {
        return new IdempotencyResult(statusCode, body);
    }

    /** Static factory method to create an error result with the given status code and response body.
     * @param statusCode the HTTP status code to be returned for an error outcome (e.g., 400, 500)
     * @param body the response body containing error details or messages for a failed operation
     * @return an instance of IdempotencyResult representing an error outcome
     */
    public static IdempotencyResult error(int statusCode,
                                          Map<String, Object> body) {
        return new IdempotencyResult(statusCode, body);
    }
}