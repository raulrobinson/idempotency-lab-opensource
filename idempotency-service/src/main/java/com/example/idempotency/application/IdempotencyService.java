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

    /**
     * Main method to handle incoming requests with idempotency logic. It validates the presence of the Idempotency-Key header,
     * hashes the request payload, and checks for existing records in the database to determine how to respond.
     * @param idempotencyKey string representing the idempotency key, must be unique for each distinct request
     *                       and is used to track the status of previous requests
     * @param payload JSON body of the request, which will be hashed and stored in the database to ensure
     *                that the same idempotency key cannot be reused with a different payload without detection
     * @return a Mono emitting an IdempotencyResult containing the appropriate response based on the idempotency logic,
     *         which could be a successful response if the request is processed successfully, an error if the client
     *         is trying to reuse the same key with a different payload, or an indication to wait
     *         if another request with the same key is currently being processed
     */
    public Mono<IdempotencyResult> handle(String idempotencyKey,
                                          Map<String, Object> payload) {
        // Validate presence of Idempotency-Key header
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Mono.just(IdempotencyResult.error(
                    HttpStatus.BAD_REQUEST.value(),
                    Map.of("message", "Idempotency-Key header is required")
            ));
        }

        // Hash the request payload to detect changes in subsequent requests with the same key
        String requestHash = hash(payload);

        // Check if there's an existing record for this idempotency key
        return repository.findById(idempotencyKey)
                .flatMap(existing -> handleExisting(existing, requestHash))
                .switchIfEmpty(createAndExecute(idempotencyKey, requestHash, payload));
    }

    /**
     * If there's an existing record for the idempotency key,
     * we check the request hash and the status of the previous request to determine how to respond.
     * @param existing the existing idempotency record retrieved from the database,
     *                 which contains the request hash and status of the previous request
     * @param requestHash the hash of the current request payload, which is compared against
     *                    the stored request hash to detect if the client is trying to reuse the same
     *                    idempotency key with a different payload, which is not allowed
     * @return a Mono emitting an IdempotencyResult containing the appropriate response based on
     *         the existing record's status and request hash, which could be a successful response
     *         if the previous request was completed with the same payload, an error if the client
     *         is trying to reuse the key with a different payload, or an indication to wait if the previous request is still processing
     */
    private Mono<IdempotencyResult> handleExisting(IdempotencyRecord existing,
                                                   String requestHash) {
        // If the request hash doesn't match, it means the client is trying
        // to reuse the same idempotency key with a different payload, which is not allowed
        if (!existing.getRequestHash().equals(requestHash)) {
            return Mono.just(IdempotencyResult.error(
                    422,
                    Map.of("message", "Idempotency-Key reused with different payload")
            ));
        }

        // If the request is already completed, return the stored response
        if ("COMPLETED".equals(existing.getStatus())) {
            return Mono.just(IdempotencyResult.success(
                    existing.getResponseStatus(),
                    existing.getResponseBody()
            ));
        }

        // If the request is currently processing, inform the client to wait
        if ("PROCESSING".equals(existing.getStatus())) {
            return Mono.just(IdempotencyResult.error(
                    409,
                    Map.of("message", "Request is already processing")
            ));
        }

        // If the previous request with the same key failed,
        // return an error indicating that the client should not retry with the same key
        if ("FAILED".equals(existing.getStatus())) {
            return Mono.just(IdempotencyResult.error(
                    500,
                    Map.of("message", "Previous request failed", "error", existing.getErrorMessage())
            ));
        }

        // For any other status, return a generic error
        return Mono.just(IdempotencyResult.error(
                409,
                Map.of("message", "Invalid idempotency state")
        ));
    }

    /**
     * If there's no existing record for the idempotency key, we create a new one with status "PROCESSING" and invoke the domain logic.
     * We handle concurrent requests with the same key by catching DuplicateKeyException when trying to save the new record.
     * @param key the idempotency key for the new request, which must be unique in the database to prevent concurrent processing of the same key
     * @param requestHash the hash of the request payload, which is stored in the database to detect changes in subsequent requests with the same key
     * @param payload the request body to send to the domain, which will be forwarded as-is to the Lambda function for processing
     * @return a Mono emitting an IdempotencyResult containing the status code and response body from the domain execution,
     *         or an error if another request with the same key is being processed concurrently or if domain execution fails
     */
    private Mono<IdempotencyResult> createAndExecute(
            String key,
            String requestHash,
            Map<String, Object> payload
    ) {
        Instant now = Instant.now();

        // Create a new record with status "PROCESSING" to indicate that the request is being handled
        IdempotencyRecord processing = IdempotencyRecord.builder()
                .idempotencyKey(key)
                .requestHash(requestHash)
                .status("PROCESSING")
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .build();

        // Attempt to save the new record. If another request with the same key is being processed concurrently,
        // this will throw a DuplicateKeyException, which we catch to return a 409 response
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

    /**
     * Invokes the domain logic by making an HTTP POST request to the configured Lambda URL with the request payload.
     * @param payload the request body to send to the domain, which will be forwarded as-is to the Lambda function for processing
     * @return a Mono emitting an IdempotencyResult containing the status code and response body from the domain execution,
     *         which will be stored in the database for future reference and returned to the client
     */
    private Mono<IdempotencyResult> invokeDomain(Map<String, Object> payload) {
        return webClientBuilder.build()
                .post()
                .uri(lambdaUrl)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> IdempotencyResult.success(200, body));
    }

    /**
     * If the domain execution succeeds, we update the record status to "COMPLETED" and store the response data.
     * @param key the idempotency key of the record to update
     * @param response the successful response from the domain execution,
     *                 whose status code and body will be stored in the record for future reference
     * @return a Mono emitting the same IdempotencyResult that was passed in, after updating the record in the database
     */
    private Mono<IdempotencyResult> completeRecord(String key,
                                                   IdempotencyResult response) {
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

    /**
     * If the domain execution fails, we update the record status to "FAILED" and store the error message.
     * @param key the idempotency key of the record to update
     * @param error the error that occurred during domain execution,
     *              whose message will be stored in the record for future reference
     * @return a Mono emitting an IdempotencyResult indicating that the request failed,
     *         with a generic error message for the client
     */
    private Mono<IdempotencyResult> failRecord(String key,
                                               Throwable error) {
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

    /**
     * Hashes the request payload using SHA-256 to create a unique fingerprint of the request body.
     * @param payload the request body to hash, which will be converted to JSON and then hashed
     *                to ensure that the same idempotency key cannot be reused with a different payload without detection
     * @return a hexadecimal string representing the hash of the request payload, which is stored
     *         in the database to compare against future requests with the same idempotency key
     */
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