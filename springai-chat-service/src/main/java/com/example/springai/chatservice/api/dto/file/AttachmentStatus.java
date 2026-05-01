package com.example.springai.chatservice.api.dto.file;

import java.util.UUID;

public record AttachmentStatus(
        UUID fileId,
        String fileName,
        String processingStatus,
        boolean active,
        Integer versionNumber) {
}