package com.example.idempotency.infrastructure.mongo;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "idempotency_records")
public class IdempotencyRecord {

    @Id
    private String idempotencyKey;

    private String requestHash;
    private String status;

    private Integer responseStatus;
    private Map<String, Object> responseBody;

    private String errorMessage;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;
}