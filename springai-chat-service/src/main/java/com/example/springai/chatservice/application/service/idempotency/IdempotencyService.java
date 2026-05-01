package com.example.springai.chatservice.application.service.idempotency;

import com.example.springai.chatservice.infrastructure.persistence.entity.ApiIdempotencyRecordEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.ApiIdempotencyRecordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IdempotencyService {

    private final ApiIdempotencyRecordRepository repository;
    private final StringRedisTemplate redisTemplate;

    public IdempotencyService(ApiIdempotencyRecordRepository repository, StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional(readOnly = true)
    public Optional<String> resolveDuplicateResponse(String userEmail, String idempotencyKey, String requestFingerprint) {
        String redisKey = redisKey(userEmail, idempotencyKey);
        String cachedResponse = redisTemplate.opsForValue().get(redisKey);
        if (cachedResponse != null) {
            return Optional.of(cachedResponse);
        }

        return repository.findByIdempotencyKeyAndUserEmail(idempotencyKey, userEmail)
                .filter(record -> record.getRequestFingerprint().equals(requestFingerprint))
                .filter(record -> "COMPLETED".equals(record.getStatus()))
                .map(ApiIdempotencyRecordEntity::getResponseBody);
    }

    public void markCompleted(String userEmail, String idempotencyKey, String requestFingerprint, String responseBody) {
        ApiIdempotencyRecordEntity record = repository.findByIdempotencyKeyAndUserEmail(idempotencyKey, userEmail)
                .orElseGet(ApiIdempotencyRecordEntity::new);
        record.setIdempotencyKey(idempotencyKey);
        record.setUserEmail(userEmail);
        record.setRequestFingerprint(requestFingerprint);
        record.setStatus("COMPLETED");
        record.setResponseBody(responseBody);
        record.setCreatedAt(OffsetDateTime.now());
        record.setExpiresAt(OffsetDateTime.now().plusHours(12));
        repository.save(record);
        redisTemplate.opsForValue().set(redisKey(userEmail, idempotencyKey), responseBody);
    }

    public String fingerprint(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create idempotency fingerprint", ex);
        }
    }

    private String redisKey(String userEmail, String idempotencyKey) {
        return "idempotency:" + userEmail + ":" + idempotencyKey;
    }
}
