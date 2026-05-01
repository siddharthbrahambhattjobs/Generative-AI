package com.example.springai.chatservice.infrastructure.persistence.repository;

import com.example.springai.chatservice.infrastructure.persistence.entity.ApiIdempotencyRecordEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiIdempotencyRecordRepository extends JpaRepository<ApiIdempotencyRecordEntity, UUID> {
    Optional<ApiIdempotencyRecordEntity> findByIdempotencyKeyAndUserEmail(String idempotencyKey, String userEmail);
    List<ApiIdempotencyRecordEntity> findByExpiresAtBefore(OffsetDateTime cutoff);
}
