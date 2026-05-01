package com.example.springai.chatservice.api.dto.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Builder;

@Builder
public record MessageResponse(
        UUID id,
        String role,
        String content,
        String status,
        OffsetDateTime createdAt) {
}
