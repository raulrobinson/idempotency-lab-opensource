package com.example.idempotency.infrastructure.mongo;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

/**
 * Repository interface for managing IdempotencyRecord entities in MongoDB.
 * Extends ReactiveMongoRepository to provide reactive CRUD operations.
 * The IdempotencyRecord class represents the structure of the documents stored in the MongoDB collection.
 * The String type parameter indicates that the ID field of the IdempotencyRecord is of type String.
 */
public interface IdempotencyRecordRepository
        extends ReactiveMongoRepository<IdempotencyRecord, String> {
}