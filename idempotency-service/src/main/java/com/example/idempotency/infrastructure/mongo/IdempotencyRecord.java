package com.example.idempotency.infrastructure.mongo;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a record of an idempotent request, stored in MongoDB. Each record is identified by a unique idempotency key,
 * and contains information about the request, its status, response, and any errors that occurred. The record also includes
 * timestamps for creation, updates, and expiration to manage the lifecycle of idempotent entries.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "idempotency_records")
public class IdempotencyRecord {

    @Id
    private String idempotencyKey;  // Unique identifier for the idempotent request, provided by the client in the "Idempotency-Key" header

    private String requestHash;     // A hash of the request payload, used to ensure that identical requests with the same idempotency key are recognized and handled correctly
    private String status;          // Current status of the idempotent request (e.g., "PENDING", "COMPLETED", "FAILED"), used to track the processing state of the request

    private Integer responseStatus;             // HTTP status code of the response generated for this idempotent request, stored to allow for consistent responses to repeated requests with the same idempotency key
    private Map<String, Object> responseBody;   // The response body generated for this idempotent request, stored as a map to allow for flexible and structured data to be returned to clients on subsequent requests with the same idempotency key

    private String errorMessage;    // If the request processing resulted in an error, this field captures the error message or details, allowing for debugging and consistent error responses to clients on repeated requests with the same idempotency key

    private Instant createdAt;      // Timestamp indicating when the idempotency record was created, used for tracking the age of the record and managing expiration
    private Instant updatedAt;      // Timestamp indicating the last time the idempotency record was updated, used for tracking changes and managing expiration
    private Instant expiresAt;      // Timestamp indicating when the idempotency record should expire and be eligible for cleanup, used to prevent indefinite storage of idempotent entries and manage resource usage
}