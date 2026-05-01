package com.example.springai.chatservice.application.event;

import java.util.UUID;

public record AttachmentIngestionRequestedEvent(
        UUID attachmentId,
        UUID userId,
        String fileName,
        String storagePath,
        long fileSize,
        String contentType,
        String contentHash) {
}