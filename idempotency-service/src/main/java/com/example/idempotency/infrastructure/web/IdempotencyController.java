package com.example.idempotency.infrastructure.web;

import com.example.idempotency.application.IdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/idempotency")
public class IdempotencyController {

    private final IdempotencyService idempotencyService;

    /**
     * Endpoint to handle idempotent requests. Clients must include an "Idempotency-Key" header and a JSON payload.
     * @param idempotencyKey string representing the idempotency key, must be unique for each distinct request
     * @param payload JSON body of the request, will be hashed and stored to ensure idempotency
     * @return a Mono emitting a ResponseEntity containing the result of the operation,
     *         with appropriate status code and body based on the idempotency logic.
     */
    @PostMapping("/execute")
    public Mono<ResponseEntity<Map<String, Object>>> execute(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> payload
    ) {
        return idempotencyService.handle(idempotencyKey, payload)
                .map(result -> ResponseEntity
                        .status(result.statusCode())
                        .body(result.body()));
    }
}