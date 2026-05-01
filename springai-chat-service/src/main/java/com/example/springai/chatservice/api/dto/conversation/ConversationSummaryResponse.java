package com.example.springai.chatservice.api.dto.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ConversationSummaryResponse(
        UUID id,
        String title,
        String status,
        OffsetDateTime updatedAt) {
}
