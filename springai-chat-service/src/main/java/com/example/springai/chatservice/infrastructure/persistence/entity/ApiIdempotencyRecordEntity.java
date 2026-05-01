package com.example.springai.chatservice.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@Entity
@Table(name = "api_idempotency_record")
public class ApiIdempotencyRecordEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "user_email", nullable = false, length = 320)
    private String userEmail;

    @Column(name = "request_fingerprint", nullable = false, length = 128)
    private String requestFingerprint;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}
