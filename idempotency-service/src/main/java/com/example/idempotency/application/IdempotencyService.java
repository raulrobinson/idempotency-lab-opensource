package com.example.idempotency.application;

import com.example.idempotency.infrastructure.mongo.IdempotencyRecord;
import com.example.idempotency.infrastructure.mongo.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    @Value("${domain.lambda-url}")
    private String lambdaUrl;

    @Value("${idempotency.ttl-hours}")
    private Long ttlHours;

    public Mono<IdempotencyResult> handle(String idempotencyKey, Map<String, Object> payload) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Mono.just(IdempotencyResult.error(
                    HttpStatus.BAD_REQUEST.value(),
                    Map.of("message", "Idempotency-Key header is required")
            ));
        }

        String requestHash = hash(payload);

        return repository.findById(idempotencyKey)
                .flatMap(existing -> handleExisting(existing, requestHash))
                .switchIfEmpty(createAndExecute(idempotencyKey, requestHash, payload));
    }

    private Mono<IdempotencyResult> handleExisting(IdempotencyRecord existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            return Mono.just(IdempotencyResult.error(
                    422,
                    Map.of("message", "Idempotency-Key reused with different payload")
            ));
        }

        if ("COMPLETED".equals(existing.getStatus())) {
            return Mono.just(IdempotencyResult.success(
                    existing.getResponseStatus(),
                    existing.getResponseBody()
            ));
        }

        if ("PROCESSING".equals(existing.getStatus())) {
            return Mono.just(IdempotencyResult.error(
                    409,
                    Map.of("message", "Request is already processing")
            ));
        }

        if ("FAILED".equals(existing.getStatus())) {
            return Mono.just(IdempotencyResult.error(
                    500,
                    Map.of("message", "Previous request failed", "error", existing.getErrorMessage())
            ));
        }

        return Mono.just(IdempotencyResult.error(
                409,
                Map.of("message", "Invalid idempotency state")
        ));
    }

    private Mono<IdempotencyResult> createAndExecute(
            String key,
            String requestHash,
            Map<String, Object> payload
    ) {
        Instant now = Instant.now();

        IdempotencyRecord processing = IdempotencyRecord.builder()
                .idempotencyKey(key)
                .requestHash(requestHash)
                .status("PROCESSING")
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .build();

        return repository.save(processing)
                .then(invokeDomain(payload))
                .flatMap(domainResponse -> completeRecord(key, domainResponse))
                .onErrorResume(DuplicateKeyException.class, error ->
                        Mono.just(IdempotencyResult.error(
                                409,
                                Map.of("message", "Request is already processing")
                        ))
                )
                .onErrorResume(error -> failRecord(key, error));
    }

    private Mono<IdempotencyResult> invokeDomain(Map<String, Object> payload) {
        return webClientBuilder.build()
                .post()
                .uri(lambdaUrl)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> IdempotencyResult.success(200, body));
    }

    private Mono<IdempotencyResult> completeRecord(String key, IdempotencyResult response) {
        return repository.findById(key)
                .flatMap(record -> {
                    record.setStatus("COMPLETED");
                    record.setResponseStatus(response.statusCode());
                    record.setResponseBody(response.body());
                    record.setUpdatedAt(Instant.now());
                    return repository.save(record);
                })
                .thenReturn(response);
    }

    private Mono<IdempotencyResult> failRecord(String key, Throwable error) {
        return repository.findById(key)
                .flatMap(record -> {
                    record.setStatus("FAILED");
                    record.setErrorMessage(error.getMessage());
                    record.setUpdatedAt(Instant.now());
                    return repository.save(record);
                })
                .thenReturn(IdempotencyResult.error(
                        500,
                        Map.of("message", "Domain execution failed")
                ));
    }

    private String hash(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedHash);
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash request", e);
        }
    }
}