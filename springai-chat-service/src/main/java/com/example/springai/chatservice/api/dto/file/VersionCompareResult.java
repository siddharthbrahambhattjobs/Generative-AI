package com.example.springai.chatservice.api.dto.file;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Builder;

@Builder
public record VersionCompareResult(
        String fileName,
        int leftVersion,
        int rightVersion,
        UUID leftAttachmentId,
        UUID rightAttachmentId,
        OffsetDateTime leftCreatedAt,
        OffsetDateTime rightCreatedAt,
        String leftPreview,
        String rightPreview,
        boolean sameContent) {
}