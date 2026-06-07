package com.example.idempotency.infrastructure.mongo;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface IdempotencyRecordRepository
        extends ReactiveMongoRepository<IdempotencyRecord, String> {
}