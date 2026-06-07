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